package to.spora.android
import android.app.Application
import com.bugfender.android.BuildConfig
import com.bugfender.sdk.Bugfender

class App : Application() {
    override fun onCreate() {
        super.onCreate();
        Bugfender.init(this, "6nejmgelWn80JkqD1q1Mg4rLo377Mnti", BuildConfig.DEBUG, true)
        Bugfender.enableCrashReporting()
        Bugfender.enableUIEventLogging(this)
        Bugfender.enableLogcatLogging() // optional, if you want logs automatically collected from logcat
    }
}