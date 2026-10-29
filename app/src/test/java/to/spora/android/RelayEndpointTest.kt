package to.spora.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Relay addresses as the core's `RelayEndpoint::from_url_param` reads them. */
class RelayEndpointTest {

    @Test
    fun `accepts what the core accepts`() {
        for (ok in listOf(
            "relay.example.org:443",
            "203.0.113.7:443",
            "[2001:db8::7]:443",
            "[::1]:1",
            "[::ffff:192.0.2.1]:65535",
            "nz/relay.example.org:443",
            "tcptls/203.0.113.7:8443",
            "quic/relay.example.org:443",
            "udpquic/relay.example.org:443",
            "direct/[2001:db8::7]:20000",
        )) {
            assertNull(ok, relayEndpointError(ok))
        }
    }

    @Test
    fun `names what is wrong`() {
        assertEquals(RelayEndpointError.FORMAT, relayEndpointError(""))
        assertEquals(RelayEndpointError.FORMAT, relayEndpointError("relay.example.org"))
        assertEquals(RelayEndpointError.FORMAT, relayEndpointError(":443"))
        assertEquals(RelayEndpointError.FORMAT, relayEndpointError("relay example.org:443"))
        assertEquals(RelayEndpointError.FORMAT, relayEndpointError("[2001:db8::7]443"))
        assertEquals(RelayEndpointError.FORMAT, relayEndpointError("[not:v6]:443"))
        assertEquals(RelayEndpointError.FORMAT, relayEndpointError("nz/a/b:443"))
        assertEquals(RelayEndpointError.PROTOCOL, relayEndpointError("wss/relay.example.org:443"))
        assertEquals(RelayEndpointError.PROTOCOL, relayEndpointError("https://relay.example.org:443"))
        assertEquals(RelayEndpointError.IPV6_BRACKETS, relayEndpointError("2001:db8::7:443"))
        assertEquals(RelayEndpointError.PORT, relayEndpointError("relay.example.org:"))
        assertEquals(RelayEndpointError.PORT, relayEndpointError("relay.example.org:0"))
        assertEquals(RelayEndpointError.PORT, relayEndpointError("relay.example.org:65536"))
        assertEquals(RelayEndpointError.PORT, relayEndpointError("relay.example.org:+443"))
        assertEquals(RelayEndpointError.PORT, relayEndpointError("relay.example.org:٤٤٣"))
    }

    @Test
    fun `ipv6 literals are checked without a lookup`() {
        assertTrue(isIpv6Literal("2001:db8:0:0:0:0:0:7"))
        assertTrue(isIpv6Literal("::"))
        assertTrue(isIpv6Literal("fe80::1"))
        assertTrue(isIpv6Literal("64:ff9b::192.0.2.33"))
        assertFalse(isIpv6Literal("2001:db8::7::1"))
        assertFalse(isIpv6Literal("2001:db8:0:0:0:0:0:0:7"))
        assertFalse(isIpv6Literal("12345::1"))
        assertFalse(isIpv6Literal("g::1"))
        assertFalse(isIpv6Literal("::192.0.2.256"))
        assertFalse(isIpv6Literal("::192.0.2.01"))
        assertFalse(isIpv6Literal("192.0.2.1::"))
        assertFalse(isIpv6Literal("fe80::1%wlan0"))
    }

    @Test
    fun `typed addresses are normalized`() {
        assertEquals("relay.example.org:443", normalizeRelayEndpoint("  relay.example.org:443\n"))
        // a value copied out of a share URL's ?r=
        assertEquals("nz/[2001:db8::7]:443", normalizeRelayEndpoint("nz%2F%5B2001%3Adb8%3A%3A7%5D%3A443"))
    }

    @Test
    fun `a share needs some relay`() {
        assertTrue(RelaySettingsState().hasRelays)
        assertFalse(RelaySettingsState(useSpora = false).hasRelays)
        assertTrue(RelaySettingsState(useSpora = false, own = listOf(OwnRelay("r:1"))).hasRelays)
    }

    @Test
    fun `tokens stay out of logs`() {
        val relay = OwnRelay("relay.example.org:443", "s3cret")
        assertFalse(relay.toString().contains("s3cret"))
        assertFalse(SharedConnection("id", "Mom", "c2VjcmV0").toString().contains("c2VjcmV0"))
    }
}
