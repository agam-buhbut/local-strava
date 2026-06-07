package com.localstrava.ui.record

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.localstrava.format.Format
import com.localstrava.tracking.TrackPhase
import com.localstrava.tracking.TrackingUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordScreen(
    onFinished: (Long) -> Unit,
    onDiscarded: () -> Unit,
    onExitToHistory: () -> Unit,
    vm: RecordViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showSettingsHint by remember { mutableStateOf(false) }

    LaunchedEffect(state.phase) {
        when (val p = state.phase) {
            is TrackPhase.Finished -> { val id = p.activityId; vm.consumeTerminal(); onFinished(id) }
            is TrackPhase.Discarded -> { vm.consumeTerminal(); onDiscarded() }
            else -> Unit
        }
    }

    // Back during an active run: leave the service recording, return to History.
    BackHandler(enabled = state.isActive) { onExitToHistory() }

    val perms = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val fine = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (fine) { showSettingsHint = false; vm.start() } else { showSettingsHint = true }
        // notification denial is non-fatal (spec §8)
    }

    fun onStartClicked() {
        val fine = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        if (fine) {
            vm.start()
        } else {
            showSettingsHint = false
            launcher.launch(perms)
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Record") }) }) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (state.locationLost) {
                Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "Location is off — recording paused. Re-enable location to continue.",
                        Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            if (showSettingsHint && !state.isActive) {
                Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            "Location permission is required to record a run.",
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.fromParts("package", context.packageName, null),
                                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }) { Text("Open settings") }
                    }
                }
            }
            BigStat("Time", Format.duration(state.movingTimeMs))
            BigStat("Distance", Format.distance(state.distanceMeters))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                SmallStat("Pace", Format.pace(state.currentPaceSecPerKm))
                SmallStat("Avg pace", Format.pace(state.avgPaceSecPerKm))
                SmallStat("Elev", Format.elevation(state.elevationGainMeters))
            }
            Spacer(Modifier.weight(1f))
            Controls(state, ::onStartClicked, vm::pause, vm::resume, vm::stop)
        }
    }
}

@Composable
private fun Controls(
    state: TrackingUiState,
    onStart: () -> Unit, onPause: () -> Unit, onResume: () -> Unit, onStop: () -> Unit,
) {
    when (state.phase) {
        is TrackPhase.Active -> Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            OutlinedButton(onClick = onPause) { Text("Pause") }
            Button(onClick = onStop) { Text("Stop") }
        }
        is TrackPhase.Paused -> Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(onClick = onResume) { Text("Resume") }
            Button(onClick = onStop) { Text("Stop") }
        }
        else -> Button(onClick = onStart, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Start") }
    }
}

@Composable
private fun BigStat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(value, style = MaterialTheme.typography.displaySmall)
    }
}

@Composable
private fun SmallStat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall)
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}
