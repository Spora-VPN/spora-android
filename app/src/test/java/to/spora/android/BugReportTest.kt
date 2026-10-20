package to.spora.android

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Test

class BugReportTest {

    @Test
    fun `old reports are deleted, recent ones kept`() {
        val dir = Files.createTempDirectory("reports").toFile()
        try {
            val now = 1_000_000_000_000L
            val day = 24 * 60 * 60 * 1000L
            File(dir, "old.zip").apply { writeText("x"); setLastModified(now - day - 1) }
            File(dir, "recent.zip").apply { writeText("x"); setLastModified(now - day + 60_000) }
            pruneReports(dir, now, day)
            assertEquals(listOf("recent.zip"), dir.list()!!.sorted())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a missing directory is nothing to prune`() {
        pruneReports(File("/nonexistent/spora/reports"), System.currentTimeMillis(), 1L)
    }
}
