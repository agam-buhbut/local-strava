package com.localstrava.ui.record

import android.content.SharedPreferences
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import com.localstrava.format.Format
import com.localstrava.metrics.Goal
import com.localstrava.metrics.stepped
import com.localstrava.ui.theme.Barlow
import com.localstrava.ui.theme.Numbers
import com.localstrava.ui.theme.RunColors

/** Mockup screen 1: pick the bell goal, then start. */
@Composable
internal fun ReadyContent(
    prefs: SharedPreferences,
    showPermissionHint: Boolean,
    showSamsungTip: Boolean,
    onOpenSettings: () -> Unit,
    onStart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var kind by remember { mutableStateOf(prefs.getString(KEY_GOAL_KIND, KIND_OFF) ?: KIND_OFF) }
    var distance by remember { mutableStateOf(prefs.distanceGoal()) }
    var time by remember { mutableStateOf(prefs.timeGoal()) }

    fun save() = prefs.edit {
        putString(KEY_GOAL_KIND, kind)
        putFloat(KEY_GOAL_DISTANCE_M, distance.meters.toFloat())
        putLong(KEY_GOAL_TIME_MS, time.millis)
    }

    Column(modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 8.dp)) {
        Text(
            "Ready to run",
            Modifier.padding(top = 18.dp),
            style = Numbers.copy(fontSize = 34.sp, fontWeight = FontWeight.SemiBold),
        )
        Text("Ring me at", Modifier.padding(top = 40.dp), color = RunColors.Mist, fontSize = 14.sp)
        KindToggle(kind) { kind = it; save() }
        if (kind != KIND_OFF) {
            val goal: Goal = if (kind == KIND_DISTANCE) distance else time
            Row(
                Modifier.fillMaxWidth().padding(top = 26.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StepButton("−", "Less") {
                    if (kind == KIND_DISTANCE) distance = distance.stepped(-1) else time = time.stepped(-1)
                    save()
                }
                GoalValue(Format.goal(goal))
                StepButton("+", "More") {
                    if (kind == KIND_DISTANCE) distance = distance.stepped(+1) else time = time.stepped(+1)
                    save()
                }
            }
            Text(
                "Rings and vibrates once, even in your pocket.",
                Modifier.fillMaxWidth().padding(top = 22.dp),
                color = RunColors.Mist,
                fontSize = 13.5.sp,
                textAlign = TextAlign.Center,
            )
        }
        if (showPermissionHint) {
            Surface(
                color = RunColors.Surface,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
            ) {
                Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Location permission is required to record a run.", textAlign = TextAlign.Center)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = onOpenSettings) { Text("Open settings") }
                }
            }
        }
        Spacer(Modifier.weight(1f))
        if (showSamsungTip) {
            Text(
                "Samsung: also add this app to Settings › Battery › " +
                    "Background usage limits › Never sleeping apps.",
                Modifier.padding(bottom = 16.dp),
                color = RunColors.Mist,
                fontSize = 13.5.sp,
                lineHeight = 19.sp,
            )
        }
        Button(
            onClick = onStart,
            modifier = Modifier.fillMaxWidth().height(60.dp),
            shape = RoundedCornerShape(18.dp),
        ) { Text("Start run", fontSize = 18.sp, fontWeight = FontWeight.SemiBold) }
    }
}

@Composable
private fun KindToggle(selected: String, onSelect: (String) -> Unit) {
    Row(
        Modifier
            .padding(top = 12.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(RunColors.Surface)
            .padding(4.dp)
            .selectableGroup(),
    ) {
        listOf(KIND_OFF to "Off", KIND_DISTANCE to "Distance", KIND_TIME to "Time").forEach { (key, label) ->
            val on = key == selected
            Box(
                Modifier
                    .weight(1f)
                    .height(42.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(if (on) RunColors.Snow else RunColors.Surface)
                    .selectable(selected = on, role = Role.RadioButton) { onSelect(key) },
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = if (on) RunColors.Ink else RunColors.Mist, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun StepButton(symbol: String, description: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.size(56.dp).semantics { contentDescription = description },
        shape = CircleShape,
        border = BorderStroke(2.dp, RunColors.Line),
        contentPadding = PaddingValues(0.dp),
    ) { Text(symbol, color = RunColors.Snow, fontSize = 28.sp) }
}

/** "5.5 km" / "1 h 30 min": numbers big, units small in mist. */
@Composable
private fun GoalValue(text: String) {
    val styled = buildAnnotatedString {
        text.split(" ").forEachIndexed { i, part ->
            if (i > 0) append(" ")
            if (part.first().isDigit()) append(part)
            else withStyle(SpanStyle(fontFamily = Barlow, fontSize = 22.sp, fontWeight = FontWeight.Medium, color = RunColors.Mist)) {
                append(part)
            }
        }
    }
    Text(styled, style = Numbers.copy(fontSize = 72.sp, fontWeight = FontWeight.SemiBold, color = RunColors.Snow))
}

/** The goal the next run starts with; null when the bell is off. Read at start time by RecordScreen. */
internal fun goalFromPrefs(prefs: SharedPreferences): Goal? = when (prefs.getString(KEY_GOAL_KIND, KIND_OFF)) {
    KIND_DISTANCE -> prefs.distanceGoal()
    KIND_TIME -> prefs.timeGoal()
    else -> null
}

// SharedPreferences has no double; 500 m steps are exact in a float.
private fun SharedPreferences.distanceGoal() = Goal.Distance(getFloat(KEY_GOAL_DISTANCE_M, 5000f).toDouble())
private fun SharedPreferences.timeGoal() = Goal.Time(getLong(KEY_GOAL_TIME_MS, 1_800_000L))

private const val KEY_GOAL_KIND = "goal_kind"
private const val KEY_GOAL_DISTANCE_M = "goal_distance_m"
private const val KEY_GOAL_TIME_MS = "goal_time_ms"
private const val KIND_OFF = "off"
private const val KIND_DISTANCE = "distance"
private const val KIND_TIME = "time"
