package com.localstrava.ui.record

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localstrava.tracking.TrackPhase
import com.localstrava.tracking.TrackingService
import com.localstrava.tracking.TrackingStateHolder

@Composable
fun RecordScreen(
    onFinished: (Long) -> Unit,
    onDiscarded: () -> Unit,
    onExitToHistory: () -> Unit,
) {
    val state by TrackingStateHolder.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    var showSettingsHint by remember { mutableStateOf(false) }

    LaunchedEffect(state.phase) {
        when (val p = state.phase) {
            is TrackPhase.Finished -> {
                val id = p.activityId
                TrackingStateHolder.consumeTerminal()
                onFinished(id)
            }
            is TrackPhase.Discarded -> { TrackingStateHolder.consumeTerminal(); onDiscarded() }
            else -> Unit
        }
    }

    // Back during an active run: leave the service recording, return to History.
    BackHandler(enabled = state.isActive) { onExitToHistory() }

    val perms = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    val powerManager = remember { context.getSystemService(PowerManager::class.java) }
    var batteryExempt by remember {
        mutableStateOf(powerManager.isIgnoringBatteryOptimizations(context.packageName))
    }
    // Re-check on return from the system dialog or Settings.
    LifecycleResumeEffect(Unit) {
        batteryExempt = powerManager.isIgnoringBatteryOptimizations(context.packageName)
        onPauseOrDispose { }
    }

    // The goal is read from prefs at start time on every path, so none of them can drop it.
    fun startService() = TrackingService.start(context, goalFromPrefs(prefs))

    // The run starts whatever the answer: the exemption only helps screen-off reliability.
    val batteryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { startService() }

    // Ask once to be exempt from battery optimization: Samsung freezes non-exempt apps with the
    // screen off, so a locked phone stopped recording despite the location FGS.
    fun startRun() {
        if (powerManager.isIgnoringBatteryOptimizations(context.packageName) ||
            prefs.getBoolean(KEY_BATTERY_ASKED, false)
        ) {
            startService()
            return
        }
        prefs.edit { putBoolean(KEY_BATTERY_ASKED, true) }
        try {
            batteryLauncher.launch(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.fromParts("package", context.packageName, null),
                ),
            )
        } catch (e: ActivityNotFoundException) {
            startService() // ROM without the dialog: start anyway
        }
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val fine = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (fine) { showSettingsHint = false; startRun() } else { showSettingsHint = true }
        // notification denial is non-fatal (spec §8)
    }

    fun onStartClicked() {
        val fine = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        if (fine) {
            startRun()
        } else {
            showSettingsHint = false
            launcher.launch(perms)
        }
    }

    Scaffold { padding ->
        if (state.isActive) {
            RunningContent(
                state,
                onPause = { TrackingService.pause(context) },
                onResume = { TrackingService.resume(context) },
                onStop = { TrackingService.stop(context) },
                modifier = Modifier.padding(padding),
            )
        } else {
            ReadyContent(
                prefs,
                showPermissionHint = showSettingsHint,
                showSamsungTip = !batteryExempt,
                onOpenSettings = {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", context.packageName, null),
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                },
                onStart = ::onStartClicked,
                modifier = Modifier.padding(padding),
            )
        }
    }
}

private const val PREFS_NAME = "record"
private const val KEY_BATTERY_ASKED = "battery_optimization_asked"
