package to.spora.android

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

object SharedConnectionStore {
    private const val TAG = "SharedConnectionStore"
    private const val PREFS_NAME = "shared_connections"
    private const val KEY_CONNECTIONS = "connections"

    @Volatile
    private var prefs: SharedPreferences? = null

    // Synchronized: the service's start reads the shares on an IO thread
    // while the activity opens the store on the main thread
    @Synchronized
    fun init(context: Context) {
        if (prefs == null) prefs = SecurePrefs.open(context, PREFS_NAME)
    }

    private fun store(): SharedPreferences =
        checkNotNull(prefs) { "SharedConnectionStore.init has not run" }

    fun getAll(): List<SharedConnection> = try {
        val json = store().getString(KEY_CONNECTIONS, null)
        if (json == null) emptyList() else {
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { i ->
                val obj = array.getJSONObject(i)
                // Entries made before shares were the core's (they carry an
                // "identity" instead) cannot be started any more; drop them.
                val share = obj.optString("share")
                if (share.isEmpty()) return@mapNotNull null
                SharedConnection(
                    id = obj.getString("id"),
                    label = obj.getString("label"),
                    share = share,
                )
            }
        }
    } catch (e: Exception) {
        // The class only: org.json's messages quote the stored JSON, which
        // holds the shares (their secrets and relay tokens)
        SporaLog.e(TAG, "Corrupted connections entry (${e.javaClass.simpleName}); resetting store")
        try { store().edit().remove(KEY_CONNECTIONS).apply() } catch (_: Exception) {}
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
                put("share", c.share)
            })
        }
        store().edit().putString(KEY_CONNECTIONS, array.toString()).apply()
    }
}
