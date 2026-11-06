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
import android.os.PowerManager
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
import com.localstrava.metrics.Goal
import com.localstrava.metrics.isReached
import com.localstrava.metrics.RunAccumulator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class TrackingService : Service() {

    // limitedParallelism(1) serializes all DB writes FIFO so the STOP finalize can't be
    // overtaken by a late incremental flush (which would re-null endedAt). See spec / fix note.
    @OptIn(ExperimentalCoroutinesApi::class)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private lateinit var repo: ActivityRepository
    private lateinit var locationSource: LocationSource
    private var barometerSource: BarometerSource? = null
    private lateinit var bell: GoalBell
    private var wakeLock: PowerManager.WakeLock? = null

    private val accumulator = RunAccumulator()
    private var startJob: Deferred<Long>? = null
    private var startedAtMs: Long = 0L
    private val buffer = ArrayList<AcceptedPoint>()
    private var lastFlushMs: Long = 0L
    private var lastNotifiedHundredM: Int = -1

    override fun onCreate() {
        super.onCreate()
        repo = ActivityRepository(RunDatabase.get(this).runDao())
        bell = GoalBell(this)
        locationSource = LocationSourceFactory.create(this, ::onFix, ::onLocationLost)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(intent.goal())
            ACTION_PAUSE -> handlePause()
            ACTION_RESUME -> handleResume()
            ACTION_STOP -> handleStop()
            else -> Unit // null intent after restart, or unknown action: ignore
        }
        return START_NOT_STICKY
    }

    private fun handleStart(goal: Goal?) {
        if (TrackingStateHolder.state.value.isActive) return // idempotent
        startedAtMs = System.currentTimeMillis()
        lastFlushMs = startedAtMs
        lastNotifiedHundredM = -1
        // startForeground must be the FIRST real action (FGS start window, spec §6).
        startForegroundCompat(buildNotification(paused = false, lost = false))
        TrackingStateHolder.set(TrackingUiState(phase = TrackPhase.Active, goal = goal))
        acquireWakeLock()
        startJob = scope.async { repo.startActivity(startedAtMs) }
        locationSource.start()
        BarometerSource(this, ::onBarometerAltitude, ::onBarometerUnavailable).also {
            barometerSource = it
            it.start()
        }
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
        barometerSource?.stop() // synchronous, main thread — not inside scope.launch
        releaseWakeLock()
        val endedAt = System.currentTimeMillis()
        val stats = accumulator.snapshot(endedAt)
        val tail = ArrayList(buffer) // copy on the main thread (buffer is main-thread-only)
        buffer.clear()
        val started = startedAtMs
        val tooShort = Constants.belowSaveThreshold(stats.pointCount, stats.distanceMeters)
        val job = startJob
        scope.launch {
            val id = job?.await() ?: 0L
            if (id != 0L) {
                repo.appendPoints(tail.map { it.toEntity(id) })
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
        // A fix means location is working again — clear any "GPS lost" state.
        if (TrackingStateHolder.state.value.locationLost) {
            TrackingStateHolder.update { it.copy(locationLost = false) }
            pushNotification(
                paused = TrackingStateHolder.state.value.phase == TrackPhase.Paused,
                lost = false,
            )
        }
        val accepted = accumulator.update(fix)
        if (accepted != null) buffer.add(accepted)
        publishStats(fix.timeMs)
        if (accepted != null) maybeFlush(fix.timeMs)
    }

    // Barometer callbacks — delivered on the main Looper (Handler(mainLooper) in BarometerSource),
    // so they share the accumulator's single-thread confinement with onFix.
    private fun onBarometerAltitude(altitudeMeters: Double) {
        accumulator.setBarometerActive(true) // first sample re-baselines onto the barometer datum
        accumulator.onBarometerAltitude(altitudeMeters)
    }

    private fun onBarometerUnavailable() {
        accumulator.setBarometerActive(false) // no/stale barometer → GPS-altitude fallback
    }

    private fun onLocationLost() {
        // GPS/provider turned off mid-run. Do NOT pause the accumulator: its gap logic
        // (MAX_FIX_GAP_MS) makes a clean segment break for the dead interval and moving time
        // naturally stops (no fixes arrive). Just surface the state; onFix clears it on return.
        val phase = TrackingStateHolder.state.value.phase
        if (phase == TrackPhase.Active || phase == TrackPhase.Paused) {
            TrackingStateHolder.update { it.copy(locationLost = true) }
            pushNotification(paused = phase == TrackPhase.Paused, lost = true)
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
            )
        }
        // Once per run: goalReached latches; the service is the state's only writer (main thread).
        val st = TrackingStateHolder.state.value
        if (st.goal != null && !st.goalReached && st.goal.isReached(s.distanceMeters, s.movingTimeMs)) {
            TrackingStateHolder.update { it.copy(goalReached = true) }
            bell.ring()
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
        val batch = ArrayList(buffer)
        buffer.clear()
        val stats = accumulator.snapshot(nowMs)
        val job = startJob ?: return
        scope.launch {
            val id = job.await()
            repo.appendPoints(batch.map { it.toEntity(id) })
            repo.updateProgress(id, stats)
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
            paused -> "Run paused"
            lost -> "Recording — GPS signal lost"
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

    // Samsung's battery management freezes/throttles the app with the screen off despite the
    // location FGS, so no fixes arrive while locked. A partial wake lock keeps the CPU up for
    // the whole run. Main-thread only, like the rest of the service state.
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
            .apply { acquire(WAKE_LOCK_TIMEOUT_MS) }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
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
        // Durability is covered by incremental flush + STOP flush + recoverInProgress();
        // do not block the main thread on a DB write here.
        // Belt-and-suspenders: ensure the location request is released on any teardown path
        // (a leaked FLP callback would keep requesting at 1 Hz). Idempotent with handleStop().
        if (::locationSource.isInitialized) locationSource.stop()
        barometerSource?.stop()
        releaseWakeLock()
        if (::bell.isInitialized) bell.release()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.localstrava.action.START"
        const val ACTION_PAUSE = "com.localstrava.action.PAUSE"
        const val ACTION_RESUME = "com.localstrava.action.RESUME"
        const val ACTION_STOP = "com.localstrava.action.STOP"
        private const val EXTRA_GOAL_DISTANCE_M = "com.localstrava.extra.GOAL_DISTANCE_M"
        private const val EXTRA_GOAL_TIME_MS = "com.localstrava.extra.GOAL_TIME_MS"
        private const val NOTIF_ID = 1
        private const val FLUSH_INTERVAL_MS = 10_000L
        private const val WAKE_LOCK_TAG = "LocalStrava:run"
        // Leak safety net only: handleStop()/onDestroy() release it long before this in practice.
        private const val WAKE_LOCK_TIMEOUT_MS = 12 * 60 * 60 * 1000L

        /** START must be called from a visible Activity with permissions already granted (spec §6). */
        fun start(context: Context, goal: Goal?) = ContextCompat.startForegroundService(
            context,
            Intent(context, TrackingService::class.java).setAction(ACTION_START).apply {
                when (goal) {
                    is Goal.Distance -> putExtra(EXTRA_GOAL_DISTANCE_M, goal.meters)
                    is Goal.Time -> putExtra(EXTRA_GOAL_TIME_MS, goal.millis)
                    null -> Unit
                }
            },
        )

        private fun Intent.goal(): Goal? = when {
            hasExtra(EXTRA_GOAL_DISTANCE_M) -> Goal.Distance(getDoubleExtra(EXTRA_GOAL_DISTANCE_M, 0.0))
            hasExtra(EXTRA_GOAL_TIME_MS) -> Goal.Time(getLongExtra(EXTRA_GOAL_TIME_MS, 0L))
            else -> null
        }
        // PAUSE/RESUME/STOP are delivered to the already-running service (no FGS start contract).
        fun pause(context: Context) = deliver(context, ACTION_PAUSE)
        fun resume(context: Context) = deliver(context, ACTION_RESUME)
        fun stop(context: Context) = deliver(context, ACTION_STOP)

        private fun deliver(context: Context, action: String) {
            context.startService(Intent(context, TrackingService::class.java).setAction(action))
        }
    }
}
