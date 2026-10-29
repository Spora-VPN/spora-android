package to.spora.android

import java.util.Base64

data class SharedConnection(
    val id: String,
    val label: String,
    /**
     * The share from [uniffi.spora_ffi.makeShare], base64: its secret and
     * relays, and so its URL, which never changes. Made under this device's
     * identity (kept by the core's service); usable with no other.
     */
    val share: String,
) {
    fun shareBytes(): ByteArray = Base64.getDecoder().decode(share)

    // The share holds the link's secret: never in a log
    override fun toString() = "SharedConnection(id=$id, label=$label)"
}
