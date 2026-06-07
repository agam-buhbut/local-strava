package com.localstrava.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.localstrava.data.TrackPoint
import com.localstrava.format.Format
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(activityId: Long, onBack: () -> Unit, vm: DetailViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(activityId) { vm.load(activityId) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Run") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
                actions = { TextButton(onClick = { confirmDelete = true }) { Text("Delete") } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            state.activity?.let { a ->
                StatsRow(
                    a.distanceMeters, a.movingTimeMs, a.elevationGainMeters,
                    (a.endedAtEpochMs ?: a.startedAtEpochMs) - a.startedAtEpochMs,
                )
            }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                when {
                    state.points.size >= 2 -> RouteMap(state.points)
                    !state.loading -> Text("No route recorded")
                    else -> Unit
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this run?") },
            text = { Text("This can't be undone.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; vm.delete(activityId, onBack) }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun StatsRow(distanceM: Double, movingMs: Long, elevM: Double, elapsedMs: Long) {
    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Stat("Distance", Format.distance(distanceM))
        Stat("Moving", Format.duration(movingMs))
        Stat("Pace", if (distanceM >= 10.0) Format.pace(movingMs.toDouble() / distanceM) else "--")
        Stat("Elev", Format.elevation(elevM))
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall)
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun RouteMap(points: List<TrackPoint>) {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mapView = remember {
        MapView(ctx).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
        }
    }
    var didFit by remember { mutableStateOf(false) }
    DisposableEffectMapLifecycle(lifecycleOwner, mapView)
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { mapView },
        update = { map ->
            map.overlays.clear()
            // Split the polyline at segment breaks (pause/gap) so jumps aren't drawn.
            var seg = ArrayList<GeoPoint>()
            points.forEach { p ->
                if (p.segmentBreak && seg.isNotEmpty()) {
                    map.overlays.add(Polyline().apply { setPoints(seg) })
                    seg = ArrayList()
                }
                seg.add(GeoPoint(p.latitude, p.longitude))
            }
            if (seg.isNotEmpty()) map.overlays.add(Polyline().apply { setPoints(seg) })

            val first = points.first(); val last = points.last()
            map.overlays.add(Marker(map).apply { position = GeoPoint(first.latitude, first.longitude); title = "Start" })
            map.overlays.add(Marker(map).apply { position = GeoPoint(last.latitude, last.longitude); title = "End" })

            if (!didFit) {
                didFit = true
                map.post {
                    val box = BoundingBox.fromGeoPoints(points.map { GeoPoint(it.latitude, it.longitude) })
                    map.zoomToBoundingBox(box.increaseByScale(1.3f), false, 48)
                }
            }
            map.invalidate()
        },
    )
}

@Composable
private fun DisposableEffectMapLifecycle(
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    mapView: MapView,
) {
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onResume()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(obs)
            mapView.onDetach()
        }
    }
}
