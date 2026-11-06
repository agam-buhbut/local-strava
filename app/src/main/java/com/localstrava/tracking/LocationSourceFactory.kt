package com.localstrava.tracking

import android.content.Context
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.localstrava.metrics.Fix

/**
 * Picks the best available [LocationSource]: the Google Play Services fused provider when usable
 * (denser/smoother fixes), else the framework [LegacyLocationSource] so de-Googled / F-Droid
 * devices still record. Selection is made once, at service start.
 *
 * Caveat (accepted): a transient GMS state at start would route a GMS device to the legacy path
 * for the whole session; the run still records correctly, just on the raw provider.
 */
object LocationSourceFactory {
    fun create(context: Context, onFix: (Fix) -> Unit, onLost: () -> Unit): LocationSource =
        if (fusedUsable(context)) FusedLocationSource(context, onFix, onLost)
        else LegacyLocationSource(context, onFix, onLost)

    private fun fusedUsable(context: Context): Boolean {
        val status = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context)
        // SUCCESS, or "an update is available but the installed version still serves location".
        return status == ConnectionResult.SUCCESS ||
            status == ConnectionResult.SERVICE_VERSION_UPDATE_REQUIRED
    }
}
