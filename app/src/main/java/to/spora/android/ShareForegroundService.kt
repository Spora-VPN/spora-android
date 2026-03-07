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
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class ShareForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeJobs = mutableMapOf<String, Job>()

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
                val key = intent.getStringExtra(EXTRA_KEY)
                    ?: return START_NOT_STICKY
                startConnection(connectionId, key)
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

    private fun startConnection(connectionId: String, key: String) {
        if (activeJobs.containsKey(connectionId)) return

        ShareState.starting(connectionId)

        if (activeJobs.isEmpty()) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(getString(R.string.notif_share_starting)),
            )
        }

        val job = serviceScope.launch {
            try {
                val result = uniffi.spora_ffi.share(key, null)
                ShareState.started(connectionId, result.handle, result.url)
                updateNotification()
            } catch (t: Throwable) {
                ShareState.failed(connectionId, t)
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
    }

    private fun stopConnection(connectionId: String) {
        activeJobs.remove(connectionId)?.cancel()

        val shareInfo = ShareState.uiState.value.activeShares[connectionId]
        shareInfo?.let {
            try {
                uniffi.spora_ffi.stopShare(it.handle)
            } catch (_: Throwable) {
                // Best-effort cleanup; handle may already be invalid.
            }
        }
        ShareState.stopped(connectionId)

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
        val count = activeJobs.size
        val text = resources.getQuantityString(R.plurals.notif_share_count, count, count)
        notify(buildNotification(text))
    }

    private fun notify(notification: Notification) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(contentText: String): Notification {
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
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.notif_share_title))
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
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "spora_share"
        private const val NOTIFICATION_ID = 1

        private const val ACTION_START_CONNECTION =
            "to.spora.android.action.START_SHARE_CONNECTION"
        private const val ACTION_STOP_CONNECTION =
            "to.spora.android.action.STOP_SHARE_CONNECTION"
        private const val ACTION_STOP_ALL =
            "to.spora.android.action.STOP_ALL_SHARES"

        private const val EXTRA_CONNECTION_ID = "connection_id"
        private const val EXTRA_KEY = "key"

        fun startConnection(context: Context, connectionId: String, key: String) {
            val intent = Intent(context, ShareForegroundService::class.java)
                .setAction(ACTION_START_CONNECTION)
                .putExtra(EXTRA_CONNECTION_ID, connectionId)
                .putExtra(EXTRA_KEY, key)
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
