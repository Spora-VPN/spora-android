package to.spora.android

import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.spora_ffi.LogLevel

class SporaLogTest {

    @Test
    fun `a line without an exception is kept as is`() {
        assertEquals("connect failed", withStackTrace("connect failed", null))
    }

    @Test
    fun `the stack trace follows the message, unknown hosts included`() {
        val e = IllegalStateException("tunnel", UnknownHostException("relay.example"))
        val line = withStackTrace("connect failed", e)
        assertTrue(line, line.startsWith("connect failed\njava.lang.IllegalStateException: tunnel\n"))
        assertTrue(line, line.contains("Caused by: java.net.UnknownHostException: relay.example"))
        assertTrue(line, !line.endsWith("\n"))
    }

    @Test
    fun `early lines keep their order and the time they were logged`() {
        val early = SporaLog.EarlyLines(10)
        early.add(LogLevel.ERROR, "Store", "resetting store", 1_760_000_000_123L)
        early.add(LogLevel.DEBUG, "Vpn", "MTU 1280", 1_760_000_001_000L)
        val lines = early.drain()
        assertEquals(listOf("Store", "Vpn"), lines.map { it.tag })
        assertEquals(LogLevel.ERROR, lines[0].level)
        assertEquals("[logged at 2025-10-09T08:53:20.123Z] resetting store", lines[0].text)
        assertTrue(early.drain().isEmpty())
    }

    @Test
    fun `early lines are bounded, the oldest dropped and counted`() {
        val early = SporaLog.EarlyLines(3)
        for (i in 1..5) early.add(LogLevel.INFO, "T", "line $i", 0L)
        val lines = early.drain()
        assertEquals(4, lines.size)
        assertTrue(lines[0].text, lines[0].text.startsWith("2 lines"))
        assertEquals(listOf("line 3", "line 4", "line 5"), lines.drop(1).map { it.text.substringAfter("] ") })
        assertTrue(early.drain().isEmpty())
    }
}
