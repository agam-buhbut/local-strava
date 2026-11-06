package com.localstrava.metrics

/** What the service should persist for an accepted fix. */
data class AcceptedPoint(val fix: Fix, val segmentBreak: Boolean)

/** Immutable snapshot of live run metrics. Pace values are null when undefined ("--"). */
data class RunStats(
    val distanceMeters: Double,
    val movingTimeMs: Long,
    val elevationGainMeters: Double,
    val currentPaceSecPerKm: Double?,
    val avgPaceSecPerKm: Double?,
    val pointCount: Int,
)

/**
 * Stateful but framework-free run accumulator. Feed it [Fix]es in time order via [update];
 * read live metrics via [snapshot]. All filtering rules live here (spec §5).
 *
 * Not thread-safe: the service confines all calls to a single thread (spec §3).
 */
class RunAccumulator {

    private data class Anchor(val lat: Double, val lon: Double, val timeMs: Long)
    private data class WindowSample(val lat: Double, val lon: Double, val timeMs: Long, val segmentBreak: Boolean)

    private var anchor: Anchor? = null
    private var distance = 0.0
    private var movingTime = 0L
    private var points = 0

    private var manualPaused = false
    private var pendingResumeBreak = false
    private var pendingGapBreak = false
    private var overAccuracyCount = 0
    private var teleportRejects = 0

    // Elevation source select + last motion classification (for barometer gating).
    private var barometerActive = false
    private var stationary = false

    private val window = ArrayDeque<WindowSample>()

    private data class SpeedSample(val lat: Double, val lon: Double, val timeMs: Long)
    private val speedWindow = ArrayDeque<SpeedSample>()

    // Elevation
    private val altBuffer = ArrayDeque<Double>()
    private var lastCommittedAlt: Double? = null
    private var elevationGain = 0.0

    fun setManualPaused(paused: Boolean) {
        if (paused && !manualPaused) {
            manualPaused = true
        } else if (!paused && manualPaused) {
            manualPaused = false
            pendingResumeBreak = true
        }
    }

    /**
     * Select the active elevation source: GPS altitude (default) or the barometric channel.
     * Barometer pseudo-altitude and GPS MSL altitude sit on different absolute datums, so
     * every transition clears the smoothing buffer and re-baselines ([lastCommittedAlt] = null)
     * — without zeroing accumulated gain — so the one-off datum gap is never counted as a climb.
     * Same single-thread confinement as [update] (spec §3).
     */
    fun setBarometerActive(active: Boolean) {
        if (active == barometerActive) return
        barometerActive = active
        altBuffer.clear()
        lastCommittedAlt = null
    }

    /**
     * Feed one barometer-derived altitude (metres). Ignored unless the barometer is the active
     * source, and gated exactly like GPS elevation: no gain while manually paused or while the
     * run is auto-paused (stationary). Same single-thread confinement as [update] (spec §3).
     */
    fun onBarometerAltitude(altitudeMeters: Double) {
        if (!barometerActive) return
        if (manualPaused || stationary) return
        commitAltitude(altitudeMeters)
    }

    /** Process one fix. Returns the point to persist, or null if rejected/not stored. */
    fun update(fix: Fix): AcceptedPoint? {
        if (manualPaused) return null

        val acc = fix.accuracyMeters ?: return null
        if (acc > Constants.MAX_ACCURACY_M) {
            overAccuracyCount++
            if (acc > Constants.MAX_ACCURACY_HARD_M ||
                overAccuracyCount > Constants.MAX_ACCURACY_TOLERATE_FIXES
            ) {
                pendingGapBreak = true // sustained bad signal → treat like a gap
            }
            return null
        }
        overAccuracyCount = 0

        val a = anchor
        if (a == null) {
            return startSegment(fix, segmentBreak = false)
        }
        if (pendingResumeBreak) {
            pendingResumeBreak = false
            return startSegment(fix, segmentBreak = true)
        }

        val dt = fix.timeMs - a.timeMs
        if (dt <= 0L) return null

        if (dt > Constants.MAX_FIX_GAP_MS || pendingGapBreak) {
            pendingGapBreak = false
            return startSegment(fix, segmentBreak = true)
        }

        val dist = GeoMath.haversineMeters(a.lat, a.lon, fix.latitude, fix.longitude)
        val dtSec = dt / 1000.0

        // Teleport spike: reject, KEEP the old anchor (don't measure from a bad fix).
        if (dist > Constants.MAX_JUMP_M || dist > Constants.MAX_PLAUSIBLE_SPEED_MPS * dtSec) {
            teleportRejects++
            if (teleportRejects > Constants.MAX_TELEPORT_REJECTS) {
                teleportRejects = 0
                return startSegment(fix, segmentBreak = true) // GPS genuinely relocated
            }
            return null
        }
        teleportRejects = 0

        // Stationary: advance anchor (so the next dt stays small) but add no distance/time.
        val speed = effectiveSpeed(fix, dist, dtSec)
        if (speed < Constants.STATIONARY_SPEED_MPS) {
            stationary = true // gate the barometer channel too (no gain while auto-paused)
            anchor = Anchor(fix.latitude, fix.longitude, fix.timeMs)
            return null
        }

        if (dist < Constants.MIN_DUP_M) {
            // Sub-MIN_DUP_M displacement is intentionally dropped as dedup, not banked.
            anchor = Anchor(fix.latitude, fix.longitude, fix.timeMs)
            return null
        }

        // Moving fix.
        stationary = false
        distance += dist
        movingTime += dt
        anchor = Anchor(fix.latitude, fix.longitude, fix.timeMs)
        if (!barometerActive) updateElevation(fix) // barometer owns elevation when active
        addToWindow(fix, segmentBreak = false)
        points++
        return AcceptedPoint(fix, segmentBreak = false)
    }

    private fun startSegment(fix: Fix, segmentBreak: Boolean): AcceptedPoint {
        stationary = false
        anchor = Anchor(fix.latitude, fix.longitude, fix.timeMs)
        teleportRejects = 0
        // Reset the speed window so net displacement never spans a break/gap.
        speedWindow.clear()
        speedWindow.addLast(SpeedSample(fix.latitude, fix.longitude, fix.timeMs))
        if (!barometerActive) updateElevation(fix) // barometer owns elevation when active
        addToWindow(fix, segmentBreak)
        points++
        return AcceptedPoint(fix, segmentBreak)
    }

    private fun updateElevation(fix: Fix) {
        val alt = fix.altitudeMeters ?: return
        val vacc = fix.verticalAccuracyMeters ?: return
        if (vacc > Constants.ELEV_VACC_MAX_M) return
        commitAltitude(alt)
    }

    /**
     * Shared elevation commit used by both the GPS path (after its vertical-accuracy gate)
     * and the barometer channel: median-of-≤[ELEV_SMOOTH_WINDOW] smoother → commit positive
     * gain past [ELEV_THRESHOLD_M] hysteresis → ratchet the baseline down on descents.
     */
    private fun commitAltitude(alt: Double) {
        altBuffer.addLast(alt)
        while (altBuffer.size > Constants.ELEV_SMOOTH_WINDOW) altBuffer.removeFirst()
        // Median of last ≤5; on an even count this takes the upper-middle by design.
        val smoothed = altBuffer.sorted()[altBuffer.size / 2]

        val last = lastCommittedAlt
        if (last == null) {
            lastCommittedAlt = smoothed
        } else {
            val diff = smoothed - last
            if (diff >= Constants.ELEV_THRESHOLD_M) {
                elevationGain += diff
                lastCommittedAlt = smoothed
            } else if (diff <= -Constants.ELEV_THRESHOLD_M) {
                // Symmetric hysteresis: only a genuine descent re-baselines. Ratcheting down on
                // sub-threshold dips converts noise oscillation into phantom gain (measured
                // over-count vs Strava, 2026-07).
                lastCommittedAlt = smoothed
            }
        }
    }

    /**
     * Speed used by the stationary gate: the device value when present, otherwise the net
     * displacement over a short trailing window divided by its span. The windowed form
     * averages raw GPS jitter (~1–2 m/fix while standing still) toward zero so auto-pause
     * does not leak distance/time (spec §5 step 4).
     */
    private fun effectiveSpeed(fix: Fix, dist: Double, dtSec: Double): Double {
        speedWindow.addLast(SpeedSample(fix.latitude, fix.longitude, fix.timeMs))
        while (speedWindow.size > 1 &&
            fix.timeMs - speedWindow.first().timeMs > Constants.STATIONARY_WINDOW_MS) {
            speedWindow.removeFirst()
        }
        fix.speedMps?.let { return it.toDouble() }
        val oldest = speedWindow.first()
        val spanMs = fix.timeMs - oldest.timeMs
        if (spanMs < Constants.MIN_SPEED_WINDOW_MS) return if (dtSec > 0) dist / dtSec else 0.0
        val net = GeoMath.haversineMeters(oldest.lat, oldest.lon, fix.latitude, fix.longitude)
        return net / (spanMs / 1000.0)
    }

    private fun addToWindow(fix: Fix, segmentBreak: Boolean) {
        // Clear the window at a break so currentPace never spans the dead time of a
        // gap/resume (pre-break samples contribute no distance but inflate windowTime).
        if (segmentBreak) window.clear()
        window.addLast(WindowSample(fix.latitude, fix.longitude, fix.timeMs, segmentBreak))
    }

    fun snapshot(nowMs: Long): RunStats {
        val avgPace =
            if (distance >= Constants.MIN_PACE_DISTANCE_M && movingTime > 0L)
                movingTime.toDouble() / distance
            else null
        return RunStats(
            distanceMeters = distance,
            movingTimeMs = movingTime,
            elevationGainMeters = elevationGain,
            currentPaceSecPerKm = currentPace(nowMs),
            avgPaceSecPerKm = avgPace,
            pointCount = points,
        )
    }

    private fun currentPace(nowMs: Long): Double? {
        val cutoff = nowMs - Constants.CURRENT_PACE_WINDOW_MS
        while (window.isNotEmpty() && window.first().timeMs < cutoff) window.removeFirst()
        if (window.size < 2) return null
        var windowDistance = 0.0
        val it = window.iterator()
        var prev = it.next()
        while (it.hasNext()) {
            val cur = it.next()
            if (!cur.segmentBreak) {
                windowDistance += GeoMath.haversineMeters(prev.lat, prev.lon, cur.lat, cur.lon)
            }
            prev = cur
        }
        val windowTime = window.last().timeMs - window.first().timeMs
        if (windowDistance < Constants.CURRENT_PACE_MIN_DISTANCE_M || windowTime <= 0L) return null
        return windowTime.toDouble() / windowDistance
    }
}
