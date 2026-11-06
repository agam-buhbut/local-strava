package com.localstrava.metrics

/**
 * One optional per-run goal that rings the bell once when reached (spec §4.2). Time goals compare
 * against MOVING time — the number on screen — so pauses and auto-pause don't count toward them.
 */
sealed interface Goal {
    data class Distance(val meters: Double) : Goal
    data class Time(val millis: Long) : Goal
}

fun Goal.isReached(distanceMeters: Double, movingTimeMs: Long): Boolean = when (this) {
    is Goal.Distance -> distanceMeters >= meters
    is Goal.Time -> movingTimeMs >= millis
}

fun Goal.progress(distanceMeters: Double, movingTimeMs: Long): Float = when (this) {
    is Goal.Distance -> (distanceMeters / meters).toFloat()
    is Goal.Time -> movingTimeMs.toFloat() / millis
}.coerceIn(0f, 1f)

fun Goal.Distance.stepped(direction: Int): Goal.Distance =
    Goal.Distance((meters + direction * DISTANCE_STEP_M).coerceIn(MIN_DISTANCE_M, MAX_DISTANCE_M))

fun Goal.Time.stepped(direction: Int): Goal.Time =
    Goal.Time((millis + direction * TIME_STEP_MS).coerceIn(MIN_TIME_MS, MAX_TIME_MS))

private const val DISTANCE_STEP_M = 500.0
private const val MIN_DISTANCE_M = 500.0
private const val MAX_DISTANCE_M = 100_000.0
private const val TIME_STEP_MS = 5 * 60_000L
private const val MIN_TIME_MS = 5 * 60_000L
private const val MAX_TIME_MS = 600 * 60_000L
