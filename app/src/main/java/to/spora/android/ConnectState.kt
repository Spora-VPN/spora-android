package to.spora.android

import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class ConnectUiState(
    val isConnected: Boolean = false,
    val isConnecting: Boolean = false,
    val error: UserError? = null,
    val errorConnectionId: String? = null,
    val savedConnections: List<SavedUseConnection> = emptyList(),
    val activeConnectionId: String? = null,
)

// All mutations go through MutableStateFlow.update so concurrent writers
// (service IO coroutines vs. the main thread) can't lose each other's changes.
object ConnectState {
    private val _uiState = MutableStateFlow(ConnectUiState())
    val uiState: StateFlow<ConnectUiState> = _uiState.asStateFlow()

    fun loadConnections(connections: List<SavedUseConnection>) {
        _uiState.update { it.copy(savedConnections = connections) }
    }

    fun addConnection(connection: SavedUseConnection) {
        _uiState.update { it.copy(savedConnections = it.savedConnections + connection) }
    }

    fun updateConnection(connection: SavedUseConnection) {
        _uiState.update { state ->
            state.copy(
                savedConnections = state.savedConnections.map {
                    if (it.id == connection.id) connection else it
                },
            )
        }
    }

    fun removeConnection(id: String) {
        _uiState.update { state ->
            state.copy(
                savedConnections = state.savedConnections.filter { it.id != id },
                activeConnectionId = if (state.activeConnectionId == id) null else state.activeConnectionId,
                errorConnectionId = if (state.errorConnectionId == id) null else state.errorConnectionId,
            )
        }
    }

    fun connecting(connectionId: String? = null) {
        _uiState.update {
            it.copy(
                isConnecting = true,
                isConnected = false,
                error = null,
                errorConnectionId = null,
                activeConnectionId = connectionId,
            )
        }
    }

    fun connected() {
        _uiState.update {
            it.copy(
                isConnected = true,
                isConnecting = false,
                error = null,
                errorConnectionId = null,
            )
        }
    }

    /** Attributes the error to whatever connection was active when it failed. */
    fun failed(error: UserError) {
        _uiState.update {
            it.copy(
                isConnected = false,
                isConnecting = false,
                error = error,
                errorConnectionId = it.activeConnectionId,
                activeConnectionId = null,
            )
        }
    }

    fun failed(error: UserError, connectionId: String?) {
        _uiState.update {
            it.copy(
                isConnected = false,
                isConnecting = false,
                error = error,
                errorConnectionId = connectionId,
                activeConnectionId = null,
            )
        }
    }

    /**
     * Service-initiated teardown (onDestroy/onRevoke): drop the live-connection
     * flags but keep any error so the user can still see why it ended.
     */
    fun serviceStopped() {
        _uiState.update {
            it.copy(
                isConnected = false,
                isConnecting = false,
                activeConnectionId = null,
            )
        }
    }

    fun disconnected() {
        _uiState.update {
            it.copy(
                isConnected = false,
                isConnecting = false,
                error = null,
                errorConnectionId = null,
                activeConnectionId = null,
            )
        }
    }

    @VisibleForTesting
    internal fun reset() {
        _uiState.value = ConnectUiState()
    }
}
