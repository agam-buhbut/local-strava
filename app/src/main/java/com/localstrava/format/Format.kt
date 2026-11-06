package com.localstrava.format

import com.localstrava.metrics.Constants
import java.util.Locale

object Format {
    fun distance(meters: Double): String =
        if (meters >= 1000.0) String.format(Locale.US, "%.2f km", meters / 1000.0)
        else String.format(Locale.US, "%d m", meters.toInt())

    fun duration(millis: Long): String {
        val total = (millis / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%d:%02d", m, s)
    }

    fun pace(secPerKm: Double?): String {
        if (secPerKm == null || secPerKm.isNaN() || secPerKm.isInfinite() || secPerKm <= 0.0) return "--"
        val total = secPerKm.toInt()
        return String.format(Locale.US, "%d:%02d /km", total / 60, total % 60)
    }

    fun elevation(meters: Double): String = String.format(Locale.US, "%d m", meters.toInt())

    /** Whole-run average pace, "--" until the run is long enough to have a meaningful pace. */
    fun avgPace(distanceMeters: Double, movingTimeMs: Long): String =
        if (distanceMeters >= Constants.MIN_PACE_DISTANCE_M && movingTimeMs > 0)
            pace(movingTimeMs.toDouble() / distanceMeters) else "--"
}
