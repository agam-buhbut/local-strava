package com.localstrava.metrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoMathTest {
    @Test fun zeroDistanceForSamePoint() {
        assertEquals(0.0, GeoMath.haversineMeters(45.0, 8.0, 45.0, 8.0), 1e-6)
    }

    @Test fun oneDegreeLatitudeIsAbout111Km() {
        // 1° of latitude = R * π/180 = 111194.93 m
        val d = GeoMath.haversineMeters(0.0, 0.0, 1.0, 0.0)
        assertEquals(111_194.93, d, 1.0)
    }

    @Test fun shortRunningSegmentAboutThreeMetres() {
        // 0.000027° latitude ≈ 3.00 m
        val d = GeoMath.haversineMeters(0.0, 0.0, 0.000027, 0.0)
        assertEquals(3.00, d, 0.05)
    }

    @Test fun antimeridianCrossingIsShort() {
        // Two points straddling ±180° are ~22 m apart, not ~40 000 km.
        val d = GeoMath.haversineMeters(0.0, 179.9999, 0.0, -179.9999)
        assertTrue("expected a short distance, got $d", d < 100.0)
    }
}
