package com.localstrava.tracking

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import com.localstrava.metrics.Fix

/**
 * Thin wrapper over the framework LocationManager (GPS provider). Maps Location → Fix and
 * delivers callbacks on the main Looper (the single thread the accumulator is confined to).
 * Surfaces permission/provider loss via [onLost].
 */
class LocationSource(
    private val context: Context,
    private val onFix: (Fix) -> Unit,
    private val onLost: () -> Unit,
) {
    private val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var listener: LocationListener? = null

    @SuppressLint("MissingPermission") // caller guarantees ACCESS_FINE_LOCATION before start()
    fun start() {
        if (listener != null) return
        val l = object : LocationListener {
            override fun onLocationChanged(location: Location) = onFix(location.toFix())
            override fun onProviderDisabled(provider: String) = onLost()
            override fun onProviderEnabled(provider: String) {}
            @Deprecated("Deprecated in API 29") @Suppress("DEPRECATION")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }
        listener = l
        try {
            manager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, 1000L, 0f, l, context.mainLooper,
            )
        } catch (e: SecurityException) {
            listener = null
            onLost()
        }
    }

    fun stop() {
        listener?.let { manager.removeUpdates(it) }
        listener = null
    }

    private fun Location.toFix(): Fix = Fix(
        latitude = latitude,
        longitude = longitude,
        altitudeMeters = if (hasAltitude()) altitude else null,
        timeMs = time,
        accuracyMeters = if (hasAccuracy()) accuracy else null,
        speedMps = if (hasSpeed()) speed else null,
        verticalAccuracyMeters = if (hasVerticalAccuracy()) verticalAccuracyMeters else null,
    )
}
