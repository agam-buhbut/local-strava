package com.localstrava.data

import androidx.room.ColumnInfo
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
    // v2 (MIGRATION_1_2). defaultValue must mirror the migration's DEFAULT 0 or Room's
    // post-upgrade schema check fails; note has NO defaultValue on purpose (column is plain TEXT).
    @ColumnInfo(defaultValue = "0") val logged: Boolean = false, // typed in from the notebook, no track
    val note: String? = null,
)

/** A notebook entry: a run timed some other way (spec §4.3). endedAt is set, so crash recovery skips it. */
fun loggedActivity(startedAtEpochMs: Long, distanceMeters: Double, durationMs: Long, note: String?): Activity =
    Activity(
        startedAtEpochMs = startedAtEpochMs,
        endedAtEpochMs = startedAtEpochMs + durationMs,
        movingTimeMs = durationMs,
        distanceMeters = distanceMeters,
        elevationGainMeters = 0.0,
        logged = true,
        note = note?.trim()?.ifEmpty { null },
    )
