package net.spora.android

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import org.json.JSONArray
import org.json.JSONObject

object SharedConnectionStore {
    private const val PREFS_NAME = "shared_connections"
    private const val KEY_CONNECTIONS = "connections"

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        prefs = EncryptedSharedPreferences.create(
            PREFS_NAME,
            masterKeyAlias,
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun getAll(): List<SharedConnection> {
        val json = prefs.getString(KEY_CONNECTIONS, null) ?: return emptyList()
        val array = JSONArray(json)
        return (0 until array.length()).map { i ->
            val obj = array.getJSONObject(i)
            SharedConnection(
                id = obj.getString("id"),
                label = obj.getString("label"),
                secretKey = obj.getString("secretKey"),
            )
        }
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
                put("secretKey", c.secretKey)
            })
        }
        prefs.edit().putString(KEY_CONNECTIONS, array.toString()).apply()
    }
}
