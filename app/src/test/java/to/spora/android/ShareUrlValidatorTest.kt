package to.spora.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import to.spora.android.ui.isValidShareUrl

class ShareUrlValidatorTest {

    @Test
    fun `accepts real share links`() {
        assertTrue(isValidShareUrl("https://spora.to/s/exampleShareToken0123456?r=167.71.66.250:443"))
        assertTrue(isValidShareUrl("https://spora.to/s/token"))
    }

    @Test
    fun `rejects everything else`() {
        assertFalse(isValidShareUrl(""))
        assertFalse(isValidShareUrl("not a url"))
        assertFalse(isValidShareUrl("http://spora.to/s/token"))
        assertFalse(isValidShareUrl("https://spora.to/s/"))
        assertFalse(isValidShareUrl("https://spora.to/other/token"))
        assertFalse(isValidShareUrl("https://evil.example/s/token"))
        assertFalse(isValidShareUrl("https://spora.to.evil.example/s/token"))
    }
}
