package to.spora.android

import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ShareStateTest {

    @Before
    fun resetState() {
        ShareState.reset()
    }

    private fun state() = ShareState.uiState.value

    @Test
    fun `starting marks id and clears its previous error`() {
        ShareState.failed("a", UserError.SHARE_FAILED)
        ShareState.starting("a")

        assertTrue("a" in state().startingIds)
        assertNull(state().errors["a"])
    }

    @Test
    fun `started moves id from starting to active`() {
        ShareState.starting("a")
        ShareState.started("a", handle = 7, url = "https://spora.to/s/x")

        assertFalse("a" in state().startingIds)
        assertEquals(ActiveShareInfo(7, "https://spora.to/s/x"), state().activeShares["a"])
    }

    @Test
    fun `failed records error and unmarks starting`() {
        ShareState.starting("a")
        ShareState.failed("a", UserError.SHARE_FAILED)

        assertFalse("a" in state().startingIds)
        assertEquals(UserError.SHARE_FAILED, state().errors["a"])
    }

    @Test
    fun `serviceStopped clears live state but keeps errors`() {
        ShareState.starting("a")
        ShareState.started("b", 1, "url")
        ShareState.failed("c", UserError.GENERIC)

        ShareState.serviceStopped()

        assertTrue(state().startingIds.isEmpty())
        assertTrue(state().activeShares.isEmpty())
        assertEquals(UserError.GENERIC, state().errors["c"])
    }

    @Test
    fun `removeConnection clears every trace of the id`() {
        ShareState.addConnection(SharedConnection("a", "label", "identity"))
        ShareState.starting("a")
        ShareState.failed("a", UserError.GENERIC)

        ShareState.removeConnection("a")

        assertTrue(state().connections.isEmpty())
        assertFalse("a" in state().startingIds)
        assertNull(state().errors["a"])
    }

    @Test
    fun `concurrent mutations do not lose updates`() {
        val threads = 64
        val ready = CountDownLatch(threads)
        val go = CountDownLatch(1)

        (1..threads).map { i ->
            thread {
                ready.countDown()
                go.await()
                ShareState.started("conn-$i", i, "url-$i")
            }
        }.also {
            ready.await()
            go.countDown()
            it.forEach { t -> t.join() }
        }

        assertEquals(threads, state().activeShares.size)
    }
}
