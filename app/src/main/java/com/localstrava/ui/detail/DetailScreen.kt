package com.localstrava.ui.detail

import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.localstrava.data.Activity
import com.localstrava.data.TrackPoint
import com.localstrava.format.Format
import com.localstrava.ui.theme.Numbers
import com.localstrava.ui.theme.RunColors
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.TilesOverlay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val dateFmt = SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault())

/** Mockup screen 4. Logged (notebook) runs have no track: no map, no climb/height/total time. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(activityId: Long, onBack: () -> Unit, vm: DetailViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(activityId) { vm.load(activityId) }
    val activity = state.activity

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        activity?.let { dateFmt.format(Date(it.startedAtEpochMs)) } ?: "",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete run")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = RunColors.Ink),
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (activity != null && !activity.logged) {
                Box(Modifier.weight(1f).heightIn(min = 240.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    when {
                        state.points.size >= 2 -> RouteMap(state.points)
                        !state.loading -> Text("No route recorded", color = RunColors.Mist)
                        else -> Unit
                    }
                }
            }
            activity?.let { a ->
                Stats(a, maxAltitudeM = state.points.mapNotNull { it.altitudeMeters }.maxOrNull())
                if (a.logged) {
                    a.note?.let { Text(it, Modifier.padding(horizontal = 22.dp, vertical = 8.dp), fontSize = 16.sp) }
                    Spacer(Modifier.weight(1f))
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
private fun Stats(a: Activity, maxAltitudeM: Double?) {
    Column(Modifier.padding(horizontal = 22.dp, vertical = 18.dp)) {
        Text("Distance", color = RunColors.Mist, fontSize = 14.sp)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(Format.km(a.distanceMeters), style = Numbers.copy(fontSize = 64.sp, fontWeight = FontWeight.SemiBold))
            Text("km", Modifier.padding(start = 4.dp, bottom = 9.dp), color = RunColors.Mist, fontSize = 18.sp)
        }
        val pace = "Pace" to Format.avgPace(a.distanceMeters, a.movingTimeMs)
        val cells = if (a.logged) {
            listOf("Time" to Format.duration(a.movingTimeMs), pace)
        } else {
            listOf(
                "Moving time" to Format.duration(a.movingTimeMs),
                pace,
                "Total time" to (a.endedAtEpochMs?.let { Format.duration(it - a.startedAtEpochMs) } ?: "--"),
                "Climb" to Format.elevation(a.elevationGainMeters),
                "Highest" to (maxAltitudeM?.let(Format::elevation) ?: "--"),
            )
        }
        cells.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(top = 18.dp)) {
                row.forEach { (label, value) ->
                    Column(Modifier.weight(1f)) {
                        Text(label, color = RunColors.Mist, fontSize = 14.sp)
                        Text(value, Modifier.padding(top = 4.dp), style = Numbers.copy(fontSize = 26.sp, fontWeight = FontWeight.SemiBold))
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun RouteMap(points: List<TrackPoint>) {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val ink = RunColors.Ink.toArgb()
    val amber = RunColors.Amber.toArgb()
    val density = ctx.resources.displayMetrics.density
    val mapView = remember {
        MapView(ctx).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            // Dark map: invert OSM tiles. osmdroid inverts the loading placeholder too (ink would
            // turn cream), so missing/offline tiles are drawn transparent over an ink view instead.
            overlayManager.tilesOverlay.setColorFilter(TilesOverlay.INVERT_COLORS)
            overlayManager.tilesOverlay.setLoadingBackgroundColor(Color.TRANSPARENT)
            overlayManager.tilesOverlay.setLoadingLineColor(Color.TRANSPARENT)
            setBackgroundColor(ink)
        }
    }
    var didFit by remember { mutableStateOf(false) }
    MapViewLifecycle(lifecycleOwner, mapView)
    AndroidView(
        // clipToBounds: osmdroid pans by scrolling its own canvas and can paint tiles outside its
        // bounds inside Compose, drawing over the stats row above (the "disappearing bar").
        modifier = Modifier.fillMaxSize().clipToBounds(),
        factory = { mapView },
        update = { map ->
            map.overlays.clear()
            fun line(seg: List<GeoPoint>, color: Int, widthDp: Float) = Polyline().apply {
                setPoints(seg)
                outlinePaint.color = color
                outlinePaint.strokeWidth = widthDp * density
                outlinePaint.strokeCap = Paint.Cap.ROUND
                outlinePaint.strokeJoin = Paint.Join.ROUND
            }
            // Split the polyline at segment breaks (pause/gap) so jumps aren't drawn. Each segment is
            // an ink casing under the amber route so it reads against any tile.
            var seg = ArrayList<GeoPoint>()
            fun flush() {
                map.overlays.add(line(seg, ink, 9f))
                map.overlays.add(line(seg, amber, 4.5f))
            }
            points.forEach { p ->
                if (p.segmentBreak && seg.isNotEmpty()) {
                    flush()
                    seg = ArrayList()
                }
                seg.add(GeoPoint(p.latitude, p.longitude))
            }
            if (seg.isNotEmpty()) flush()

            fun dot(at: TrackPoint, fill: Int) = Marker(map).apply {
                position = GeoPoint(at.latitude, at.longitude)
                icon = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(fill)
                    setStroke((3 * density).toInt(), ink)
                    val px = (16 * density).toInt()
                    setSize(px, px)
                }
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                setInfoWindow(null) // no default light bubble on tap
            }
            map.overlays.add(dot(points.first(), RunColors.Snow.toArgb()))
            map.overlays.add(dot(points.last(), amber))

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
private fun MapViewLifecycle(
    lifecycleOwner: LifecycleOwner,
    mapView: MapView,
) {
    DisposableEffect(lifecycleOwner) {
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
