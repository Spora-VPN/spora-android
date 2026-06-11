package to.spora.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Debug-build-only hook for driving UI state from adb, so any screen state
 * (errors, multiple active shares, connecting, …) can be reached without a
 * relay or peer:
 *
 *   adb shell am broadcast -n to.spora.android/.DebugStateReceiver \
 *     -a to.spora.android.debug.STATE --es cmd connect-failed --es msg "relay unreachable"
 *
 * Seeded connections live only in the in-memory state singletons — nothing is
 * persisted, so a process restart returns to real data. The receiver is
 * declared only in the debug manifest and protected by the DUMP permission,
 * which the adb shell holds but ordinary apps cannot obtain.
 */
class DebugStateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val cmd = intent.getStringExtra("cmd") ?: return
        val id = intent.getStringExtra("id")
        val msg = intent.getStringExtra("msg") ?: "Debug error"
        Log.d(TAG, "cmd=$cmd id=$id")
        when (cmd) {
            "seed" -> {
                val shares = intent.getIntExtra("shares", 0)
                val uses = intent.getIntExtra("uses", 0)
                ShareState.loadConnections((1..shares).map { i ->
                    SharedConnection("debug-share-$i", LABELS[(i - 1) % LABELS.size], DEBUG_IDENTITY)
                })
                ConnectState.loadConnections((1..uses).map { i ->
                    SavedUseConnection("debug-use-$i", LABELS[(i - 1) % LABELS.size], "https://spora.to/s/debug$i")
                })
            }
            "share-starting" -> ShareState.starting(id ?: return)
            "share-started" -> ShareState.started(
                id ?: return,
                handle = DEBUG_HANDLE,
                url = intent.getStringExtra("url") ?: "https://spora.to/s/debugurl",
            )
            "share-failed" -> ShareState.failed(id ?: return, Exception(msg))
            "share-stopped" -> ShareState.stopped(id ?: return)
            "connect-connecting" -> ConnectState.connecting(id)
            "connect-connected" -> {
                ConnectState.connecting(id)
                ConnectState.connected()
            }
            "connect-failed" -> ConnectState.failed(Exception(msg))
            "connect-disconnected" -> ConnectState.disconnected()
            "reset" -> {
                SharedConnectionStore.init(context)
                UseConnectionStore.init(context)
                ShareState.loadConnections(SharedConnectionStore.getAll())
                ConnectState.loadConnections(UseConnectionStore.getAll())
            }
            else -> Log.w(TAG, "Unknown cmd: $cmd")
        }
    }

    private companion object {
        const val TAG = "DebugStateReceiver"
        const val DEBUG_HANDLE = 9000

        // base64("debug") — toggling a seeded share on will feed this to the real
        // share() FFI call and fail, which conveniently exercises the error path.
        const val DEBUG_IDENTITY = "ZGVidWc="

        val LABELS = listOf("Mom's Phone", "Anya", "Office", "Dacha", "Backup")
    }
}
