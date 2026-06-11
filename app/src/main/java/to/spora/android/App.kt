package to.spora.android

import android.app.Application
import com.bugfender.sdk.Bugfender

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // App's own BuildConfig: a previous import of the Bugfender library's
        // BuildConfig made this flag describe the library, not the app
        Bugfender.init(this, "6nejmgelWn80JkqD1q1Mg4rLo377Mnti", BuildConfig.DEBUG, true)
        Bugfender.enableCrashReporting()
        if (BuildConfig.DEBUG) {
            // Full logcat and UI-event upload is too invasive for a privacy-
            // sensitive VPN app in release; crash reports only there
            Bugfender.enableUIEventLogging(this)
            Bugfender.enableLogcatLogging()
        }
    }
}
