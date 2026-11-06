package com.localstrava.tracking

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.SystemClock
import com.localstrava.metrics.Constants

/**
 * Barometric altimeter. Registers a `TYPE_PRESSURE` listener and emits a pressure-derived altitude
 * (metres) at ~1 Hz on the **main Looper**, so the accumulator's elevation state stays single-thread
 * confined with the location path.
 *
 * Downstream only relative deltas are used: the emitted value is a standard-atmosphere pseudo-
 * altitude (datum offset by the day's sea-level pressure) and must never be diffed against GPS
 * altitude — the accumulator re-baselines on every source switch to enforce that.
 *
 * Callbacks are confined via a `Handler(mainLooper)` passed to `registerListener` (the 4-arg
 * overload): the 3-arg form would deliver on a SensorManager thread and race the location path.
 *
 * When the device has no barometer, registration fails, or samples go stale (none for
 * [Constants.BAROMETER_STALE_MS]), [onUnavailable] fires so the service falls back to GPS-altitude
 * elevation. Lifecycle: [start]/[stop] on the main thread; [stop] is idempotent.
 */
class BarometerSource(
    context: Context,
    private val onAltitude: (Double) -> Unit,
    private val onUnavailable: () -> Unit,
) : SensorEventListener {
    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? = manager.getDefaultSensor(Sensor.TYPE_PRESSURE)
    private val handler = Handler(context.mainLooper) // confine callbacks to the main Looper
    private val staleRunnable = Runnable { onUnavailable() }

    private var registered = false
    private var lastEmitMs = 0L // decimation cursor (uptime ms)

    fun start() {
        if (registered) return
        val s = sensor ?: run { onUnavailable(); return }
        registered = manager.registerListener(this, s, SensorManager.SENSOR_DELAY_NORMAL, handler)
        if (!registered) {
            onUnavailable()
            return
        }
        // Watchdog: if no sample arrives within the window, treat the sensor as unavailable.
        handler.postDelayed(staleRunnable, Constants.BAROMETER_STALE_MS)
    }

    fun stop() {
        handler.removeCallbacks(staleRunnable)
        if (registered) {
            manager.unregisterListener(this)
            registered = false
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        // Re-arm the staleness watchdog on every raw sample.
        handler.removeCallbacks(staleRunnable)
        handler.postDelayed(staleRunnable, Constants.BAROMETER_STALE_MS)

        // Decimate the ~5 Hz sensor stream to ~1 Hz so the 1 Hz-tuned elevation smoother holds.
        val nowMs = SystemClock.uptimeMillis()
        if (nowMs - lastEmitMs < EMIT_INTERVAL_MS) return
        lastEmitMs = nowMs

        // Platform standard-atmosphere conversion (hPa in, metres out); same hypsometric formula
        // we'd hand-roll. Only deltas are used downstream, so the fixed sea-level reference
        // cancels — weather/calibration offset does not affect gain.
        onAltitude(
            SensorManager.getAltitude(
                SensorManager.PRESSURE_STANDARD_ATMOSPHERE, event.values[0],
            ).toDouble(),
        )
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private companion object {
        const val EMIT_INTERVAL_MS = 1000L
    }
}
