package com.localstrava.metrics

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object GeoMath {
    /** Great-circle distance in metres between two WGS84 lat/lon points. */
    fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2) * sin(dLon / 2)
        // Floating error can push `a` slightly over 1 for near-antipodal inputs,
        // making sqrt(1 - a) NaN; clamp to the valid domain.
        val aClamped = a.coerceIn(0.0, 1.0)
        return Constants.EARTH_RADIUS_M * 2 * atan2(sqrt(aClamped), sqrt(1 - aClamped))
    }
}
