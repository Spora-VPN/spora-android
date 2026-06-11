package to.spora.android

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import org.json.JSONArray
import org.json.JSONObject

object SharedConnectionStore {
    private const val TAG = "SharedConnectionStore"
    private const val PREFS_NAME = "shared_connections"
    private const val KEY_CONNECTIONS = "connections"

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = try {
            createPrefs(context)
        } catch (e: Exception) {
            // Undecryptable prefs (e.g. restored from a backup without the
            // Keystore master key) or corrupted keyset: better to lose the
            // saved shares than to crash on every launch.
            Log.e(TAG, "Encrypted prefs unreadable; resetting store", e)
            context.deleteSharedPreferences(PREFS_NAME)
            createPrefs(context)
        }
    }

    private fun createPrefs(context: Context): SharedPreferences {
        val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        return EncryptedSharedPreferences.create(
            PREFS_NAME,
            masterKeyAlias,
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun getAll(): List<SharedConnection> = try {
        val json = prefs.getString(KEY_CONNECTIONS, null)
        if (json == null) emptyList() else {
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { i ->
                val obj = array.getJSONObject(i)
                // Entries from before the identity-based protocol only carry a
                // "secretKey", which the relay no longer understands; drop them.
                val identity = obj.optString("identity")
                if (identity.isEmpty()) return@mapNotNull null
                SharedConnection(
                    id = obj.getString("id"),
                    label = obj.getString("label"),
                    identity = identity,
                )
            }
        }
    } catch (e: Exception) {
        Log.e(TAG, "Corrupted connections entry; resetting store", e)
        try { prefs.edit().remove(KEY_CONNECTIONS).apply() } catch (_: Exception) {}
        emptyList()
    }

    fun save(connection: SharedConnection) {
        val all = getAll().toMutableList()
        all.add(connection)
        persist(all)
    }

    fun update(connection: SharedConnection) {
        val all = getAll().map { if (it.id == connection.id) connection else it }
        persist(all)
    }

    fun delete(id: String) {
        val all = getAll().filter { it.id != id }
        persist(all)
    }

    private fun persist(connections: List<SharedConnection>) {
        val array = JSONArray()
        connections.forEach { c ->
            array.put(JSONObject().apply {
                put("id", c.id)
                put("label", c.label)
                put("identity", c.identity)
            })
        }
        prefs.edit().putString(KEY_CONNECTIONS, array.toString()).apply()
    }
}
