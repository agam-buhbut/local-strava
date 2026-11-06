package com.localstrava.tracking

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationAvailability
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.localstrava.metrics.Fix

/**
 * Google Play Services fused location provider at ~1 Hz, `PRIORITY_HIGH_ACCURACY`. Preferred
 * source: denser, pre-smoothed, gap-free fixes vs the raw GPS provider — this is what closes the
 * distance under-count (sparse raw-GPS fixes chord-cut the curved path).
 *
 * Callbacks are delivered on the **main Looper** (passed explicitly to `requestLocationUpdates`),
 * preserving the single-thread confinement of the accumulator and the service's buffer/state.
 */
class FusedLocationSource(
    context: Context,
    private val onFix: (Fix) -> Unit,
    private val onLost: () -> Unit,
) : LocationSource {
    private val client: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)
    private val looper = context.mainLooper
    private var callback: LocationCallback? = null

    // Suppress a premature "GPS signal lost" notification during initial acquisition: only treat
    // unavailability as loss once a real fix has arrived.
    private var firstFixReceived = false

    @SuppressLint("MissingPermission") // caller guarantees ACCESS_FINE_LOCATION before start()
    override fun start() {
        if (callback != null) return
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, INTERVAL_MS)
            .setMinUpdateIntervalMillis(INTERVAL_MS)
            .setWaitForAccurateLocation(true)
            .build()
        val cb = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                firstFixReceived = true
                // FLP may batch more than one Location per callback; forward in time order.
                result.locations.forEach { onFix(it.toFix()) }
            }

            override fun onLocationAvailability(availability: LocationAvailability) {
                if (!availability.isLocationAvailable && firstFixReceived) onLost()
            }
        }
        callback = cb
        try {
            client.requestLocationUpdates(request, cb, looper)
        } catch (_: SecurityException) {
            callback = null
            onLost()
        } catch (e: Exception) {
            // Defensive: a runtime GMS failure degrades to "lost", never a crash.
            Log.e(TAG, "Fused location start failed", e)
            callback = null
            onLost()
        }
    }

    override fun stop() {
        callback?.let { client.removeLocationUpdates(it) }
        callback = null
        firstFixReceived = false
    }

    private companion object {
        const val INTERVAL_MS = 1000L
        const val TAG = "FusedLocationSource"
    }
}
