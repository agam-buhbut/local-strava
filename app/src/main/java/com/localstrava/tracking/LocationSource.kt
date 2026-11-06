package com.localstrava.tracking

import android.location.Location
import android.os.Build
import com.localstrava.metrics.Fix

/**
 * Source of GPS [Fix]es delivered on the **main Looper** — the single thread the accumulator and
 * the service's buffer/state are confined to (spec §3). Implementations:
 *  - [FusedLocationSource]: Google Play Services fused provider (preferred — denser, pre-smoothed,
 *    gap-free fixes; fixes the distance under-count vs the raw GPS provider).
 *  - [LegacyLocationSource]: framework `LocationManager` GPS provider, used when Play Services is
 *    absent (de-Googled / F-Droid devices).
 * Pick the right one via [LocationSourceFactory].
 */
interface LocationSource {
    /** Begin updates. Call on the main thread; caller guarantees `ACCESS_FINE_LOCATION`. */
    fun start()

    /** Stop updates and release the listener/callback. Main thread; idempotent. */
    fun stop()
}

/**
 * Maps a framework [Location] to the pure [Fix] DTO (keeps Android types out of `metrics/`).
 * Altitude prefers the mean-sea-level value (API 34+, when the provider supplies it) over the raw
 * WGS84-ellipsoid height: MSL is what maps and other trackers report (the two differ by a
 * region-constant geoid offset, ~tens of metres). Gain math is delta-based so either datum works;
 * this only matters for absolute readouts like max elevation.
 */
internal fun Location.toFix(): Fix = Fix(
    latitude = latitude,
    longitude = longitude,
    altitudeMeters = when {
        Build.VERSION.SDK_INT >= 34 && hasMslAltitude() -> mslAltitudeMeters
        hasAltitude() -> altitude
        else -> null
    },
    timeMs = time,
    accuracyMeters = if (hasAccuracy()) accuracy else null,
    speedMps = if (hasSpeed()) speed else null,
    verticalAccuracyMeters = if (hasVerticalAccuracy()) verticalAccuracyMeters else null,
)
