package to.spora.android

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

object UseConnectionStore {
    private const val TAG = "UseConnectionStore"
    private const val PREFS_NAME = "use_connections"
    private const val KEY_CONNECTIONS = "connections"

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getAll(): List<SavedUseConnection> = try {
        val json = prefs.getString(KEY_CONNECTIONS, null)
        if (json == null) emptyList() else {
            val array = JSONArray(json)
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                SavedUseConnection(
                    id = obj.getString("id"),
                    label = obj.getString("label"),
                    url = obj.getString("url"),
                )
            }
        }
    } catch (e: Exception) {
        Log.e(TAG, "Corrupted connections entry; resetting store", e)
        try { prefs.edit().remove(KEY_CONNECTIONS).apply() } catch (_: Exception) {}
        emptyList()
    }

    fun save(connection: SavedUseConnection) {
        val all = getAll().toMutableList()
        all.add(connection)
        persist(all)
    }

    fun update(connection: SavedUseConnection) {
        val all = getAll().map { if (it.id == connection.id) connection else it }
        persist(all)
    }

    fun delete(id: String) {
        val all = getAll().filter { it.id != id }
        persist(all)
    }

    private fun persist(connections: List<SavedUseConnection>) {
        val array = JSONArray()
        connections.forEach { c ->
            array.put(JSONObject().apply {
                put("id", c.id)
                put("label", c.label)
                put("url", c.url)
            })
        }
        prefs.edit().putString(KEY_CONNECTIONS, array.toString()).apply()
    }
}
