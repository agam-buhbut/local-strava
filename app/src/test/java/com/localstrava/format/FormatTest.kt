package com.localstrava.format

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {
    @Test fun distanceBelowKilometreInMetres() = assertEquals("850 m", Format.distance(850.0))
    @Test fun distanceAboveKilometre() = assertEquals("1.23 km", Format.distance(1234.0))
    @Test fun durationUnderHour() = assertEquals("5:30", Format.duration(330_000))
    @Test fun durationOverHour() = assertEquals("1:02:03", Format.duration(3_723_000))
    @Test fun paceNullShowsDashes() = assertEquals("--", Format.pace(null))
    @Test fun paceFormatsMinutesPerKm() = assertEquals("5:30 /km", Format.pace(330.0))
    @Test fun elevationRoundsToMetres() = assertEquals("123 m", Format.elevation(123.4))
    @Test fun avgPaceComputesFromDistanceAndMovingTime() =
        assertEquals("5:30 /km", Format.avgPace(1000.0, 330_000))
    @Test fun avgPaceBelowMinDistanceShowsDashes() = assertEquals("--", Format.avgPace(5.0, 60_000))
    @Test fun avgPaceZeroMovingTimeShowsDashes() = assertEquals("--", Format.avgPace(100.0, 0))
}
