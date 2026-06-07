package com.localstrava.metrics

import org.junit.Assert.assertEquals
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
}
