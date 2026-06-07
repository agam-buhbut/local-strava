package com.localstrava.tracking

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Process singleton bridging the foreground service (the SOLE writer) and the UI (readers).
 * The terminal result is RETAINED in the phase (Finished/Discarded), not a transient event,
 * so the UI cannot miss it if its screen was recreated. UI calls [consumeTerminal] once handled.
 */
object TrackingStateHolder {
    private val _state = MutableStateFlow(TrackingUiState())
    val state: StateFlow<TrackingUiState> = _state.asStateFlow()

    fun set(newState: TrackingUiState) { _state.value = newState }
    fun update(block: (TrackingUiState) -> TrackingUiState) { _state.update(block) }

    fun consumeTerminal() {
        _state.update {
            if (it.phase is TrackPhase.Finished || it.phase is TrackPhase.Discarded) TrackingUiState()
            else it
        }
    }
}
