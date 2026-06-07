package com.localstrava.data

import com.localstrava.metrics.Constants
import com.localstrava.metrics.RunStats
import kotlinx.coroutines.flow.Flow

class ActivityRepository(private val dao: RunDao) {

    fun observeCompleted(): Flow<List<Activity>> = dao.observeCompletedActivities()

    suspend fun getActivity(id: Long): Activity? = dao.getActivity(id)
    suspend fun getPoints(id: Long): List<TrackPoint> = dao.getPoints(id)

    suspend fun startActivity(startedAtEpochMs: Long): Long =
        dao.insertActivity(
            Activity(
                startedAtEpochMs = startedAtEpochMs,
                endedAtEpochMs = null,
                movingTimeMs = 0L,
                distanceMeters = 0.0,
                elevationGainMeters = 0.0,
            )
        )

    suspend fun appendPoints(points: List<TrackPoint>) {
        if (points.isNotEmpty()) dao.insertPoints(points)
    }

    /** Keep the in-progress summary fresh (called on each flush). Never touches endedAt. */
    suspend fun updateProgress(id: Long, stats: RunStats) {
        dao.updateProgress(id, stats.movingTimeMs, stats.distanceMeters, stats.elevationGainMeters)
    }

    suspend fun finalizeActivity(id: Long, startedAtEpochMs: Long, endedAtEpochMs: Long, stats: RunStats) {
        dao.updateActivity(
            Activity(id, startedAtEpochMs, endedAtEpochMs, stats.movingTimeMs, stats.distanceMeters, stats.elevationGainMeters)
        )
    }

    suspend fun delete(id: Long) = dao.deleteActivity(id)

    /** Finalize or discard runs left in-progress by a crash/kill (spec §3.9). */
    suspend fun recoverInProgress() {
        for (a in dao.getInProgressActivities()) {
            val points = dao.getPoints(a.id)
            if (points.size < Constants.MIN_SAVE_POINTS || a.distanceMeters < Constants.MIN_SAVE_M) {
                dao.deleteActivity(a.id)
            } else {
                dao.updateActivity(a.copy(endedAtEpochMs = points.last().timestampEpochMs))
            }
        }
    }
}
