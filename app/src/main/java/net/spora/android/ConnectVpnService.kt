package net.spora.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * VPN service for the client/connect mode.
 *
 * Creates a TUN interface with limited routes (only two hardcoded IPs) and passes
 * the file descriptor to the Rust `connect()` function.
 */
class ConnectVpnService : VpnService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var vpnInterface: ParcelFileDescriptor? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                val url = intent.getStringExtra(EXTRA_URL)
                if (url != null) {
                    startConnecting(url)
                } else {
                    ConnectState.failed(IllegalArgumentException("URL is required"))
                    stopSelf()
                }
            }
            ACTION_DISCONNECT -> disconnect()
        }
        return START_NOT_STICKY
    }

    private fun startConnecting(url: String) {
        val currentState = ConnectState.uiState.value
        if (currentState.isConnecting || currentState.isConnected) return

        ConnectState.connecting()
        startForeground(
            NOTIFICATION_ID,
            buildNotification(
                contentText = "Connecting…",
                isOngoing = true,
                includeDisconnectAction = true,
            ),
        )

        serviceScope.launch {
            try {
                // Create VPN interface with limited routes
                val builder = Builder()
                    .setSession("Spora VPN")
                    .addAddress(TUN_ADDRESS, TUN_PREFIX_LENGTH)
                    // Only route these two specific IPs through the tunnel
                    .addRoute(ROUTE_IP_1, 32)
                    .addRoute(ROUTE_IP_2, 32)
                    .setMtu(MTU)

                vpnInterface = builder.establish()
                    ?: throw IllegalStateException("Failed to establish VPN interface")

                val tunFd = vpnInterface!!.fd

                // Call Rust connect function
                uniffi.spora_ffi.connect(url, tunFd)

                ConnectState.connected()
                notify(
                    buildNotification(
                        contentText = "Connected",
                        isOngoing = true,
                        includeDisconnectAction = true,
                    )
                )
            } catch (t: Throwable) {
                ConnectState.failed(t)
                notify(
                    buildNotification(
                        contentText = "Error: ${t.message ?: t::class.java.simpleName}",
                        isOngoing = false,
                        includeDisconnectAction = false,
                    )
                )
                closeVpnInterface()
                stopSelf()
            }
        }
    }

    private fun disconnect() {
        ConnectState.disconnected()
        closeVpnInterface()
        stopForegroundCompat()
        stopSelf()
    }

    private fun closeVpnInterface() {
        try {
            vpnInterface?.close()
        } catch (_: Exception) {
        }
        vpnInterface = null
    }

    private fun notify(notification: Notification) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(
        contentText: String,
        isOngoing: Boolean,
        includeDisconnectAction: Boolean,
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
            .setContentTitle("Spora VPN")
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            .setContentIntent(openPendingIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(isOngoing)
            .setOnlyAlertOnce(true)

        if (includeDisconnectAction) {
            val disconnectIntent = Intent(this, ConnectVpnService::class.java)
                .setAction(ACTION_DISCONNECT)
            val disconnectPendingIntent = PendingIntent.getService(
                this,
                1,
                disconnectIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.addAction(0, "Disconnect", disconnectPendingIntent)
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
            "Spora VPN",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Required notification while VPN is connected"
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
        closeVpnInterface()
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "spora_vpn"
        private const val NOTIFICATION_ID = 2

        private const val ACTION_CONNECT = "net.spora.android.action.CONNECT"
        private const val ACTION_DISCONNECT = "net.spora.android.action.DISCONNECT"
        private const val EXTRA_URL = "url"

        // VPN configuration
        private const val TUN_ADDRESS = "10.11.0.2"
        private const val TUN_PREFIX_LENGTH = 24
        private const val MTU = 1500

        // Limited routes - only these IPs will go through the tunnel
        private const val ROUTE_IP_1 = "172.67.163.127"
        private const val ROUTE_IP_2 = "104.21.49.135"

        fun connect(context: Context, url: String) {
            val intent = Intent(context, ConnectVpnService::class.java)
                .setAction(ACTION_CONNECT)
                .putExtra(EXTRA_URL, url)
            ContextCompat.startForegroundService(context, intent)
        }

        fun disconnect(context: Context) {
            val intent = Intent(context, ConnectVpnService::class.java)
                .setAction(ACTION_DISCONNECT)
            context.startService(intent)
        }
    }
}
