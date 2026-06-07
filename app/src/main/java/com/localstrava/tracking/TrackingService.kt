package com.localstrava.tracking

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.localstrava.LocalStravaApp
import com.localstrava.MainActivity
import com.localstrava.data.ActivityRepository
import com.localstrava.data.RunDatabase
import com.localstrava.data.TrackPoint
import com.localstrava.format.Format
import com.localstrava.metrics.AcceptedPoint
import com.localstrava.metrics.Constants
import com.localstrava.metrics.Fix
import com.localstrava.metrics.RunAccumulator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class TrackingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var repo: ActivityRepository
    private lateinit var locationSource: LocationSource

    private val accumulator = RunAccumulator()
    private var activityId: Long = 0L
    private var startedAtMs: Long = 0L
    private val buffer = ArrayList<AcceptedPoint>()
    private var lastFlushMs: Long = 0L
    private var lastNotifiedHundredM: Int = -1

    override fun onCreate() {
        super.onCreate()
        repo = ActivityRepository(RunDatabase.get(this).runDao())
        locationSource = LocationSource(this, ::onFix, ::onLocationLost)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart()
            ACTION_PAUSE -> handlePause()
            ACTION_RESUME -> handleResume()
            ACTION_STOP -> handleStop()
            else -> Unit // null intent after restart, or unknown action: ignore
        }
        return START_NOT_STICKY
    }

    private fun handleStart() {
        if (TrackingStateHolder.state.value.isActive) return // idempotent
        startedAtMs = System.currentTimeMillis()
        lastFlushMs = startedAtMs
        // startForeground must be the FIRST real action (FGS start window, spec §6).
        startForegroundCompat(buildNotification(paused = false, lost = false))
        TrackingStateHolder.set(TrackingUiState(phase = TrackPhase.Active))
        scope.launch { activityId = repo.startActivity(startedAtMs) }
        locationSource.start()
    }

    private fun handlePause() {
        if (TrackingStateHolder.state.value.phase != TrackPhase.Active) return
        accumulator.setManualPaused(true)
        TrackingStateHolder.update { it.copy(phase = TrackPhase.Paused) }
        pushNotification(paused = true, lost = false)
    }

    private fun handleResume() {
        if (TrackingStateHolder.state.value.phase != TrackPhase.Paused) return
        accumulator.setManualPaused(false)
        TrackingStateHolder.update { it.copy(phase = TrackPhase.Active, locationLost = false) }
        pushNotification(paused = false, lost = false)
    }

    private fun handleStop() {
        val phase = TrackingStateHolder.state.value.phase
        if (phase != TrackPhase.Active && phase != TrackPhase.Paused) return
        locationSource.stop()
        val endedAt = System.currentTimeMillis()
        val stats = accumulator.snapshot(endedAt)
        val id = activityId
        val started = startedAtMs
        val tooShort = stats.pointCount < Constants.MIN_SAVE_POINTS ||
            stats.distanceMeters < Constants.MIN_SAVE_M
        scope.launch {
            flushBufferLocked(id)
            if (id != 0L) {
                if (tooShort) repo.delete(id) else repo.finalizeActivity(id, started, endedAt, stats)
            }
            TrackingStateHolder.set(
                if (tooShort || id == 0L) TrackingUiState(phase = TrackPhase.Discarded)
                else TrackingUiState(phase = TrackPhase.Finished(id)),
            )
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun onFix(fix: Fix) {
        val accepted = accumulator.update(fix)
        if (accepted != null) buffer.add(accepted)
        publishStats(fix.timeMs)
        if (accepted != null) maybeFlush(fix.timeMs)
    }

    private fun onLocationLost() {
        if (TrackingStateHolder.state.value.phase == TrackPhase.Active) {
            accumulator.setManualPaused(true) // auto-pause; stats preserved
            TrackingStateHolder.update { it.copy(locationLost = true) }
            pushNotification(paused = true, lost = true)
        }
    }

    private fun publishStats(nowMs: Long) {
        val s = accumulator.snapshot(nowMs)
        TrackingStateHolder.update {
            it.copy(
                distanceMeters = s.distanceMeters,
                movingTimeMs = s.movingTimeMs,
                elevationGainMeters = s.elevationGainMeters,
                currentPaceSecPerKm = s.currentPaceSecPerKm,
                avgPaceSecPerKm = s.avgPaceSecPerKm,
                pointCount = s.pointCount,
            )
        }
        val hundredM = (s.distanceMeters / 100).toInt()
        if (hundredM != lastNotifiedHundredM) {
            lastNotifiedHundredM = hundredM
            val paused = TrackingStateHolder.state.value.phase == TrackPhase.Paused
            pushNotification(paused = paused, lost = TrackingStateHolder.state.value.locationLost)
        }
    }

    private fun maybeFlush(nowMs: Long) {
        if (nowMs - lastFlushMs < FLUSH_INTERVAL_MS || buffer.isEmpty()) return
        lastFlushMs = nowMs
        val id = activityId
        val started = startedAtMs
        val stats = accumulator.snapshot(nowMs)
        val batch = ArrayList(buffer)
        buffer.clear()
        scope.launch {
            if (id != 0L) {
                repo.appendPoints(batch.map { it.toEntity(id) })
                repo.updateProgress(id, started, stats)
            }
        }
    }

    private suspend fun flushBufferLocked(id: Long) {
        if (id != 0L && buffer.isNotEmpty()) {
            repo.appendPoints(buffer.map { it.toEntity(id) })
            buffer.clear()
        }
    }

    private fun AcceptedPoint.toEntity(id: Long) = TrackPoint(
        activityId = id,
        latitude = fix.latitude,
        longitude = fix.longitude,
        altitudeMeters = fix.altitudeMeters,
        timestampEpochMs = fix.timeMs,
        accuracyMeters = fix.accuracyMeters,
        speedMps = fix.speedMps,
        segmentBreak = segmentBreak,
    )

    private fun buildNotification(paused: Boolean, lost: Boolean): Notification {
        val s = TrackingStateHolder.state.value
        val title = when {
            lost -> "Location off — paused"
            paused -> "Run paused"
            else -> "Recording run"
        }
        val text = "${Format.distance(s.distanceMeters)} · ${Format.duration(s.movingTimeMs)}"
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val b = NotificationCompat.Builder(this, LocalStravaApp.TRACKING_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
        if (paused) b.addAction(0, "Resume", serviceAction(ACTION_RESUME))
        else b.addAction(0, "Pause", serviceAction(ACTION_PAUSE))
        b.addAction(0, "Stop", serviceAction(ACTION_STOP))
        return b.build()
    }

    private fun serviceAction(action: String): PendingIntent {
        val intent = Intent(this, TrackingService::class.java).setAction(action)
        return PendingIntent.getService(
            this, action.hashCode(), intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun pushNotification(paused: Boolean, lost: Boolean) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, buildNotification(paused, lost))
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    override fun onDestroy() {
        val id = activityId
        if (id != 0L && buffer.isNotEmpty()) {
            runBlocking { repo.appendPoints(buffer.map { it.toEntity(id) }) }
            buffer.clear()
        }
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.localstrava.action.START"
        const val ACTION_PAUSE = "com.localstrava.action.PAUSE"
        const val ACTION_RESUME = "com.localstrava.action.RESUME"
        const val ACTION_STOP = "com.localstrava.action.STOP"
        private const val NOTIF_ID = 1
        private const val FLUSH_INTERVAL_MS = 10_000L

        /** START must be called from a visible Activity with permissions already granted (spec §6). */
        fun start(context: Context) = ContextCompat.startForegroundService(
            context, Intent(context, TrackingService::class.java).setAction(ACTION_START),
        )
        // PAUSE/RESUME/STOP are delivered to the already-running service (no FGS start contract).
        fun pause(context: Context) = deliver(context, ACTION_PAUSE)
        fun resume(context: Context) = deliver(context, ACTION_RESUME)
        fun stop(context: Context) = deliver(context, ACTION_STOP)

        private fun deliver(context: Context, action: String) {
            context.startService(Intent(context, TrackingService::class.java).setAction(action))
        }
    }
}
