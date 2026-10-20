package to.spora.android

import android.util.Log
import java.time.Instant
import uniffi.spora_ffi.LogLevel
import uniffi.spora_ffi.logLine

/**
 * The app's logger: logcat, plus the core's diagnostic log file
 * (`logLine`), where the app's lines sit between the core's and are stamped
 * with the running connection's record id (none while more than one share
 * or connect runs: which one a line belongs to is not known). Use it instead of
 * [android.util.Log], or the app's side of a problem is missing from bug
 * reports and uploads.
 *
 * Share links are redacted by the core before a line reaches the file;
 * logcat gets the line as written.
 */
object SporaLog {
    private const val TAG = "SporaLog"

    // Enough for any startup; a flood before the file opens keeps its newest
    private const val EARLY_LINES_MAX = 200

    private enum class FileState { PENDING, OPEN, UNAVAILABLE }

    // Until Diagnostics has run initLogging, lines wait in [early]: the store
    // resets in MainActivity.onCreate, or a service's first lines on a cold
    // start, run before the IO thread has loaded the native library (calling
    // into the core here would load it on whichever thread logged first).
    @Volatile
    private var fileState = FileState.PENDING
    private val early = EarlyLines(EARLY_LINES_MAX)

    fun d(tag: String, msg: String, tr: Throwable? = null) {
        if (tr == null) Log.d(tag, msg) else Log.d(tag, msg, tr)
        file(LogLevel.DEBUG, tag, msg, tr)
    }

    fun i(tag: String, msg: String, tr: Throwable? = null) {
        if (tr == null) Log.i(tag, msg) else Log.i(tag, msg, tr)
        file(LogLevel.INFO, tag, msg, tr)
    }

    fun w(tag: String, msg: String, tr: Throwable? = null) {
        if (tr == null) Log.w(tag, msg) else Log.w(tag, msg, tr)
        file(LogLevel.WARN, tag, msg, tr)
    }

    fun e(tag: String, msg: String, tr: Throwable? = null) {
        if (tr == null) Log.e(tag, msg) else Log.e(tag, msg, tr)
        file(LogLevel.ERROR, tag, msg, tr)
    }

    /** initLogging succeeded: the lines that waited go first, in order. */
    internal fun fileOpened() {
        synchronized(early) {
            for (line in early.drain()) write(line.level, line.tag, line.text)
            fileState = FileState.OPEN
        }
    }

    /** initLogging failed: nothing will ever take the waiting lines. */
    internal fun fileUnavailable() {
        synchronized(early) {
            early.drain()
            fileState = FileState.UNAVAILABLE
        }
    }

    private fun file(level: LogLevel, tag: String, msg: String, tr: Throwable?) {
        if (fileState == FileState.PENDING) {
            synchronized(early) {
                if (fileState == FileState.PENDING) {
                    early.add(level, tag, withStackTrace(msg, tr), System.currentTimeMillis())
                    return
                }
            }
        }
        if (fileState == FileState.OPEN) write(level, tag, withStackTrace(msg, tr))
    }

    private fun write(level: LogLevel, tag: String, text: String) {
        // Logging must never be what crashes the app
        try {
            logLine(level, tag, text)
        } catch (_: Throwable) {
        }
    }

    /**
     * Lines logged before the diagnostic log is open, at most [max] (past
     * that the oldest go, counted). The file stamps a line when it is
     * written, so each keeps the time it was logged in its text.
     */
    internal class EarlyLines(private val max: Int) {
        class Line(val level: LogLevel, val tag: String, val text: String)

        private val lines = ArrayDeque<Line>()
        private var dropped = 0

        fun add(level: LogLevel, tag: String, text: String, nowMs: Long) {
            if (lines.size == max) {
                lines.removeFirst()
                dropped++
            }
            lines.addLast(Line(level, tag, "[logged at ${Instant.ofEpochMilli(nowMs)}] $text"))
        }

        /** Everything held, oldest first; empties the buffer. */
        fun drain(): List<Line> {
            val out = ArrayList<Line>(lines.size + 1)
            if (dropped > 0) {
                out += Line(LogLevel.WARN, TAG, "$dropped lines from before the log opened were dropped")
            }
            out += lines
            lines.clear()
            dropped = 0
            return out
        }
    }
}

// Not Log.getStackTraceString: it prints nothing for UnknownHostException,
// exactly the failure a censored network produces.
internal fun withStackTrace(msg: String, tr: Throwable?): String =
    if (tr == null) msg else "$msg\n${tr.stackTraceToString().trimEnd()}"
