package com.localstrava.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface RunDao {
    @Insert suspend fun insertActivity(activity: Activity): Long
    @Update suspend fun updateActivity(activity: Activity)
    @Insert suspend fun insertPoints(points: List<TrackPoint>)

    @Query("SELECT * FROM activities WHERE id = :id")
    suspend fun getActivity(id: Long): Activity?

    @Query("SELECT * FROM activities WHERE endedAtEpochMs IS NOT NULL ORDER BY startedAtEpochMs DESC")
    fun observeCompletedActivities(): Flow<List<Activity>>

    @Query("SELECT * FROM activities WHERE endedAtEpochMs IS NULL")
    suspend fun getInProgressActivities(): List<Activity>

    @Query("SELECT * FROM track_points WHERE activityId = :activityId ORDER BY timestampEpochMs ASC")
    suspend fun getPoints(activityId: Long): List<TrackPoint>

    @Query("DELETE FROM activities WHERE id = :id")
    suspend fun deleteActivity(id: Long)
}
