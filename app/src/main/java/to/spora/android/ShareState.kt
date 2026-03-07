package to.spora.android

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ActiveShareInfo(
    val handle: Int,
    val url: String,
)

data class ShareUiState(
    val connections: List<SharedConnection> = emptyList(),
    val activeShares: Map<String, ActiveShareInfo> = emptyMap(),
    val startingIds: Set<String> = emptySet(),
    val errors: Map<String, String> = emptyMap(),
)

object ShareState {
    private val _uiState = MutableStateFlow(ShareUiState())
    val uiState: StateFlow<ShareUiState> = _uiState.asStateFlow()

    fun loadConnections(connections: List<SharedConnection>) {
        _uiState.value = _uiState.value.copy(connections = connections)
    }

    fun addConnection(connection: SharedConnection) {
        _uiState.value = _uiState.value.copy(
            connections = _uiState.value.connections + connection,
        )
    }

    fun starting(connectionId: String) {
        _uiState.value = _uiState.value.copy(
            startingIds = _uiState.value.startingIds + connectionId,
            errors = _uiState.value.errors - connectionId,
        )
    }

    fun started(connectionId: String, handle: Int, url: String) {
        _uiState.value = _uiState.value.copy(
            startingIds = _uiState.value.startingIds - connectionId,
            activeShares = _uiState.value.activeShares + (connectionId to ActiveShareInfo(handle, url)),
        )
    }

    fun failed(connectionId: String, t: Throwable) {
        _uiState.value = _uiState.value.copy(
            startingIds = _uiState.value.startingIds - connectionId,
            errors = _uiState.value.errors + (connectionId to (t.message ?: t.toString())),
        )
    }

    fun stopped(connectionId: String) {
        _uiState.value = _uiState.value.copy(
            activeShares = _uiState.value.activeShares - connectionId,
        )
    }

    fun renameConnection(id: String, newLabel: String) {
        _uiState.value = _uiState.value.copy(
            connections = _uiState.value.connections.map {
                if (it.id == id) it.copy(label = newLabel) else it
            },
        )
    }

    fun removeConnection(id: String) {
        _uiState.value = _uiState.value.copy(
            connections = _uiState.value.connections.filter { it.id != id },
            activeShares = _uiState.value.activeShares - id,
            startingIds = _uiState.value.startingIds - id,
            errors = _uiState.value.errors - id,
        )
    }
}
