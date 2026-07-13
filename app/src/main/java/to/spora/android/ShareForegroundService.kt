package to.spora.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
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
                // Connection logging stays off until the app wires up a log
                // directory (and backup exclusion) for it.
                val result = uniffi.spora_ffi.share(
                    identityBytes,
                    null,
                    connLogDir = null,
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
