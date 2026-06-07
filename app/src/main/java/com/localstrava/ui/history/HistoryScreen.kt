package com.localstrava.ui.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.localstrava.data.Activity
import com.localstrava.format.Format
import com.localstrava.tracking.TrackingStateHolder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val dateFmt = SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault())

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    onStartRun: () -> Unit,
    onOpenRun: (Long) -> Unit,
    vm: HistoryViewModel = viewModel(),
) {
    val runs by vm.runs.collectAsStateWithLifecycle()
    val tracking by TrackingStateHolder.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = { TopAppBar(title = { Text("Local Strava") }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = onStartRun) { Text("Start a run") }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (tracking.isActive) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.fillMaxWidth().clickable { onStartRun() },
                ) {
                    Text(
                        "● Recording… tap to return",
                        Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            if (runs.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No runs yet — tap Start a run", textAlign = TextAlign.Center)
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(runs, key = { it.id }) { run -> RunRow(run) { onOpenRun(run.id) } }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RunRow(run: Activity, onClick: () -> Unit) {
    val pace = if (run.distanceMeters >= 10.0 && run.movingTimeMs > 0)
        Format.pace(run.movingTimeMs.toDouble() / run.distanceMeters) else "--"
    ListItem(
        headlineContent = { Text(dateFmt.format(Date(run.startedAtEpochMs))) },
        supportingContent = {
            Text("${Format.distance(run.distanceMeters)} · ${Format.duration(run.movingTimeMs)} · $pace")
        },
        modifier = Modifier.clickable { onClick() },
    )
    HorizontalDivider()
}
