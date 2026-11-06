package com.localstrava.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [Activity::class, TrackPoint::class], version = 2, exportSchema = false)
abstract class RunDatabase : RoomDatabase() {
    abstract fun runDao(): RunDao

    companion object {
        // Additive only — existing runs must survive (spec §4.3). Never add a destructive fallback.
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE activities ADD COLUMN logged INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE activities ADD COLUMN note TEXT")
            }
        }

        @Volatile private var instance: RunDatabase? = null
        fun get(context: Context): RunDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    RunDatabase::class.java,
                    "local-strava.db",
                ).addMigrations(MIGRATION_1_2).build().also { instance = it }
            }
    }
}
