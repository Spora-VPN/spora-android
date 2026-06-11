package to.spora.android

data class SharedConnection(
    val id: String,
    val label: String,
    /** Base64-encoded identity bytes from [uniffi.spora_ffi.makeIdentity]. */
    val identity: String,
)
