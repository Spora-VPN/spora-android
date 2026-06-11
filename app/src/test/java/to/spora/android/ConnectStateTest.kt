package to.spora.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ConnectStateTest {

    @Before
    fun resetState() {
        ConnectState.reset()
    }

    private fun state() = ConnectState.uiState.value

    @Test
    fun `connecting sets flags and clears previous error`() {
        ConnectState.failed(UserError.CONNECT_FAILED, "x")
        ConnectState.connecting("a")

        assertTrue(state().isConnecting)
        assertFalse(state().isConnected)
        assertNull(state().error)
        assertNull(state().errorConnectionId)
        assertEquals("a", state().activeConnectionId)
    }

    @Test
    fun `connected keeps the active connection id`() {
        ConnectState.connecting("a")
        ConnectState.connected()

        assertTrue(state().isConnected)
        assertFalse(state().isConnecting)
        assertEquals("a", state().activeConnectionId)
    }

    @Test
    fun `failed attributes error to the active connection`() {
        ConnectState.connecting("a")
        ConnectState.failed(UserError.CONNECT_FAILED)

        assertFalse(state().isConnecting)
        assertEquals(UserError.CONNECT_FAILED, state().error)
        assertEquals("a", state().errorConnectionId)
        assertNull(state().activeConnectionId)
    }

    @Test
    fun `failed with explicit id overrides attribution`() {
        ConnectState.failed(UserError.VPN_PERMISSION_DENIED, "b")

        assertEquals(UserError.VPN_PERMISSION_DENIED, state().error)
        assertEquals("b", state().errorConnectionId)
    }

    @Test
    fun `serviceStopped keeps error but drops live flags`() {
        ConnectState.connecting("a")
        ConnectState.failed(UserError.CONNECT_FAILED)
        ConnectState.serviceStopped()

        assertFalse(state().isConnected)
        assertFalse(state().isConnecting)
        assertNull(state().activeConnectionId)
        assertEquals(UserError.CONNECT_FAILED, state().error)
        assertEquals("a", state().errorConnectionId)
    }

    @Test
    fun `disconnected clears everything`() {
        ConnectState.connecting("a")
        ConnectState.failed(UserError.CONNECT_FAILED)
        ConnectState.disconnected()

        assertNull(state().error)
        assertNull(state().errorConnectionId)
        assertNull(state().activeConnectionId)
    }

    @Test
    fun `removeConnection clears error attribution for that id`() {
        ConnectState.addConnection(SavedUseConnection("a", "label", "url"))
        ConnectState.failed(UserError.CONNECT_FAILED, "a")

        ConnectState.removeConnection("a")

        assertTrue(state().savedConnections.isEmpty())
        assertNull(state().errorConnectionId)
    }
}
