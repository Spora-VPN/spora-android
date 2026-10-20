package to.spora.android

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import uniffi.spora_ffi.NetworkKind
import uniffi.spora_ffi.setNetworkState

/** What the core is told about the network underneath (`setNetworkState`). */
data class UnderlyingNetwork(val kind: NetworkKind, val validated: Boolean?) {
    companion object {
        /** No non-VPN network with internet at all. */
        val NONE = UnderlyingNetwork(NetworkKind.NONE, null)
    }
}

/**
 * Reports the network underneath any tunnel to the core, which records it in
 * the connection records: a run that learned no address of its own can
 * still tell "no usable network" from "network up, everything blocked".
 *
 * Not the default network: while this app's own VPN is up, the default
 * network IS the VPN. Tracked instead are the non-VPN networks with
 * INTERNET, and the one reported is the one the system would use
 * underneath: on Android 12+ the best-matching callback ranks them as the
 * system ranks its default; before that, the default network when it is one
 * of them, else the validated one on the preferred transport.
 *
 * Lives for the process; all state is confined to its own handler thread.
 */
class UnderlyingNetworkReporter(context: Context) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val handler = Handler(HandlerThread("spora-network").apply { start() }.looper)
    private var reported: UnderlyingNetwork? = null

    fun start() {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        // Callbacks only ever describe networks that exist, so having none at
        // all is silence; queued ahead of the registration's own callbacks
        handler.post { if (cm.activeNetwork == null) report(UnderlyingNetwork.NONE) }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                cm.registerBestMatchingNetworkCallback(request, BestMatch(), handler)
            } else {
                cm.registerNetworkCallback(request, AllMatching(), handler)
            }
        } catch (t: Throwable) {
            SporaLog.w(TAG, "cannot follow the underlying network", t)
        }
    }

    private inner class BestMatch : ConnectivityManager.NetworkCallback() {
        private val tracker = BestMatchTracker<Network> { report(it) }
        private val lossSettled = Runnable { tracker.lossSettled() }

        // Also delivered right after every onAvailable
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            tracker.onCapabilities(network, caps.underlying())
        }

        override fun onLost(network: Network) {
            if (!tracker.onLost(network)) return
            handler.removeCallbacks(lossSettled)
            handler.postDelayed(lossSettled, NONE_SETTLE_MS)
        }
    }

    private inner class AllMatching : ConnectivityManager.NetworkCallback() {
        private val networks = HashMap<Network, UnderlyingNetwork>()

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            networks[network] = caps.underlying()
            report(pickUnderlying(networks, cm.activeNetwork))
        }

        override fun onLost(network: Network) {
            networks.remove(network)
            report(pickUnderlying(networks, cm.activeNetwork))
        }
    }

    private fun report(state: UnderlyingNetwork) {
        // Capabilities change with every signal-strength step; the core
        // ignores repeats too, but they need not cross the FFI
        if (state == reported) return
        reported = state
        SporaLog.i(TAG, "underlying network: ${state.kind} validated=${state.validated}")
        try {
            setNetworkState(state.kind, state.validated)
        } catch (t: Throwable) {
            SporaLog.w(TAG, "setNetworkState failed", t)
        }
    }

    private companion object {
        const val TAG = "UnderlyingNetwork"

        // How long a lost best network may go unreplaced before it is NONE
        const val NONE_SETTLE_MS = 2_000L
    }
}

/**
 * The Android 12+ best-matching callback's bookkeeping. The platform sends
 * onLost for the best network BEFORE it re-ranks the others
 * (ConnectivityService notifies the loss, then rematches), so a Wi-Fi loss
 * with cellular already up arrives as onLost(wifi) followed by the cellular
 * network's callbacks. A loss is therefore no network only if nothing has
 * replaced it when [lossSettled] runs, a moment later: [onLost] says when to
 * schedule it.
 */
internal class BestMatchTracker<N>(private val report: (UnderlyingNetwork) -> Unit) {
    private var best: N? = null

    fun onCapabilities(network: N, state: UnderlyingNetwork) {
        best = network
        report(state)
    }

    /** Whether this was the best network, so [lossSettled] is owed. */
    fun onLost(network: N): Boolean {
        if (network != best) return false
        best = null
        return true
    }

    fun lossSettled() {
        if (best == null) report(UnderlyingNetwork.NONE)
    }
}

private fun NetworkCapabilities.underlying() = UnderlyingNetwork(
    networkKind { hasTransport(it) },
    hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
)

internal fun networkKind(hasTransport: (Int) -> Boolean): NetworkKind = when {
    hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkKind.ETHERNET
    hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkKind.WIFI
    hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkKind.CELLULAR
    else -> NetworkKind.OTHER
}

/**
 * The system's pick among [networks] before Android 12: [default] when it is
 * one of them (no VPN in the way), else validated before not, then by
 * transport, the way Android ranks them.
 */
internal fun <K> pickUnderlying(networks: Map<K, UnderlyingNetwork>, default: K?): UnderlyingNetwork {
    default?.let { networks[it] }?.let { return it }
    return networks.values.maxWithOrNull(
        compareBy<UnderlyingNetwork>({ it.validated == true }, { transportRank(it.kind) }),
    ) ?: UnderlyingNetwork.NONE
}

private fun transportRank(kind: NetworkKind) = when (kind) {
    NetworkKind.ETHERNET -> 3
    NetworkKind.WIFI -> 2
    NetworkKind.CELLULAR -> 1
    else -> 0
}
