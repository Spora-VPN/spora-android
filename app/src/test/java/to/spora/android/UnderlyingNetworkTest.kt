package to.spora.android

import android.net.NetworkCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.spora_ffi.NetworkKind

class UnderlyingNetworkTest {
    private val wifi = UnderlyingNetwork(NetworkKind.WIFI, true)
    private val cellular = UnderlyingNetwork(NetworkKind.CELLULAR, true)
    private val ethernet = UnderlyingNetwork(NetworkKind.ETHERNET, true)

    @Test
    fun `kind follows the transport, wired before wireless`() {
        fun kindOf(vararg transports: Int) = networkKind { it in transports }
        assertEquals(NetworkKind.WIFI, kindOf(NetworkCapabilities.TRANSPORT_WIFI))
        assertEquals(NetworkKind.CELLULAR, kindOf(NetworkCapabilities.TRANSPORT_CELLULAR))
        assertEquals(NetworkKind.ETHERNET, kindOf(NetworkCapabilities.TRANSPORT_ETHERNET))
        assertEquals(
            NetworkKind.ETHERNET,
            kindOf(NetworkCapabilities.TRANSPORT_ETHERNET, NetworkCapabilities.TRANSPORT_WIFI),
        )
        assertEquals(NetworkKind.OTHER, kindOf(NetworkCapabilities.TRANSPORT_BLUETOOTH))
        assertEquals(NetworkKind.OTHER, kindOf())
    }

    @Test
    fun `no network at all is none`() {
        assertEquals(UnderlyingNetwork.NONE, pickUnderlying(emptyMap<String, UnderlyingNetwork>(), null))
        assertEquals(UnderlyingNetwork.NONE, pickUnderlying(emptyMap<String, UnderlyingNetwork>(), "vpn"))
    }

    @Test
    fun `the default network wins when it is not a vpn`() {
        val networks = mapOf("wifi" to wifi, "cell" to cellular)
        assertEquals(cellular, pickUnderlying(networks, "cell"))
    }

    @Test
    fun `behind a vpn, validated first, then by transport`() {
        val unvalidatedWifi = wifi.copy(validated = false)
        assertEquals(wifi, pickUnderlying(mapOf("wifi" to wifi, "cell" to cellular), "vpn"))
        assertEquals(ethernet, pickUnderlying(mapOf("wifi" to wifi, "eth" to ethernet), null))
        assertEquals(
            cellular,
            pickUnderlying(mapOf("wifi" to unvalidatedWifi, "cell" to cellular), "vpn"),
        )
        assertEquals(unvalidatedWifi, pickUnderlying(mapOf("wifi" to unvalidatedWifi), "vpn"))
    }

    @Test
    fun `a handover is not reported as no network`() {
        // Android 12+: onLost(wifi) comes before the cellular network's callbacks
        val reports = mutableListOf<UnderlyingNetwork>()
        val tracker = BestMatchTracker<String> { reports += it }
        tracker.onCapabilities("wifi", wifi)
        assertTrue(tracker.onLost("wifi"))
        tracker.onCapabilities("cell", cellular)
        tracker.lossSettled()
        assertEquals(listOf(wifi, cellular), reports)
    }

    @Test
    fun `a loss nothing replaces is no network`() {
        val reports = mutableListOf<UnderlyingNetwork>()
        val tracker = BestMatchTracker<String> { reports += it }
        tracker.onCapabilities("wifi", wifi)
        assertTrue(tracker.onLost("wifi"))
        tracker.lossSettled()
        assertEquals(listOf(wifi, UnderlyingNetwork.NONE), reports)
    }

    @Test
    fun `losing a network that is not the best changes nothing`() {
        val reports = mutableListOf<UnderlyingNetwork>()
        val tracker = BestMatchTracker<String> { reports += it }
        tracker.onCapabilities("wifi", wifi)
        assertFalse(tracker.onLost("cell"))
        tracker.lossSettled()
        assertEquals(listOf(wifi), reports)
    }
}
