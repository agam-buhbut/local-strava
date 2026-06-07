package com.localstrava.metrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RunAccumulatorTest {

    // ~3 m north per step at the equator.
    private val stepLat = 0.000027
    private fun fix(
        lat: Double, lon: Double = 0.0, t: Long,
        acc: Float? = 5f, alt: Double? = null, speed: Float? = null, vacc: Float? = 3f,
    ) = Fix(lat, lon, alt, t, acc, speed, vacc)

    @Test fun firstFixSetsAnchorWithNoDistance() {
        val acc = RunAccumulator()
        val p = acc.update(fix(0.0, t = 0))
        assertNotNull(p)
        assertFalse(p!!.segmentBreak)
        val s = acc.snapshot(0)
        assertEquals(0.0, s.distanceMeters, 1e-9)
        assertEquals(1, s.pointCount)
    }

    @Test fun twoMovingFixesAccumulateDistanceAndMovingTime() {
        val acc = RunAccumulator()
        acc.update(fix(0.0, t = 0))
        acc.update(fix(stepLat, t = 1000))
        val s = acc.snapshot(1000)
        assertEquals(3.0, s.distanceMeters, 0.1)
        assertEquals(1000L, s.movingTimeMs)
    }

    @Test fun stationaryFixesAddNoDistanceOrTime() {
        val acc = RunAccumulator()
        acc.update(fix(0.0, t = 0))
        // device-reported speed 0 → stationary, regardless of tiny jitter
        acc.update(fix(0.0000005, t = 1000, speed = 0.0f))
        acc.update(fix(0.0000005, t = 2000, speed = 0.0f))
        val s = acc.snapshot(2000)
        assertEquals(0.0, s.distanceMeters, 1e-6)
        assertEquals(0L, s.movingTimeMs)
    }

    @Test fun teleportSpikeIsRejectedAndAnchorKept() {
        val acc = RunAccumulator()
        acc.update(fix(0.0, t = 0))
        // 0.01° ≈ 1.1 km in 1 s → teleport, rejected, not stored
        val spike = acc.update(fix(0.01, t = 1000))
        assertNull(spike)
        // next real fix is measured from the ORIGINAL anchor (3 m), not the spike
        acc.update(fix(stepLat, t = 2000))
        val s = acc.snapshot(2000)
        assertEquals(3.0, s.distanceMeters, 0.2)
    }

    @Test fun timeGapStartsNewSegmentWithoutBridgingDistance() {
        val acc = RunAccumulator()
        acc.update(fix(0.0, t = 0))
        // 10 s gap then a fix ~30 m away: do NOT add 30 m; start a new segment
        val p = acc.update(fix(stepLat * 10, t = 10_000))
        assertNotNull(p)
        assertTrue(p!!.segmentBreak)
        val s = acc.snapshot(10_000)
        assertEquals(0.0, s.distanceMeters, 1e-6)
    }

    @Test fun nonPositiveDeltaTimeRejected() {
        val acc = RunAccumulator()
        acc.update(fix(0.0, t = 1000))
        assertNull(acc.update(fix(stepLat, t = 1000))) // duplicate timestamp
        assertNull(acc.update(fix(stepLat, t = 500)))  // backwards
        assertEquals(0.0, acc.snapshot(1000).distanceMeters, 1e-9)
    }

    @Test fun nullAccuracyRejected() {
        val acc = RunAccumulator()
        acc.update(fix(0.0, t = 0))
        assertNull(acc.update(fix(stepLat, t = 1000, acc = null)))
        assertEquals(0.0, acc.snapshot(1000).distanceMeters, 1e-9)
    }

    @Test fun manualPauseExcludesTimeAndDistanceThenResumeBreaks() {
        val acc = RunAccumulator()
        acc.update(fix(0.0, t = 0))
        acc.update(fix(stepLat, t = 1000))          // 3 m
        acc.setManualPaused(true)
        assertNull(acc.update(fix(stepLat * 2, t = 2000)))  // ignored while paused
        acc.setManualPaused(false)
        val resume = acc.update(fix(stepLat * 3, t = 3000)) // first fix after resume
        assertNotNull(resume)
        assertTrue(resume!!.segmentBreak)
        val s = acc.snapshot(3000)
        // only the first 3 m counted; the paused movement and the resume bridge are excluded
        assertEquals(3.0, s.distanceMeters, 0.2)
        assertEquals(1000L, s.movingTimeMs)
    }

    @Test fun elevationGainCountsSustainedClimbAboveThreshold() {
        val acc = RunAccumulator()
        // climb 100 → 105 m over good-vertical-accuracy fixes; median-smoothed gain ≈ 5 m
        val alts = listOf(100.0, 100.0, 101.0, 102.0, 103.0, 104.0, 105.0, 105.0, 105.0)
        alts.forEachIndexed { i, a -> acc.update(fix(stepLat * i, t = i * 1000L, alt = a)) }
        val s = acc.snapshot(alts.size * 1000L)
        assertEquals(5.0, s.elevationGainMeters, 1.5)
    }

    @Test fun elevationNoiseBelowThresholdProducesNoGain() {
        val acc = RunAccumulator()
        val alts = listOf(100.0, 100.5, 99.5, 100.5, 99.5, 100.5, 99.5, 100.0)
        alts.forEachIndexed { i, a -> acc.update(fix(stepLat * i, t = i * 1000L, alt = a)) }
        assertEquals(0.0, acc.snapshot(alts.size * 1000L).elevationGainMeters, 0.5)
    }

    @Test fun altitudeWithPoorVerticalAccuracyIgnored() {
        val acc = RunAccumulator()
        val alts = listOf(100.0, 110.0, 120.0, 130.0, 140.0) // big climb...
        alts.forEachIndexed { i, a ->
            acc.update(fix(stepLat * i, t = i * 1000L, alt = a, vacc = 50f)) // ...but unreliable
        }
        assertEquals(0.0, acc.snapshot(alts.size * 1000L).elevationGainMeters, 0.01)
    }

    @Test fun averagePaceIsNullBelowMinDistance() {
        val acc = RunAccumulator()
        acc.update(fix(0.0, t = 0))
        acc.update(fix(stepLat, t = 1000)) // only 3 m < MIN_PACE_DISTANCE_M (10)
        assertNull(acc.snapshot(1000).avgPaceSecPerKm)
    }

    @Test fun averagePaceAtThreeMetresPerSecondIsAbout333() {
        val acc = RunAccumulator()
        // 10 steps of 3 m at 1 Hz → 30 m, 10 s → 3 m/s → 1000/3 ≈ 333 s/km
        for (i in 0..10) acc.update(fix(stepLat * i, t = i * 1000L))
        val s = acc.snapshot(10_000)
        assertEquals(30.0, s.distanceMeters, 1.0)
        assertEquals(333.0, s.avgPaceSecPerKm!!, 15.0)
    }

    @Test fun currentPaceIsNullWhenStopped() {
        val acc = RunAccumulator()
        for (i in 0..10) acc.update(fix(stepLat * i, t = i * 1000L))
        // 40 s later with no new movement: window is empty → current pace undefined
        assertNull(acc.snapshot(50_000).currentPaceSecPerKm)
    }

    @Test fun jitterWithNoDeviceSpeedReadsAsStationary() {
        val acc = RunAccumulator()
        // ~1 m of latitude near the equator.
        val m = 0.000009
        // Stand still while GPS jitters ±0.7 m about a fixed point at 1 Hz. Every
        // consecutive step is ~1.4 m (> MIN_DUP_M, so the dedup gate can't hide it),
        // but net displacement over any trailing window is ≈ 0. speed = null forces
        // the windowed dist/dt path, which must average the jitter toward zero.
        val offsets = listOf(-0.7, 0.7, -0.7, 0.7, -0.7, 0.7, -0.7)
        offsets.forEachIndexed { i, o ->
            acc.update(fix(o * m, t = i * 1000L, speed = null))
        }
        val s = acc.snapshot(6000)
        // Instantaneous logic would bank every ~1.4 m jitter step (~8 m); windowed
        // logic keeps it tiny (only the first sample or two before the window fills).
        assertTrue("distance should stay small, was ${s.distanceMeters}", s.distanceMeters < 4.0)
        assertTrue("moving time should stay small, was ${s.movingTimeMs}", s.movingTimeMs <= 2000L)
    }

    @Test fun currentPaceDoesNotSpanSegmentBreak() {
        val acc = RunAccumulator()
        // Pre-break moving segment (~6 m in 1 s), little distance.
        acc.update(fix(0.0, t = 0))
        acc.update(fix(stepLat * 2, t = 1000))
        // >5 s gap with a far jump → segment break (no distance bridged).
        acc.update(fix(stepLat * 30, t = 8000))
        // Post-break segment at a known 3 m/s (~333 s/km), 4 fixes 3 m apart at 1 Hz.
        for (i in 1..4) acc.update(fix(stepLat * 30 + stepLat * i, t = 8000L + i * 1000L))
        // The whole sequence is inside the 20 s window. Pace must reflect ONLY the
        // post-break 3 m/s segment (~333), not the inflated value (~666) you get when
        // the dead time of the gap is counted against post-break distance alone.
        val pace = acc.snapshot(12_000).currentPaceSecPerKm!!
        assertEquals(333.0, pace, 30.0)
    }
}
