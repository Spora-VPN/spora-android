package net.spora.android

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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service wrapper for running `uniffi.spora_ffi.share()` even when the app is backgrounded.
 *
 * Design:
 * - Activity starts this service via `ContextCompat.startForegroundService(...)`.
 * - Service calls `startForeground(...)` immediately (required by Android), then runs the Rust
 *   sharing routine on a background dispatcher.
 * - The generated URL is stored in [ShareState] for UI display and also shown in the ongoing
 *   notification.
 */
class ShareForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startSharingIfNeeded()
            ACTION_STOP -> stopSharing()
        }
        return START_NOT_STICKY
    }

    private fun startSharingIfNeeded() {
        val currentState = ShareState.uiState.value
        if (currentState.isStarting || currentState.isRunning) return

        ShareState.starting()
        startForeground(
            NOTIFICATION_ID,
            buildNotification(
                contentText = "Starting…",
                isOngoing = true,
                includeStopAction = true,
            ),
        )

        serviceScope.launch {
            try {
                val url = uniffi.spora_ffi.share()
                ShareState.started(url)
                notify(buildNotification(contentText = url, isOngoing = true, includeStopAction = true))
            } catch (t: Throwable) {
                ShareState.failed(t)
                notify(
                    buildNotification(
                        contentText = "Error: ${t.message ?: t::class.java.simpleName}",
                        isOngoing = false,
                        includeStopAction = false,
                    ),
                )
                stopSelf()
            }
        }
    }

    private fun stopSharing() {
        ShareState.stopped()
        stopForegroundCompat()
        stopSelf()
    }

    private fun notify(notification: Notification) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(
        contentText: String,
        isOngoing: Boolean,
        includeStopAction: Boolean,
    ): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val openPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val builder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Spora sharing")
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            .setContentIntent(openPendingIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(isOngoing)
            .setOnlyAlertOnce(true)

        if (includeStopAction) {
            val stopIntent = Intent(this, ShareForegroundService::class.java).setAction(ACTION_STOP)
            val stopPendingIntent = PendingIntent.getService(
                this,
                1,
                stopIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.addAction(0, "Stop", stopPendingIntent)
        }

        return builder.build()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val existing = nm.getNotificationChannel(NOTIFICATION_CHANNEL_ID)
        if (existing != null) return

        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "Spora sharing",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Required notification while sharing is active"
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

        private const val ACTION_START = "net.spora.android.action.START_SHARE"
        private const val ACTION_STOP = "net.spora.android.action.STOP_SHARE"

        fun start(context: Context) {
            val intent = Intent(context, ShareForegroundService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, ShareForegroundService::class.java).setAction(ACTION_STOP)
            // We can use startService here because the service should already be running in FG mode.
            context.startService(intent)
        }
    }
}
