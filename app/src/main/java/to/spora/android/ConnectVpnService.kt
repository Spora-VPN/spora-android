package to.spora.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Build
import android.util.Log
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
    private var tunFd: Int = -1
    @Volatile private var currentMtu: Int = DEFAULT_MTU
    @Volatile private var fullRoutesEstablished: Boolean = false

    // Set by disconnect() while the connect coroutine is still blocked inside
    // the FFI connect() call; the coroutine checks it to avoid reporting a
    // user-initiated cancel as an error or resurrecting torn-down state.
    @Volatile private var cancelRequested = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val handle = tunnelHandle ?: return
            try {
                when (intent.action) {
                    Intent.ACTION_SCREEN_ON -> {
                        Log.d(TAG, "Screen ON — setting keepalive to 20s")
                        uniffi.spora_ffi.setKeepalive(handle, 20u)
                    }
                    Intent.ACTION_SCREEN_OFF -> {
                        Log.d(TAG, "Screen OFF — disabling keepalive")
                        uniffi.spora_ffi.setKeepalive(handle, 0u)
                    }
                }
            } catch (_: Exception) {}
        }
    }

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
                    Log.e(TAG, "Connect intent missing URL extra")
                    ConnectState.failed(UserError.GENERIC)
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

        cancelRequested = false
        ConnectState.connecting(connectionId)
        startForeground(
            NOTIFICATION_ID,
            buildNotification(
                contentText = getString(R.string.notif_vpn_connecting),
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
                    .setMtu(DEFAULT_MTU)

                vpnInterface = initialBuilder.establish()
                    ?: throw IllegalStateException("Failed to establish VPN interface")

                tunFd = vpnInterface!!.fd

                // Phase 2: Connect (blocks during STUN). Rust calls back to protect sockets.
                val protector = object : uniffi.spora_ffi.SocketProtectorCallback {
                    override fun protect(fd: Int) {
                        this@ConnectVpnService.protect(fd)
                    }
                }
                val mtuCallback = object : uniffi.spora_ffi.MtuCallback {
                    override fun onMtu(mtu: Int) {
                        Log.d(TAG, "MTU callback: mtu=$mtu")
                        currentMtu = mtu
                        if (fullRoutesEstablished) {
                            rebuildTun(mtu)
                        }
                    }
                }
                val handle = uniffi.spora_ffi.connect(url, tunFd, protector, mtuCallback)
                tunnelHandle = handle

                if (cancelRequested) {
                    // User disconnected while connect() was blocked; the tunnel
                    // it just established must be torn down, not activated.
                    closeTunnel()
                    return@launch
                }

                // Phase 3: Re-establish TUN with full route and swap fd.
                // Use currentMtu which may have been updated by the MTU callback
                // during connect().
                rebuildTun(currentMtu)
                fullRoutesEstablished = true
            } catch (t: Throwable) {
                if (cancelRequested) {
                    closeTunnel()
                    return@launch
                }
                Log.e(TAG, "connect failed", t)
                ConnectState.failed(t.toUserError())
                try {
                    // Separate id: the foreground notification (NOTIFICATION_ID)
                    // is removed by the system when the service stops below.
                    notify(
                        buildNotification(
                            contentText = getString(R.string.notif_vpn_error, getString(t.toUserError().messageRes)),
                            isOngoing = false,
                            includeDisconnectAction = false,
                        ),
                        id = ERROR_NOTIFICATION_ID,
                    )
                } catch (_: Exception) {}
                closeTunnel()
                stopSelf()
                return@launch
            }

            if (cancelRequested) return@launch
            ConnectState.connected()

            // Start with keepalive enabled (user's screen is on when they connect)
            try {
                uniffi.spora_ffi.setKeepalive(tunnelHandle!!, 20u)
            } catch (_: Exception) {}

            // Toggle keepalive based on screen state
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            }
            registerReceiver(screenReceiver, filter)

            try {
                notify(
                    buildNotification(
                        contentText = getString(R.string.notif_vpn_connected),
                        isOngoing = true,
                        includeDisconnectAction = true,
                    )
                )
            } catch (_: Exception) {}
        }
    }

    private fun rebuildTun(mtu: Int) {
        val newInterface = Builder()
            .setSession("Spora VPN")
            .addAddress(TUN_ADDRESS, TUN_PREFIX_LENGTH)
            .addRoute("0.0.0.0", 0)
            .addDnsServer(DNS_SERVER)
            .setMtu(mtu)
            .addDisallowedApplication("com.google.android.gms")
            .establish()
            ?: throw IllegalStateException("Failed to re-establish VPN interface")

        Os.dup2(newInterface.fileDescriptor, tunFd)
        newInterface.close()
        Log.d(TAG, "TUN re-established with MTU=$mtu")
    }

    private fun disconnect() {
        cancelRequested = true
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
        ConnectState.disconnected()
        closeTunnel()
        stopForegroundCompat()
        stopSelf()
    }

    private fun closeTunnel() {
        fullRoutesEstablished = false
        currentMtu = DEFAULT_MTU
        tunFd = -1
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

    private fun notify(notification: Notification, id: Int = NOTIFICATION_ID) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(id, notification)
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
            .setContentTitle(getString(R.string.notif_vpn_title))
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
            builder.addAction(0, getString(R.string.notif_vpn_disconnect), disconnectPendingIntent)
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
            getString(R.string.notif_vpn_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notif_vpn_channel_desc)
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

    // Called by the system when another app becomes the active VPN or the user
    // kills the VPN from settings; without this the UI keeps saying "Connected".
    override fun onRevoke() {
        Log.i(TAG, "VPN revoked by system")
        disconnect()
    }

    override fun onDestroy() {
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
        serviceScope.cancel()
        closeTunnel()
        ConnectState.serviceStopped()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ConnectVpnService"
        private const val NOTIFICATION_CHANNEL_ID = "spora_vpn"
        private const val NOTIFICATION_ID = 2
        private const val ERROR_NOTIFICATION_ID = 3

        private const val ACTION_CONNECT = "to.spora.android.action.CONNECT"
        private const val ACTION_DISCONNECT = "to.spora.android.action.DISCONNECT"
        private const val EXTRA_URL = "url"
        private const val EXTRA_CONNECTION_ID = "connection_id"

        // VPN configuration
        private const val TUN_ADDRESS = "10.11.0.2"
        private const val TUN_PREFIX_LENGTH = 24
        private const val DEFAULT_MTU = 1280
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
