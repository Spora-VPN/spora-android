package to.spora.android

import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class ActiveShareInfo(
    val handle: Int,
    val url: String,
)

data class ShareUiState(
    val connections: List<SharedConnection> = emptyList(),
    val activeShares: Map<String, ActiveShareInfo> = emptyMap(),
    val startingIds: Set<String> = emptySet(),
    val errors: Map<String, UserError> = emptyMap(),
)

// All mutations go through MutableStateFlow.update so concurrent writers
// (service IO coroutines vs. the main thread) can't lose each other's changes.
object ShareState {
    private val _uiState = MutableStateFlow(ShareUiState())
    val uiState: StateFlow<ShareUiState> = _uiState.asStateFlow()

    fun loadConnections(connections: List<SharedConnection>) {
        _uiState.update { it.copy(connections = connections) }
    }

    fun addConnection(connection: SharedConnection) {
        _uiState.update { it.copy(connections = it.connections + connection) }
    }

    fun starting(connectionId: String) {
        _uiState.update {
            it.copy(
                startingIds = it.startingIds + connectionId,
                errors = it.errors - connectionId,
            )
        }
    }

    fun started(connectionId: String, handle: Int, url: String) {
        _uiState.update {
            it.copy(
                startingIds = it.startingIds - connectionId,
                activeShares = it.activeShares + (connectionId to ActiveShareInfo(handle, url)),
            )
        }
    }

    fun failed(connectionId: String, error: UserError) {
        _uiState.update {
            it.copy(
                startingIds = it.startingIds - connectionId,
                errors = it.errors + (connectionId to error),
            )
        }
    }

    fun stopped(connectionId: String) {
        _uiState.update {
            it.copy(
                activeShares = it.activeShares - connectionId,
                startingIds = it.startingIds - connectionId,
            )
        }
    }

    /**
     * Service-initiated teardown (onDestroy): every share died with the
     * service, but keep errors visible.
     */
    fun serviceStopped() {
        _uiState.update {
            it.copy(
                activeShares = emptyMap(),
                startingIds = emptySet(),
            )
        }
    }

    fun renameConnection(id: String, newLabel: String) {
        _uiState.update { state ->
            state.copy(
                connections = state.connections.map {
                    if (it.id == id) it.copy(label = newLabel) else it
                },
            )
        }
    }

    fun removeConnection(id: String) {
        _uiState.update { state ->
            state.copy(
                connections = state.connections.filter { it.id != id },
                activeShares = state.activeShares - id,
                startingIds = state.startingIds - id,
                errors = state.errors - id,
            )
        }
    }

    @VisibleForTesting
    internal fun reset() {
        _uiState.value = ShareUiState()
    }
}
