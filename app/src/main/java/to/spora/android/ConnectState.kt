package to.spora.android

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ConnectUiState(
    val isConnected: Boolean = false,
    val isConnecting: Boolean = false,
    val errorMessage: String? = null,
    val savedConnections: List<SavedUseConnection> = emptyList(),
    val activeConnectionId: String? = null,
)

object ConnectState {
    private val _uiState = MutableStateFlow(ConnectUiState())
    val uiState: StateFlow<ConnectUiState> = _uiState.asStateFlow()

    fun loadConnections(connections: List<SavedUseConnection>) {
        _uiState.value = _uiState.value.copy(savedConnections = connections)
    }

    fun addConnection(connection: SavedUseConnection) {
        _uiState.value = _uiState.value.copy(
            savedConnections = _uiState.value.savedConnections + connection,
        )
    }

    fun updateConnection(connection: SavedUseConnection) {
        _uiState.value = _uiState.value.copy(
            savedConnections = _uiState.value.savedConnections.map {
                if (it.id == connection.id) connection else it
            },
        )
    }

    fun removeConnection(id: String) {
        _uiState.value = _uiState.value.copy(
            savedConnections = _uiState.value.savedConnections.filter { it.id != id },
            activeConnectionId = if (_uiState.value.activeConnectionId == id) null else _uiState.value.activeConnectionId,
        )
    }

    fun connecting(connectionId: String? = null) {
        _uiState.value = _uiState.value.copy(
            isConnecting = true,
            isConnected = false,
            errorMessage = null,
            activeConnectionId = connectionId,
        )
    }

    fun connected() {
        _uiState.value = _uiState.value.copy(
            isConnected = true,
            isConnecting = false,
            errorMessage = null,
        )
    }

    fun failed(t: Throwable) {
        _uiState.value = _uiState.value.copy(
            isConnected = false,
            isConnecting = false,
            errorMessage = t.message ?: t.toString(),
            activeConnectionId = null,
        )
    }

    fun disconnected() {
        _uiState.value = _uiState.value.copy(
            isConnected = false,
            isConnecting = false,
            errorMessage = null,
            activeConnectionId = null,
        )
    }
}
