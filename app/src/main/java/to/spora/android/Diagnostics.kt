package to.spora.android

import android.content.Context
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.spora_ffi.ServiceConfig
import uniffi.spora_ffi.TelemetryException
import uniffi.spora_ffi.TelemetryStatus
import uniffi.spora_ffi.acknowledgeTelemetryNotice
import uniffi.spora_ffi.initLogging
import uniffi.spora_ffi.initService
import uniffi.spora_ffi.setTelemetryEnabled
import uniffi.spora_ffi.telemetryNudge
import uniffi.spora_ffi.telemetryStatus
import uniffi.spora_ffi.writeCrash

/**
 * The app's side of the core's diagnostics service: the diagnostic log and
 * crash files, the connection records, and the upload switch.
 *
 * Collection is always on and stays on the device; the switch decides only
 * whether what is already there is uploaded (default off). Everything lives
 * under noBackupFilesDir: a restored device token would clone a device, and
 * logs and records describe the device they were made on.
 */
object Diagnostics {
    private const val TAG = "Diagnostics"

    // A crash during startup waits this long for the log directory
    private const val CRASH_INIT_WAIT_MS = 2_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val initDone = CountDownLatch(1)

    @Volatile
    private var serviceUp = false

    private val _status = MutableStateFlow<TelemetryStatus?>(null)

    /** The switch as last read; null until the service is up (or if it could not start). */
    val status: StateFlow<TelemetryStatus?> = _status.asStateFlow()

    private val _unavailable = MutableStateFlow(false)

    /**
     * The service failed to start (it is retried each time the app comes to
     * the foreground); false while it is still starting, or up.
     */
    val unavailable: StateFlow<Boolean> = _unavailable.asStateFlow()

    fun logDir(context: Context) = File(context.noBackupFilesDir, "diag")

    /** The connection-record directory, for every `connect` and `share`. */
    fun recordDir(context: Context): String = File(context.noBackupFilesDir, "records").path

    private fun stateDir(context: Context) = File(context.noBackupFilesDir, "service")

    /**
     * Start the log file and the service (which uploads on its own thread,
     * and only while the switch is on). Loads the native library and touches
     * files: call once, off the main thread.
     */
    fun init(context: Context) {
        try {
            try {
                initLogging(logDir(context).path)
                SporaLog.fileOpened()
            } catch (t: Throwable) {
                SporaLog.fileUnavailable()
                SporaLog.e(TAG, "diagnostic log unavailable", t)
            }
            startService(context.applicationContext)
        } finally {
            initDone.countDown()
        }
    }

    // Serialized: a retry from onForeground must not overlap another attempt
    @Synchronized
    private fun startService(context: Context) {
        if (serviceUp) return
        try {
            initService(
                ServiceConfig(
                    stateDir = stateDir(context).path,
                    recordDir = recordDir(context),
                    logDir = logDir(context).path,
                    platform = "android",
                    // "-debug" on debug builds (versionNameSuffix), so the
                    // server can tell them from installs
                    appVersion = BuildConfig.VERSION_NAME,
                    // Debug builds only (-PsporaApi / SPORA_API); null is the
                    // built-in production API
                    apiBases = apiBases(BuildConfig.SPORA_API),
                ),
            )
            serviceUp = true
            _unavailable.value = false
            val status = telemetryStatus()
            _status.value = status
            SporaLog.i(
                TAG,
                "app ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) started; " +
                    "uploads ${if (status.uploadEnabled) "on" else "off"}" +
                    if (status.required) ", required by the account" else "",
            )
        } catch (t: Throwable) {
            _unavailable.value = true
            SporaLog.e(TAG, "diagnostics service unavailable", t)
        }
    }

    /**
     * For the uncaught-exception handler: synchronous, so the file is on disk
     * before the process dies. Never throws.
     */
    fun captureCrash(thread: Thread, e: Throwable) {
        try {
            initDone.await(CRASH_INIT_WAIT_MS, TimeUnit.MILLISECONDS)
            writeCrash(
                "kotlin",
                "Uncaught exception in thread \"${thread.name}\"\n${e.stackTraceToString()}",
            )
        } catch (_: Throwable) {
        }
    }

    /** Re-read the switch (this process's copy: no file I/O). */
    suspend fun refresh() {
        withContext(Dispatchers.IO) {
            if (serviceUp) _status.value = telemetryStatus()
        }
    }

    /**
     * The app came to the foreground: a chance to upload, to notice a changed
     * flag, and to retry a service that failed to start (an I/O error, say)
     * rather than leave the switch dead until the process ends.
     */
    fun onForeground(context: Context) {
        val app = context.applicationContext
        scope.launch {
            if (!serviceUp && initDone.count == 0L) startService(app)
            if (!serviceUp) return@launch
            telemetryNudge()
            refresh()
        }
    }

    enum class SwitchResult { OK, REQUIRED, FAILED }

    /**
     * Flip the upload switch. [SwitchResult.REQUIRED]: the account requires
     * uploads, nothing changed, and the UI owes the user the force notice.
     */
    suspend fun setUploadEnabled(enabled: Boolean): SwitchResult = withContext(Dispatchers.IO) {
        try {
            setTelemetryEnabled(enabled)
            SporaLog.i(TAG, "uploads switched ${if (enabled) "on" else "off"} by the user")
            SwitchResult.OK
        } catch (_: TelemetryException.Required) {
            SwitchResult.REQUIRED
        } catch (t: Throwable) {
            SporaLog.e(TAG, "cannot switch uploads ${if (enabled) "on" else "off"}", t)
            SwitchResult.FAILED
        } finally {
            refresh()
        }
    }

    /** The user has seen the force notice. */
    fun acknowledgeNotice() {
        scope.launch {
            if (_status.value?.requiredNoticePending != true) return@launch
            acknowledgeTelemetryNotice()
            refresh()
        }
    }
}

/**
 * The service's API bases from a debug build's `-PsporaApi` / `SPORA_API`,
 * read as the CLI reads `SPORA_API`: comma-separated, each trimmed, blanks
 * dropped. Unset (null) is the empty list, the built-in production API. A
 * value that is set but names no address (blank, a stray comma) is passed on
 * as it is, for the service to refuse as not a URL: a dev setup gone wrong
 * must not quietly mean production.
 */
internal fun apiBases(raw: String?): List<String> {
    if (raw == null) return emptyList()
    val bases = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    return bases.ifEmpty { listOf(raw) }
}
