package com.localstrava.metrics

/**
 * A pure, framework-free GPS sample. `LocationSource` maps android.location.Location
 * into this so the metrics core never imports Android types (keeps it JVM-unit-testable).
 */
data class Fix(
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double?,
    val timeMs: Long,
    val accuracyMeters: Float?,
    val speedMps: Float?,
    val verticalAccuracyMeters: Float?,
)
