package com.localstrava.metrics

object Constants {
    const val EARTH_RADIUS_M = 6_371_000.0

    // Accuracy gate (metres)
    const val MAX_ACCURACY_M = 35.0f
    const val MAX_ACCURACY_HARD_M = 40.0f
    const val MAX_ACCURACY_TOLERATE_FIXES = 3

    // Motion / filtering
    const val STATIONARY_SPEED_MPS = 0.5
    const val STATIONARY_WINDOW_MS = 5_000L
    const val MIN_SPEED_WINDOW_MS = 2_000L
    const val MIN_DUP_M = 1.0
    const val MAX_PLAUSIBLE_SPEED_MPS = 12.0
    const val MAX_JUMP_M = 200.0
    const val MAX_TELEPORT_REJECTS = 3
    const val MAX_FIX_GAP_MS = 5_000L

    // Pace
    const val CURRENT_PACE_WINDOW_MS = 20_000L
    const val CURRENT_PACE_MIN_DISTANCE_M = 3.0
    const val MIN_PACE_DISTANCE_M = 10.0

    // Elevation
    const val ELEV_VACC_MAX_M = 6.0f
    const val ELEV_THRESHOLD_M = 1.5
    const val ELEV_SMOOTH_WINDOW = 5

    // Save policy
    const val MIN_SAVE_M = 10.0
    const val MIN_SAVE_POINTS = 2
}
