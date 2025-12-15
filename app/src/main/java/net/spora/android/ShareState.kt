package net.spora.android

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ShareUiState(
    val isRunning: Boolean = false,
    val isStarting: Boolean = false,
    val url: String? = null,
    val errorMessage: String? = null,
)

object ShareState {
    private val _uiState = MutableStateFlow(ShareUiState())
    val uiState: StateFlow<ShareUiState> = _uiState.asStateFlow()

    fun starting() {
        _uiState.value = ShareUiState(isStarting = true)
    }

    fun started(url: String) {
        _uiState.value = ShareUiState(isRunning = true, url = url)
    }

    fun failed(t: Throwable) {
        _uiState.value = ShareUiState(
            errorMessage = t.message ?: t.toString(),
        )
    }

    fun stopped() {
        _uiState.value = ShareUiState()
    }
}
