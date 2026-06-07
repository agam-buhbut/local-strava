package com.localstrava.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "track_points",
    foreignKeys = [ForeignKey(
        entity = Activity::class,
        parentColumns = ["id"],
        childColumns = ["activityId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("activityId")],
)
data class TrackPoint(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val activityId: Long,
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double?,
    val timestampEpochMs: Long,
    val accuracyMeters: Float?,
    val speedMps: Float?,
    val segmentBreak: Boolean,
)
