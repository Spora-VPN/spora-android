package to.spora.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

class ShareForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeJobs = ConcurrentHashMap<String, Job>()

    // Android has no resolv.conf, so the core's DNS forwarder (which answers
    // clients' queries from this device's resolvers) is told what they are:
    // once per share at start, and again whenever the default network's link
    // changes (WiFi <-> cellular, a DHCP renew with new resolvers).
    private val connectivity by lazy { getSystemService(ConnectivityManager::class.java) }
    @Volatile private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_CONNECTION -> {
                val connectionId = intent.getStringExtra(EXTRA_CONNECTION_ID)
                    ?: return START_NOT_STICKY
                val identity = intent.getStringExtra(EXTRA_IDENTITY)
                    ?: return START_NOT_STICKY
                startConnection(connectionId, identity)
            }
            ACTION_STOP_CONNECTION -> {
                val connectionId = intent.getStringExtra(EXTRA_CONNECTION_ID)
                    ?: return START_NOT_STICKY
                stopConnection(connectionId)
            }
            ACTION_STOP_ALL -> stopAll()
        }
        return START_NOT_STICKY
    }

    private fun startConnection(connectionId: String, identity: String) {
        if (activeJobs.containsKey(connectionId)) return

        ShareState.starting(connectionId)

        if (activeJobs.isEmpty()) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(
                    getString(R.string.notif_share_title),
                    getString(R.string.notif_share_starting),
                ),
            )
        }

        // Lazy start so the activeJobs entry is in place before the coroutine
        // can fail and remove it (e.g. a synchronous Base64 decode error).
        val job = serviceScope.launch(start = CoroutineStart.LAZY) {
            try {
                val identityBytes = java.util.Base64.getDecoder().decode(identity)
                // The connection log is a liability record for the sharer, so
                // it is always on. One dir per connection: the core binds a
                // database to the identity that created it and refuses to open
                // it for any other, so a shared dir breaks every share after
                // the first. The connlog tree is excluded from auto-backup (see
                // res/xml backup rules); retention/destination logging use the
                // core defaults (90 days, destinations included).
                val connLogDir = java.io.File(filesDir, "connlog/$connectionId").apply { mkdirs() }
                val result = uniffi.spora_ffi.share(
                    identityBytes,
                    null,
                    connLogDir = connLogDir.absolutePath,
                    connLogRetentionDays = null,
                    connLogSessionsOnly = false,
                )
                if (!isActive) {
                    // Toggled off while share() was blocked starting up: the
                    // session it just created must be stopped, not surfaced.
                    try { uniffi.spora_ffi.stopShare(result.handle) } catch (_: Throwable) {}
                    ShareState.stopped(connectionId)
                    return@launch
                }
                ShareState.started(connectionId, result.handle, result.url)
                updateNotification()
                connectivity.activeNetwork
                    ?.let { connectivity.getLinkProperties(it) }
                    ?.let { pushDnsServers(it, listOf(result.handle)) }
                ensureNetworkCallback()
            } catch (t: Throwable) {
                if (!isActive) return@launch
                Log.e(TAG, "share failed for $connectionId", t)
                ShareState.failed(connectionId, t.toUserError())
                activeJobs.remove(connectionId)
                if (activeJobs.isEmpty()) {
                    stopForegroundCompat()
                    stopSelf()
                } else {
                    updateNotification()
                }
            }
        }
        activeJobs[connectionId] = job
        job.start()
    }

    /**
     * Tell the share sessions in [handles] which resolvers this device uses
     * (see spora-core's `dns`: the forwarder sends clients' queries there,
     * with a public fallback when the list is empty or nothing answers).
     */
    private fun pushDnsServers(lp: LinkProperties, handles: Collection<Int>) {
        val servers = lp.dnsServers.mapNotNull { it.hostAddress }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && lp.isPrivateDnsActive) {
            // Known defect (spora-core `dns`): the system resolves over TLS,
            // the forwarder sends plain UDP to the same servers.
            Log.w(
                TAG,
                "Private DNS is active (${lp.privateDnsServerName ?: "opportunistic"}): " +
                    "clients' queries are forwarded to $servers in the clear",
            )
        }
        Log.d(TAG, "resolvers for shares $handles: $servers")
        for (handle in handles) {
            try {
                uniffi.spora_ffi.setShareDnsServers(handle, servers)
            } catch (t: Throwable) {
                // The share may have been stopped between the snapshot and
                // this call; the next link change will not include it.
                Log.w(TAG, "setShareDnsServers($handle) failed", t)
            }
        }
    }

    @Synchronized
    private fun ensureNetworkCallback() {
        if (networkCallback != null) return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
                val handles = ShareState.uiState.value.activeShares.values.map { it.handle }
                if (handles.isNotEmpty()) pushDnsServers(lp, handles)
            }
        }
        networkCallback = callback
        try {
            connectivity.registerDefaultNetworkCallback(callback)
        } catch (t: Throwable) {
            Log.w(TAG, "cannot follow the default network's resolvers", t)
            networkCallback = null
        }
    }

    private fun stopConnection(connectionId: String) {
        activeJobs.remove(connectionId)?.cancel()

        val shareInfo = ShareState.uiState.value.activeShares[connectionId]
        ShareState.stopped(connectionId)
        shareInfo?.let { info ->
            // Blocking FFI teardown off the main thread, on a scope that
            // survives the stopSelf below
            teardownScope.launch {
                try {
                    uniffi.spora_ffi.stopShare(info.handle)
                } catch (_: Throwable) {
                    // Best-effort cleanup; handle may already be invalid.
                }
            }
        }

        if (activeJobs.isEmpty()) {
            stopForegroundCompat()
            stopSelf()
        } else {
            updateNotification()
        }
    }

    private fun stopAll() {
        activeJobs.keys.toList().forEach { stopConnection(it) }
    }

    private fun updateNotification() {
        // Design 5d: the count line is the title, the shared names the body
        // ("Sharing 2 connections" / "Mom's Phone · For Anya")
        val count = activeJobs.size
        val title = resources.getQuantityString(R.plurals.notif_share_count, count, count)
        val connections = ShareState.uiState.value.connections
        val names = activeJobs.keys.mapNotNull { id ->
            connections.find { it.id == id }?.label
        }.joinToString(" · ")
        notify(buildNotification(title, names.ifEmpty { null }))
    }

    private fun notify(notification: Notification) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(title: String, contentText: String?): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val openPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val stopAllIntent = Intent(this, ShareForegroundService::class.java)
            .setAction(ACTION_STOP_ALL)
        val stopAllPendingIntent = PendingIntent.getService(
            this,
            1,
            stopAllIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_spora)
            .setColor(0xFF587536.toInt()) // success green — the share family accent
            .setContentTitle(title)
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            .setContentIntent(openPendingIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, getString(R.string.notif_share_stop_all), stopAllPendingIntent)
            .build()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val existing = nm.getNotificationChannel(NOTIFICATION_CHANNEL_ID)
        if (existing != null) return

        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.notif_share_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notif_share_channel_desc)
        }

        nm.createNotificationChannel(channel)
    }

    @Suppress("DEPRECATION")
    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            stopForeground(true)
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        networkCallback?.let { cb ->
            try { connectivity.unregisterNetworkCallback(cb) } catch (_: Throwable) {}
        }
        networkCallback = null
        ShareState.serviceStopped()
        super.onDestroy()
    }

    companion object {
        // Outlives any service instance so blocking FFI teardown finishes even
        // after onDestroy cancels serviceScope
        private val teardownScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        private const val TAG = "ShareForegroundService"
        private const val NOTIFICATION_CHANNEL_ID = "spora_share"
        private const val NOTIFICATION_ID = 1

        private const val ACTION_START_CONNECTION =
            "to.spora.android.action.START_SHARE_CONNECTION"
        private const val ACTION_STOP_CONNECTION =
            "to.spora.android.action.STOP_SHARE_CONNECTION"
        private const val ACTION_STOP_ALL =
            "to.spora.android.action.STOP_ALL_SHARES"

        private const val EXTRA_CONNECTION_ID = "connection_id"
        private const val EXTRA_IDENTITY = "identity"

        fun startConnection(context: Context, connectionId: String, identity: String) {
            val intent = Intent(context, ShareForegroundService::class.java)
                .setAction(ACTION_START_CONNECTION)
                .putExtra(EXTRA_CONNECTION_ID, connectionId)
                .putExtra(EXTRA_IDENTITY, identity)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stopConnection(context: Context, connectionId: String) {
            val intent = Intent(context, ShareForegroundService::class.java)
                .setAction(ACTION_STOP_CONNECTION)
                .putExtra(EXTRA_CONNECTION_ID, connectionId)
            context.startService(intent)
        }

        fun stopAll(context: Context) {
            val intent = Intent(context, ShareForegroundService::class.java)
                .setAction(ACTION_STOP_ALL)
            context.startService(intent)
        }
    }
}
