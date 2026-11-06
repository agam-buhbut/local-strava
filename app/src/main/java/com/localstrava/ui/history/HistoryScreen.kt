package com.localstrava.ui.history

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.localstrava.data.Activity
import com.localstrava.format.Format
import com.localstrava.tracking.TrackingStateHolder
import com.localstrava.ui.theme.Numbers
import com.localstrava.ui.theme.RunColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val dateFmt = SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault())

/** Mockup screen 3: recorded and logged runs, newest first. */
@Composable
fun HistoryScreen(
    onStartRun: () -> Unit,
    onLogRun: () -> Unit,
    onOpenRun: (Long) -> Unit,
    vm: HistoryViewModel = viewModel(),
) {
    val runs by vm.runs.collectAsStateWithLifecycle()
    val tracking by TrackingStateHolder.state.collectAsStateWithLifecycle()

    Scaffold(
        bottomBar = {
            Row(
                Modifier
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(horizontal = 22.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = onLogRun,
                    modifier = Modifier.weight(1f).height(60.dp),
                    shape = RoundedCornerShape(18.dp),
                    border = BorderStroke(2.dp, RunColors.Line),
                ) { Text("Log a run", color = RunColors.Snow, fontSize = 18.sp, fontWeight = FontWeight.SemiBold) }
                Button(
                    onClick = onStartRun,
                    modifier = Modifier.weight(1.4f).height(60.dp),
                    shape = RoundedCornerShape(18.dp),
                ) { Text("Start run", fontSize = 18.sp, fontWeight = FontWeight.SemiBold) }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Text(
                "Runs",
                Modifier.padding(start = 22.dp, top = 18.dp, bottom = 14.dp),
                style = Numbers.copy(fontSize = 34.sp, fontWeight = FontWeight.SemiBold),
            )
            if (tracking.isActive) {
                Surface(
                    color = RunColors.Surface,
                    modifier = Modifier.fillMaxWidth().clickable { onStartRun() },
                ) {
                    Text("● Recording… tap to return", Modifier.padding(horizontal = 22.dp, vertical = 16.dp))
                }
            }
            if (runs.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "No runs yet. Start one, or log a run you timed another way.",
                        color = RunColors.Mist,
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                HorizontalDivider(color = RunColors.Line)
                LazyColumn(Modifier.fillMaxSize()) {
                    items(runs, key = { it.id }) { run -> RunRow(run) { onOpenRun(run.id) } }
                }
            }
        }
    }
}

@Composable
private fun RunRow(run: Activity, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable { onClick() }.padding(horizontal = 22.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(dateFmt.format(Date(run.startedAtEpochMs)), color = RunColors.Mist, fontSize = 14.sp)
            if (run.logged) {
                Text(
                    "Logged",
                    Modifier
                        .padding(start = 8.dp)
                        .background(RunColors.Snow, RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    color = RunColors.Ink,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.Bottom) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.Bottom) {
                Text(Format.km(run.distanceMeters), style = Numbers.copy(fontSize = 36.sp, fontWeight = FontWeight.SemiBold))
                Text("km", Modifier.padding(start = 3.dp, bottom = 5.dp), color = RunColors.Mist, fontSize = 16.sp)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(Format.duration(run.movingTimeMs), style = Numbers.copy(fontSize = 17.sp))
                Text(Format.avgPace(run.distanceMeters, run.movingTimeMs), color = RunColors.Mist, fontSize = 15.sp)
            }
        }
        run.note?.let {
            Text(it, Modifier.padding(top = 4.dp), color = RunColors.Mist, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
    HorizontalDivider(color = RunColors.Line)
}
