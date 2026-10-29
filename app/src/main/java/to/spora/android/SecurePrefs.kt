package to.spora.android

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

/**
 * The app's encrypted stores (shares, relay settings, the service's state
 * key), all under one Keystore master key.
 *
 * One lock for every store: they are opened from different threads (the
 * activity, the service's start on an IO thread), and two first-launch
 * opens racing could each create the master key, leaving whichever store
 * was made first undecryptable.
 */
internal object SecurePrefs {
    private const val TAG = "SecurePrefs"

    @Synchronized
    fun open(context: Context, name: String): SharedPreferences = try {
        create(context, name)
    } catch (e: Exception) {
        // Undecryptable (restored from a backup without the Keystore master
        // key, or the Keystore was reset) or a corrupted keyset: better to
        // lose what the store held than to crash on every launch.
        SporaLog.e(TAG, "encrypted store $name unreadable; resetting it", e)
        context.deleteSharedPreferences(name)
        create(context, name)
    }

    private fun create(context: Context, name: String): SharedPreferences {
        val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        return EncryptedSharedPreferences.create(
            name,
            masterKeyAlias,
            context.applicationContext,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }
}
