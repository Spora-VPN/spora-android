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
    VPN_PERMISSION_DENIED(R.string.error_vpn_permission),
    VPN_UNAVAILABLE(R.string.error_vpn_unavailable),
    GENERIC(R.string.error_generic),
}

fun Throwable.toUserError(): UserError = when (this) {
    is ConnectException.InvalidUrl -> UserError.INVALID_URL
    is ConnectException -> UserError.CONNECT_FAILED
    is ShareException -> UserError.SHARE_FAILED
    // ConnectVpnService throws this when Builder.establish() returns null
    is IllegalStateException -> UserError.VPN_UNAVAILABLE
    else -> UserError.GENERIC
}
