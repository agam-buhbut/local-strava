package com.localstrava

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import org.osmdroid.config.Configuration
import java.io.File

class LocalStravaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        createTrackingChannel()
        configureOsmdroid()
    }

    private fun createTrackingChannel() {
        // minSdk 26 → NotificationChannel always available, no version guard.
        val channel = NotificationChannel(
            TRACKING_CHANNEL_ID,
            getString(R.string.app_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "Ongoing run recording" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun configureOsmdroid() {
        // Must run before any MapView is created. Pin cache to app-private storage,
        // and set a non-default User-Agent (the default "osmdroid" is banned by OSM).
        val conf = Configuration.getInstance()
        conf.load(this, getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
        conf.userAgentValue = BuildConfig.APPLICATION_ID
        val base = File(cacheDir, "osmdroid")
        conf.osmdroidBasePath = base
        conf.osmdroidTileCache = File(base, "tiles")
    }

    companion object {
        const val TRACKING_CHANNEL_ID = "tracking"
    }
}
