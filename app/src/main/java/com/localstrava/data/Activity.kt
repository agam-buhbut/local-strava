package com.localstrava.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "activities")
data class Activity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAtEpochMs: Long,
    val endedAtEpochMs: Long?,        // null = in-progress (crash-recoverable)
    val movingTimeMs: Long,
    val distanceMeters: Double,
    val elevationGainMeters: Double,
)
