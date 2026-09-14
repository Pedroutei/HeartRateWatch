package com.pedro.heartratewatch.wear

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide holder for the latest session data, so the Activity UI and the Tile can both
 * show live numbers without talking to Health Services themselves. [ExerciseSessionService] is
 * the only writer; everything else (MainActivity, HeartRateTileService) only reads.
 */
object HeartRateRepository {

    data class SessionState(
        val isActive: Boolean = false,
        val currentBpm: Int? = null,
        val distanceMeters: Float = 0f,
        val onBreak: Boolean = false,
        val breakSecondsRemaining: Int = 0
    )

    private val _state = MutableStateFlow(SessionState())
    val state: StateFlow<SessionState> = _state.asStateFlow()

    fun update(transform: (SessionState) -> SessionState) {
        _state.value = transform(_state.value)
    }
}
