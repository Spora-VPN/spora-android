package to.spora.android

import androidx.annotation.StringRes
import uniffi.spora_ffi.ConnectException
import uniffi.spora_ffi.ShareException

/**
 * User-presentable error categories. Raw exception text from the FFI layer is
 * English debug output ("v1=…"), so services log the exception and store one
 * of these instead; the UI resolves it to a localized string at render time.
 */
enum class UserError(@StringRes val messageRes: Int) {
    INVALID_URL(R.string.error_invalid_url),
    CONNECT_FAILED(R.string.error_connect_failed),
    SHARE_FAILED(R.string.error_share_failed),
    /** The service that holds the device identity is not up. */
    SHARE_UNAVAILABLE(R.string.error_share_unavailable),
    /**
     * The share was made under a device identity this device no longer has
     * (its service state was lost): only a new share (new link) helps.
     */
    SHARE_LINK_LOST(R.string.error_share_link_lost),
    /** Spora's relays off and none of the user's own: no share can be made. */
    NO_RELAYS(R.string.error_no_relays),
    /** Spora's relays asked for, and the API could not be reached. */
    RELAYS_UNREACHABLE(R.string.error_relays_unreachable),
    /** The API answered with no relays. */
    NO_SPORA_RELAYS(R.string.error_no_spora_relays),
    VPN_PERMISSION_DENIED(R.string.error_vpn_permission),
    VPN_UNAVAILABLE(R.string.error_vpn_unavailable),
    GENERIC(R.string.error_generic),
}

fun Throwable.toUserError(): UserError = when (this) {
    is ConnectException.InvalidUrl -> UserError.INVALID_URL
    is ConnectException -> UserError.CONNECT_FAILED
    is ShareException.IdentityMismatch -> UserError.SHARE_LINK_LOST
    is ShareException.NotInitialized -> UserError.SHARE_UNAVAILABLE
    is ShareException.NoRelayAdvice -> UserError.NO_SPORA_RELAYS
    is ShareException -> UserError.SHARE_FAILED
    // ConnectVpnService throws this when Builder.establish() returns null
    is IllegalStateException -> UserError.VPN_UNAVAILABLE
    else -> UserError.GENERIC
}
