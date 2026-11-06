package com.localstrava.ui.record

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.localstrava.format.Format
import com.localstrava.metrics.progress
import com.localstrava.tracking.TrackPhase
import com.localstrava.tracking.TrackingUiState
import com.localstrava.ui.theme.Numbers
import com.localstrava.ui.theme.RunColors
import kotlinx.coroutines.launch

/** Mockup screen 2. Also what shows over the lock screen mid-run. No endless animations (battery). */
@Composable
internal fun RunningContent(
    state: TrackingUiState,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val paused = state.phase == TrackPhase.Paused
    Column(modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 8.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(top = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(9.dp).clip(CircleShape).background(RunColors.Amber))
                Text(
                    if (paused) "Paused" else "Recording",
                    Modifier.padding(start = 7.dp),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
            Text(
                if (state.locationLost) "GPS lost" else "GPS on",
                color = if (state.locationLost) RunColors.Amber else RunColors.Mist,
                fontSize = 14.sp,
            )
        }
        if (state.locationLost) {
            Text("Turn location back on to keep recording.", color = RunColors.Mist, fontSize = 13.5.sp)
        }

        Label("Distance", Modifier.padding(top = 30.dp))
        val km = Format.km(state.distanceMeters)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                km,
                // 6+ chars ("100.00") would overflow a ~340 dp column at the full size.
                style = Numbers.copy(fontSize = if (km.length >= 6) 100.sp else 128.sp, fontWeight = FontWeight.Bold),
                maxLines = 1,
                softWrap = false,
            )
            Text("km", Modifier.padding(start = 8.dp, bottom = 18.dp), color = RunColors.Mist, fontSize = 24.sp)
        }
        state.goal?.let { goal ->
            val p = goal.progress(state.distanceMeters, state.movingTimeMs)
            Box(
                Modifier.padding(top = 14.dp).fillMaxWidth().height(6.dp)
                    .clip(RoundedCornerShape(3.dp)).background(RunColors.Surface),
            ) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(p).background(RunColors.Amber))
            }
            Label(
                Format.goalStatus(goal, state.distanceMeters, state.movingTimeMs, state.goalReached),
                Modifier.padding(top = 8.dp),
            )
        }

        Label("Time", Modifier.padding(top = 28.dp))
        Text(
            Format.duration(state.movingTimeMs),
            style = Numbers.copy(fontSize = 76.sp, fontWeight = FontWeight.SemiBold),
            maxLines = 1,
        )

        Row(Modifier.fillMaxWidth().padding(top = 28.dp)) {
            SmallStat("Pace now", Format.paceShort(state.currentPaceSecPerKm), Modifier.weight(1f))
            SmallStat("Average", Format.paceShort(state.avgPaceSecPerKm), Modifier.weight(1f))
            SmallStat("Climb", Format.elevation(state.elevationGainMeters), Modifier.weight(1f))
        }

        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val buttonMod = Modifier.weight(1f).height(60.dp)
            if (paused) {
                Button(onClick = onResume, modifier = buttonMod, shape = RoundedCornerShape(18.dp)) {
                    Text("Resume", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                }
            } else {
                OutlinedButton(
                    onClick = onPause,
                    modifier = buttonMod,
                    shape = RoundedCornerShape(18.dp),
                    border = BorderStroke(2.dp, RunColors.Line),
                ) { Text("Pause", color = RunColors.Snow, fontSize = 18.sp, fontWeight = FontWeight.SemiBold) }
            }
            HoldToStopButton(onStop, Modifier.weight(1.3f))
        }
    }
}

@Composable
private fun Label(text: String, modifier: Modifier = Modifier) =
    Text(text, modifier, color = RunColors.Mist, fontSize = 14.sp)

@Composable
private fun SmallStat(label: String, value: String, modifier: Modifier) {
    Column(modifier) {
        Label(label)
        Text(value, Modifier.padding(top = 4.dp), style = Numbers.copy(fontSize = 32.sp, fontWeight = FontWeight.SemiBold))
    }
}

/**
 * Stop needs a [HOLD_MS] press so a pocket touch can't end the run (spec §4.5). Letting go early
 * snaps back with no stop. TalkBack gets a plain click action instead of the hold.
 */
@Composable
internal fun HoldToStopButton(onStop: () -> Unit, modifier: Modifier = Modifier) {
    val fill = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val stop by rememberUpdatedState(onStop)
    val holding by remember { derivedStateOf { fill.value > 0f } }
    Box(
        modifier
            .height(60.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(RunColors.Surface)
            .drawBehind { drawRect(RunColors.Amber, size = size.copy(width = size.width * fill.value)) }
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    val hold = scope.launch {
                        fill.animateTo(1f, tween(HOLD_MS, easing = LinearEasing))
                        stop()
                    }
                    tryAwaitRelease()
                    if (fill.value < 1f) {
                        hold.cancel()
                        fill.snapTo(0f)
                    }
                })
            }
            .semantics {
                role = Role.Button
                onClick(label = "Stop run") { stop(); true }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "Hold to stop",
            color = if (holding) RunColors.Ink else RunColors.Snow,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

private const val HOLD_MS = 1_200
