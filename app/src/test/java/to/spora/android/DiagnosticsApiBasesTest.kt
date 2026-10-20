package to.spora.android

import org.junit.Assert.assertEquals
import org.junit.Test

class DiagnosticsApiBasesTest {

    @Test
    fun `unset is the built-in API`() {
        assertEquals(emptyList<String>(), apiBases(null))
    }

    @Test
    fun `set but blank is passed on for the service to refuse, never the built-in API`() {
        assertEquals(listOf(""), apiBases(""))
        assertEquals(listOf(" "), apiBases(" "))
        assertEquals(listOf(","), apiBases(","))
        assertEquals(listOf(" , "), apiBases(" , "))
    }

    @Test
    fun `a single base is used as it is`() {
        assertEquals(listOf("http://127.0.0.1:9"), apiBases("http://127.0.0.1:9"))
    }

    @Test
    fun `a comma-separated list is split and trimmed, blanks dropped`() {
        assertEquals(
            listOf("http://a.example", "http://b.example"),
            apiBases(" http://a.example , http://b.example "),
        )
        assertEquals(listOf("http://a.example"), apiBases("http://a.example,"))
        assertEquals(
            listOf("http://a.example", "http://b.example"),
            apiBases(",http://a.example,,http://b.example"),
        )
    }
}
