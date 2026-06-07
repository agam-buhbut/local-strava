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

    private val window = ArrayDeque<WindowSample>()

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
        val speed = fix.speedMps?.toDouble() ?: (dist / dtSec)
        if (speed < Constants.STATIONARY_SPEED_MPS) {
            anchor = Anchor(fix.latitude, fix.longitude, fix.timeMs)
            return null
        }

        if (dist < Constants.MIN_DUP_M) {
            anchor = Anchor(fix.latitude, fix.longitude, fix.timeMs)
            return null
        }

        // Moving fix.
        distance += dist
        movingTime += dt
        anchor = Anchor(fix.latitude, fix.longitude, fix.timeMs)
        updateElevation(fix)
        addToWindow(fix, segmentBreak = false)
        points++
        return AcceptedPoint(fix, segmentBreak = false)
    }

    private fun startSegment(fix: Fix, segmentBreak: Boolean): AcceptedPoint {
        anchor = Anchor(fix.latitude, fix.longitude, fix.timeMs)
        teleportRejects = 0
        updateElevation(fix)
        addToWindow(fix, segmentBreak)
        points++
        return AcceptedPoint(fix, segmentBreak)
    }

    private fun updateElevation(fix: Fix) {
        val alt = fix.altitudeMeters ?: return
        val vacc = fix.verticalAccuracyMeters ?: return
        if (vacc > Constants.ELEV_VACC_MAX_M) return

        altBuffer.addLast(alt)
        while (altBuffer.size > Constants.ELEV_SMOOTH_WINDOW) altBuffer.removeFirst()
        val smoothed = altBuffer.sorted()[altBuffer.size / 2] // median of last ≤5

        val last = lastCommittedAlt
        if (last == null) {
            lastCommittedAlt = smoothed
        } else {
            val diff = smoothed - last
            if (diff >= Constants.ELEV_THRESHOLD_M) {
                elevationGain += diff
                lastCommittedAlt = smoothed
            } else if (smoothed < last) {
                lastCommittedAlt = smoothed // ratchet down so future climbs count from the low
            }
        }
    }

    private fun addToWindow(fix: Fix, segmentBreak: Boolean) {
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
