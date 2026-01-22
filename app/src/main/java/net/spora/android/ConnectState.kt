package net.spora.android

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ConnectUiState(
    val isConnected: Boolean = false,
    val isConnecting: Boolean = false,
    val errorMessage: String? = null,
)

object ConnectState {
    private val _uiState = MutableStateFlow(ConnectUiState())
    val uiState: StateFlow<ConnectUiState> = _uiState.asStateFlow()

    fun connecting() {
        _uiState.value = ConnectUiState(isConnecting = true)
    }

    fun connected() {
        _uiState.value = ConnectUiState(isConnected = true)
    }

    fun failed(t: Throwable) {
        _uiState.value = ConnectUiState(
            errorMessage = t.message ?: t.toString(),
        )
    }

    fun disconnected() {
        _uiState.value = ConnectUiState()
    }
}
