package to.spora.android

import android.app.Application
import android.os.Process
import kotlin.system.exitProcess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        installCrashCapture()
        // Loads the native library and opens files: not on the main thread
        appScope.launch { Diagnostics.init(this@App) }
        // Not after init: a connect or share from a cold service start should
        // find the network already reported when its record opens. Its lines
        // wait in SporaLog until the log file is open.
        UnderlyingNetworkReporter(this).start()
    }

    // A Kotlin crash is written next to the diagnostic log (the core picks it
    // up for bug reports and uploads), then handed on to the platform's
    // handler, which reports it and ends the process as before.
    private fun installCrashCapture() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            try {
                Diagnostics.captureCrash(thread, e)
            } catch (_: Throwable) {
                // Diagnostics itself could not load (e.g. its class init under
                // OOM); the crash must still end the process below
            } finally {
                if (previous != null) {
                    previous.uncaughtException(thread, e)
                } else {
                    Process.killProcess(Process.myPid())
                    exitProcess(10)
                }
            }
        }
    }

    private companion object {
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
