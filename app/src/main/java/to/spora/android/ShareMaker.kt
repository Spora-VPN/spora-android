package to.spora.android

import android.content.Context
import java.util.Base64
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.spora_ffi.ShareException
import uniffi.spora_ffi.makeShare
import uniffi.spora_ffi.relayAccessStatus

/**
 * Makes new shares (each its own secret and link) on the relays the settings
 * name: Spora's first, when on, then the user's own.
 */
object ShareMaker {
    private const val TAG = "ShareMaker"

    sealed interface Result {
        /** The new share, base64 (see [SharedConnection.share]). */
        data class Made(val share: String) : Result
        data class Failed(val error: UserError) : Result
    }

    /**
     * A new share. Network only when Spora's relays are wanted and none have
     * been fetched yet (a fresh install, or the API was never reachable);
     * otherwise offline, on the relays last fetched.
     */
    suspend fun make(context: Context): Result = withContext(Dispatchers.IO) {
        RelaySettings.init(context)
        val settings = RelaySettings.state.value
        if (!settings.hasRelays) return@withContext Result.Failed(UserError.NO_RELAYS)
        if (!Diagnostics.awaitService(context)) {
            return@withContext Result.Failed(UserError.SHARE_UNAVAILABLE)
        }
        try {
            if (settings.useSpora && relayAccessStatus().advisedRelays.isEmpty() &&
                !RelayAccess.fetch(context)
            ) {
                return@withContext Result.Failed(UserError.RELAYS_UNREACHABLE)
            }
            val blob = makeShare(settings.own.map { it.toEntry() }, settings.useSpora)
            SporaLog.i(
                TAG,
                "new share: ${if (settings.useSpora) "Spora's relays + " else ""}" +
                    "${settings.own.size} own",
            )
            Result.Made(Base64.getEncoder().encodeToString(blob))
        } catch (e: ShareException) {
            SporaLog.e(TAG, "cannot make a share", e)
            Result.Failed(e.toUserError())
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            SporaLog.e(TAG, "cannot make a share", t)
            Result.Failed(UserError.SHARE_FAILED)
        }
    }
}
