package com.localstrava.tracking

import com.localstrava.metrics.Goal

sealed interface TrackPhase {
    data object Idle : TrackPhase
    data object Active : TrackPhase
    data object Paused : TrackPhase
    data class Finished(val activityId: Long) : TrackPhase
    data object Discarded : TrackPhase
}

data class TrackingUiState(
    val phase: TrackPhase = TrackPhase.Idle,
    val locationLost: Boolean = false,
    val distanceMeters: Double = 0.0,
    val movingTimeMs: Long = 0L,
    val elevationGainMeters: Double = 0.0,
    val currentPaceSecPerKm: Double? = null,
    val avgPaceSecPerKm: Double? = null,
    val goal: Goal? = null,
    val goalReached: Boolean = false,
) {
    val isActive: Boolean get() = phase is TrackPhase.Active || phase is TrackPhase.Paused
}
