package com.localstrava.format

import com.localstrava.metrics.Constants
import com.localstrava.metrics.Goal
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

    fun pace(secPerKm: Double?): String = paceShort(secPerKm).let { if (it == "--") it else "$it /km" }

    /** Pace without the unit, for readouts that print "/km" separately. */
    fun paceShort(secPerKm: Double?): String {
        if (secPerKm == null || secPerKm.isNaN() || secPerKm.isInfinite() || secPerKm <= 0.0) return "--"
        val total = secPerKm.toInt()
        return String.format(Locale.US, "%d:%02d", total / 60, total % 60)
    }

    /** Kilometres as a bare number ("4.82"); big readouts render the unit separately. */
    fun km(meters: Double): String = String.format(Locale.US, "%.2f", meters / 1000.0)

    fun goal(goal: Goal): String = when (goal) {
        is Goal.Distance -> {
            val km = goal.meters / 1000.0
            if (km % 1.0 == 0.0) "${km.toInt()} km" else String.format(Locale.US, "%.1f km", km)
        }
        is Goal.Time -> {
            val totalMin = goal.millis / 60_000
            val h = totalMin / 60
            val m = totalMin % 60
            when {
                h == 0L -> "$m min"
                m == 0L -> "$h h"
                else -> "$h h $m min"
            }
        }
    }

    /** The line under the goal bar: what's left, or that it already rang. */
    fun goalStatus(goal: Goal, distanceMeters: Double, movingTimeMs: Long, reached: Boolean): String {
        val bell = "${goal(goal)} bell"
        if (reached) return "$bell rang"
        val left = when (goal) {
            is Goal.Distance -> distance(goal.meters - distanceMeters)
            is Goal.Time -> {
                val secLeft = ((goal.millis - movingTimeMs) / 1000).coerceAtLeast(0)
                if (secLeft >= 60) "${(secLeft + 59) / 60} min" else "$secLeft s"
            }
        }
        return "$left until the $bell"
    }

    fun elevation(meters: Double): String = String.format(Locale.US, "%d m", meters.toInt())

    /** Whole-run average pace, "--" until the run is long enough to have a meaningful pace. */
    fun avgPace(distanceMeters: Double, movingTimeMs: Long): String =
        if (distanceMeters >= Constants.MIN_PACE_DISTANCE_M && movingTimeMs > 0)
            pace(movingTimeMs.toDouble() / distanceMeters) else "--"
}
