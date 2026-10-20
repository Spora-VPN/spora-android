package to.spora.android

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import uniffi.spora_ffi.exportBugReport

/**
 * Bug reports: the core bundles this device's recent connection records,
 * diagnostic log and crash files into a zip, and the user sends it through
 * whatever app they choose. No Spora server is involved, so reporting works
 * exactly when spora.to is unreachable and with uploads off.
 */
object BugReport {
    private const val TAG = "BugReport"

    // cacheDir/reports, the only place the FileProvider below serves
    private const val DIR = "reports"
    private const val AUTHORITY_SUFFIX = ".reports"

    // A share target may read the attachment well after the chooser closes
    // (an outbox that sends later), so a report outlives its own send by a day
    private const val MAX_AGE_MS = 24 * 60 * 60 * 1000L

    /**
     * Writes a fresh report under cacheDir/reports and returns it, or null
     * when it could not be made. Blocking file work, seconds at worst: call
     * it off the main thread.
     */
    fun export(context: Context, note: String?): File? {
        val dir = File(context.cacheDir, DIR)
        pruneReports(dir, System.currentTimeMillis(), MAX_AGE_MS)
        val stamp = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date())
        val out = File(dir, "spora-report-$stamp.zip")
        return try {
            val summary = exportBugReport(out.path, note, platformInfo())
            SporaLog.i(
                TAG,
                "report ${out.name}: ${summary.includedFiles} files, " +
                    "${summary.omittedFiles} left out, ${summary.bytes} bytes",
            )
            out
        } catch (t: Throwable) {
            SporaLog.e(TAG, "cannot write a report", t)
            null
        }
    }

    /**
     * Opens the share sheet with a message to support, [report] attached when
     * there is one. Mail is the intent, but any app may take it (a messenger
     * when mail is blocked). Returns whether the report is attached.
     */
    fun send(context: Context, subject: String, message: String, report: File?): Boolean {
        val uri = report?.let {
            try {
                FileProvider.getUriForFile(context, context.packageName + AUTHORITY_SUFFIX, it)
            } catch (t: Throwable) {
                SporaLog.e(TAG, "cannot share ${it.name}", t)
                null
            }
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_EMAIL, arrayOf(context.getString(R.string.support_email)))
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, message)
            if (uri != null) {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                // The chooser passes the grant on to the target through the clip
                clipData = ClipData.newRawUri(null, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } else {
                type = "text/plain"
            }
        }
        try {
            context.startActivity(
                Intent.createChooser(send, context.getString(R.string.feedback_chooser_title)),
            )
        } catch (e: ActivityNotFoundException) {
            SporaLog.w(TAG, "no app to send the report with", e)
        }
        return uri != null
    }

    private fun platformInfo(): Map<String, String> = mapOf(
        "manufacturer" to Build.MANUFACTURER,
        "model" to Build.MODEL,
        "android" to Build.VERSION.RELEASE,
        "sdk" to Build.VERSION.SDK_INT.toString(),
        "app_version" to BuildConfig.VERSION_NAME,
        "app_version_code" to BuildConfig.VERSION_CODE.toString(),
    )
}

/** Deletes reports in [dir] last modified more than [maxAgeMs] before [now]. */
internal fun pruneReports(dir: File, now: Long, maxAgeMs: Long) {
    dir.listFiles()?.forEach { f ->
        if (f.isFile && now - f.lastModified() > maxAgeMs) f.delete()
    }
}
