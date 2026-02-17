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
import android.system.Os
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
 * Uses a two-phase TUN establishment:
 * 1. Create TUN with no routes so STUN negotiation uses normal network
 * 2. After connect + socket protection, re-establish TUN with 0.0.0.0/0 route
 *    and use dup2 to swap the fd Rust is using
 */
class ConnectVpnService : VpnService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var vpnInterface: ParcelFileDescriptor? = null
    private var tunnelHandle: Int? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                val url = intent.getStringExtra(EXTRA_URL)
                val connectionId = intent.getStringExtra(EXTRA_CONNECTION_ID)
                if (url != null) {
                    startConnecting(url, connectionId)
                } else {
                    ConnectState.failed(IllegalArgumentException("URL is required"))
                    stopSelf()
                }
            }
            ACTION_DISCONNECT -> disconnect()
        }
        return START_NOT_STICKY
    }

    private fun startConnecting(url: String, connectionId: String?) {
        val currentState = ConnectState.uiState.value
        if (currentState.isConnecting || currentState.isConnected) return

        ConnectState.connecting(connectionId)
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
                // Phase 1: Create TUN with no routes so STUN packets use normal network
                val initialBuilder = Builder()
                    .setSession("Spora VPN")
                    .addAddress(TUN_ADDRESS, TUN_PREFIX_LENGTH)
                    .setMtu(MTU)

                vpnInterface = initialBuilder.establish()
                    ?: throw IllegalStateException("Failed to establish VPN interface")

                val tunFd = vpnInterface!!.fd

                // Phase 2: Connect (blocks during STUN). Rust calls back to protect sockets.
                val protector = object : uniffi.spora_ffi.SocketProtectorCallback {
                    override fun protect(fd: Int) {
                        this@ConnectVpnService.protect(fd)
                    }
                }
                val handle = uniffi.spora_ffi.connect(url, tunFd, protector)
                tunnelHandle = handle

                // Phase 3: Re-establish TUN with full route and swap fd
                val fullRouteBuilder = Builder()
                    .setSession("Spora VPN")
                    .addAddress(TUN_ADDRESS, TUN_PREFIX_LENGTH)
                    .addRoute("0.0.0.0", 0)
                    .addDnsServer(DNS_SERVER)
                    .setMtu(MTU)

                val newInterface = fullRouteBuilder.establish()
                    ?: throw IllegalStateException("Failed to re-establish VPN interface with routes")

                Os.dup2(newInterface.fileDescriptor, tunFd)
                newInterface.close()

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
                closeTunnel()
                stopSelf()
            }
        }
    }

    private fun disconnect() {
        ConnectState.disconnected()
        closeTunnel()
        stopForegroundCompat()
        stopSelf()
    }

    private fun closeTunnel() {
        tunnelHandle?.let { handle ->
            // Release ParcelFileDescriptor ownership of the TUN fd
            // so Rust's disconnect() can close it without fdsan aborting
            try {
                vpnInterface?.detachFd()
            } catch (_: Exception) {
            }
            vpnInterface = null
            try {
                uniffi.spora_ffi.disconnect(handle)
            } catch (_: Exception) {
            }
            tunnelHandle = null
        }
        // No tunnel handle means Rust never got the fd — close normally
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
        closeTunnel()
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "spora_vpn"
        private const val NOTIFICATION_ID = 2

        private const val ACTION_CONNECT = "net.spora.android.action.CONNECT"
        private const val ACTION_DISCONNECT = "net.spora.android.action.DISCONNECT"
        private const val EXTRA_URL = "url"
        private const val EXTRA_CONNECTION_ID = "connection_id"

        // VPN configuration
        private const val TUN_ADDRESS = "10.11.0.2"
        private const val TUN_PREFIX_LENGTH = 24
        private const val MTU = 1500
        private const val DNS_SERVER = "8.8.8.8"

        fun connect(context: Context, url: String, connectionId: String? = null) {
            val intent = Intent(context, ConnectVpnService::class.java)
                .setAction(ACTION_CONNECT)
                .putExtra(EXTRA_URL, url)
            if (connectionId != null) {
                intent.putExtra(EXTRA_CONNECTION_ID, connectionId)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun disconnect(context: Context) {
            val intent = Intent(context, ConnectVpnService::class.java)
                .setAction(ACTION_DISCONNECT)
            context.startService(intent)
        }
    }
}
