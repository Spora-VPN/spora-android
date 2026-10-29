package to.spora.android

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import uniffi.spora_ffi.RelayAccessStatus
import uniffi.spora_ffi.RelayCredential
import uniffi.spora_ffi.RelayEntry
import uniffi.spora_ffi.refreshRelays
import uniffi.spora_ffi.relayAccessStatus

/**
 * A relay the user runs (or trusts), as they typed it. [endpoint] is in the
 * share URL's `?r=` form; [token] is what the relay wants, as pasted
 * (trimmed), or null for an open relay. The token goes into the share, never
 * into its URL.
 */
data class OwnRelay(
    val endpoint: String,
    val token: String? = null,
) {
    fun toEntry(): RelayEntry = RelayEntry(
        endpoint = endpoint,
        credential = token?.let { RelayCredential.Static(it.toByteArray(Charsets.UTF_8)) }
            ?: RelayCredential.None,
    )

    // The token is a secret: never in a log
    override fun toString() = "OwnRelay($endpoint, token=${if (token != null) "set" else "none"})"
}

/**
 * The relays new shares are made with: Spora's (on by default) and the
 * user's own, independently. A share keeps the relays it was made with (they
 * are part of its URL), so these apply to new shares only.
 */
data class RelaySettingsState(
    val useSpora: Boolean = true,
    val own: List<OwnRelay> = emptyList(),
) {
    /** A share needs at least one relay. */
    val hasRelays: Boolean get() = useSpora || own.isNotEmpty()
}

object RelaySettings {
    private const val TAG = "RelaySettings"
    private const val PREFS_NAME = "relay_settings"
    private const val KEY_USE_SPORA = "use_spora"
    private const val KEY_OWN = "own_relays"

    @Volatile
    private var prefs: SharedPreferences? = null

    private val _state = MutableStateFlow(RelaySettingsState())
    val state: StateFlow<RelaySettingsState> = _state.asStateFlow()

    /** Load the settings (once; the encrypted store is opened here). */
    @Synchronized
    fun init(context: Context) {
        if (prefs != null) return
        val p = SecurePrefs.open(context, PREFS_NAME)
        prefs = p
        _state.value = RelaySettingsState(
            useSpora = p.getBoolean(KEY_USE_SPORA, true),
            own = readOwn(p),
        )
    }

    fun setUseSpora(use: Boolean) {
        _state.update { it.copy(useSpora = use) }
        prefs?.edit()?.putBoolean(KEY_USE_SPORA, use)?.apply()
        SporaLog.i(TAG, "Spora's relays ${if (use) "on" else "off"} for new shares")
    }

    /** Add [relay], or replace the one at [index]. */
    fun put(relay: OwnRelay, index: Int? = null) {
        _state.update { s ->
            val own = s.own.toMutableList()
            if (index != null && index in own.indices) own[index] = relay else own.add(relay)
            s.copy(own = own)
        }
        persistOwn()
    }

    fun remove(index: Int) {
        _state.update { s -> s.copy(own = s.own.filterIndexed { i, _ -> i != index }) }
        persistOwn()
    }

    private fun persistOwn() {
        val array = JSONArray()
        _state.value.own.forEach { r ->
            array.put(JSONObject().apply {
                put("endpoint", r.endpoint)
                r.token?.let { put("token", it) }
            })
        }
        prefs?.edit()?.putString(KEY_OWN, array.toString())?.apply()
    }

    private fun readOwn(p: SharedPreferences): List<OwnRelay> = try {
        val array = JSONArray(p.getString(KEY_OWN, null) ?: "[]")
        (0 until array.length()).map { i ->
            val obj = array.getJSONObject(i)
            OwnRelay(
                endpoint = obj.getString("endpoint"),
                token = if (obj.has("token")) obj.getString("token") else null,
            )
        }
    } catch (e: Exception) {
        // The class only: org.json's messages quote the JSON, tokens and all
        SporaLog.e(TAG, "unreadable relay list (${e.javaClass.simpleName}); starting empty")
        emptyList()
    }
}

/**
 * The device's access to Spora's relays, as the core's service keeps it
 * (the relays the API advises for new shares, and the relay token running
 * shares present, renewed in the background).
 */
object RelayAccess {
    private const val TAG = "RelayAccess"

    private val _status = MutableStateFlow<RelayAccessStatus?>(null)

    /** As last read; null until the service is up. */
    val status: StateFlow<RelayAccessStatus?> = _status.asStateFlow()

    private val _fetchFailed = MutableStateFlow(false)

    /**
     * The last [fetch] failed. The status says so too, except where the
     * device has no registration with the API in use to keep it under (a
     * debug build switched to another API whose registration failed).
     */
    val fetchFailed: StateFlow<Boolean> = _fetchFailed.asStateFlow()

    /** Re-read the status (this process's copy of the state: no I/O). */
    suspend fun refresh() = withContext(Dispatchers.IO) {
        if (!Diagnostics.serviceReady) return@withContext
        try {
            _status.value = relayAccessStatus()
        } catch (t: Throwable) {
            SporaLog.w(TAG, "relay access status unavailable", t)
        }
    }

    /**
     * Ask the API for relays and a relay token now (network): registers the
     * device if it is not yet. False when the service is down or the API
     * could not be reached; the status says more.
     */
    suspend fun fetch(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (!Diagnostics.awaitService(context)) {
            _fetchFailed.value = true
            return@withContext false
        }
        try {
            _status.value = refreshRelays()
            _fetchFailed.value = false
            true
        } catch (t: Throwable) {
            SporaLog.w(TAG, "cannot fetch Spora's relays", t)
            _fetchFailed.value = true
            refresh()
            false
        }
    }
}

/** Why a relay address cannot be used. */
enum class RelayEndpointError { FORMAT, PROTOCOL, IPV6_BRACKETS, PORT, DUPLICATE }

/**
 * Normalize a relay address as typed: trimmed, a pasted `?r=` value's
 * percent-encoding undone. The result is what is stored and checked.
 */
fun normalizeRelayEndpoint(raw: String): String {
    val trimmed = raw.trim()
    return if ('%' in trimmed) {
        runCatching { java.net.URLDecoder.decode(trimmed, "UTF-8") }.getOrDefault(trimmed)
    } else trimmed
}

/**
 * Check [endpoint] (already normalized) the way spora-core's
 * `RelayEndpoint::from_url_param` reads it: an optional protocol tag
 * (`quic/`, `udpquic/`, `nz/`, `tcptls/`, `direct/`), then `host:port`, an
 * IPv6 literal bracketed. Null when it is usable.
 */
fun relayEndpointError(endpoint: String): RelayEndpointError? {
    if (endpoint.isEmpty() || endpoint.any { it.isWhitespace() }) return RelayEndpointError.FORMAT
    val slash = endpoint.indexOf('/')
    val address = if (slash >= 0) {
        if (endpoint.substring(0, slash) !in RELAY_PROTOCOL_TAGS) return RelayEndpointError.PROTOCOL
        endpoint.substring(slash + 1)
    } else endpoint
    val host: String
    val port: String
    if (address.startsWith('[')) {
        val close = address.indexOf("]:")
        if (close < 0) return RelayEndpointError.FORMAT
        host = address.substring(1, close)
        port = address.substring(close + 2)
        if (!isIpv6Literal(host)) return RelayEndpointError.FORMAT
    } else {
        val colon = address.lastIndexOf(':')
        if (colon < 0) return RelayEndpointError.FORMAT
        host = address.substring(0, colon)
        port = address.substring(colon + 1)
        if (':' in host) return RelayEndpointError.IPV6_BRACKETS
        if (host.isEmpty() || '/' in host) return RelayEndpointError.FORMAT
    }
    // ASCII digits only: toInt reads other scripts' digits too
    if (port.isEmpty() || port.length > 5 || !port.all { it in '0'..'9' } ||
        port.toInt() !in 1..65535
    ) {
        return RelayEndpointError.PORT
    }
    return null
}

private val RELAY_PROTOCOL_TAGS = setOf("quic", "udpquic", "direct", "tcptls", "nz")

// By hand rather than through InetAddress, which looks up anything that is
// not a literal (DNS, here on the main thread)
internal fun isIpv6Literal(s: String): Boolean {
    val halves = s.split("::")
    if (halves.size > 2) return false
    // Groups in one half; an IPv4 tail counts two and only ends the address
    fun groups(part: String, last: Boolean): Int? {
        if (part.isEmpty()) return 0
        val gs = part.split(':')
        var n = 0
        for ((i, g) in gs.withIndex()) {
            if ('.' in g) {
                if (!last || i != gs.lastIndex || !isIpv4Literal(g)) return null
                n += 2
            } else {
                if (g.length !in 1..4 || !g.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }) {
                    return null
                }
                n += 1
            }
        }
        return n
    }
    if (halves.size == 1) return groups(halves[0], last = true) == 8
    val head = groups(halves[0], last = false) ?: return false
    val tail = groups(halves[1], last = true) ?: return false
    return head + tail <= 7
}

private fun isIpv4Literal(s: String): Boolean {
    val octets = s.split('.')
    return octets.size == 4 && octets.all { o ->
        o.length in 1..3 && o.all { it in '0'..'9' } && (o == "0" || !o.startsWith('0')) &&
            o.toInt() <= 255
    }
}
