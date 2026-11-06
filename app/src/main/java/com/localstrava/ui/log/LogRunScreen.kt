package com.localstrava.ui.log

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.localstrava.format.Format
import com.localstrava.ui.theme.Numbers
import com.localstrava.ui.theme.RunColors
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Date
import java.util.Locale

private val dateFmt = SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault())

/** Mockup screen 5: the notebook — a run timed some other way (spec §4.3). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogRunScreen(onDone: () -> Unit, vm: LogRunViewModel = viewModel()) {
    var whenMs by rememberSaveable { mutableLongStateOf(System.currentTimeMillis()) }
    var distance by rememberSaveable { mutableStateOf("") }
    var hours by rememberSaveable { mutableStateOf("") }
    var minutes by rememberSaveable { mutableStateOf("") }
    var seconds by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    var pickingDate by remember { mutableStateOf(false) }
    var pickedDateUtc by remember { mutableStateOf<Long?>(null) } // non-null = time picker open
    var saving by remember { mutableStateOf(false) } // one tap, one row

    val distanceM = parseDistanceMeters(distance)
    val durationMs = parseDurationMs(hours, minutes, seconds)
    val valid = distanceM != null && durationMs != null

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Log a run", fontWeight = FontWeight.SemiBold) },
                navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.Filled.Close, contentDescription = "Close") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = RunColors.Ink),
            )
        },
        bottomBar = {
            Button(
                onClick = {
                    saving = true
                    vm.save(whenMs, distanceM!!, durationMs!!, note, onDone)
                },
                enabled = valid && !saving,
                modifier = Modifier
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .imePadding()
                    .padding(horizontal = 22.dp, vertical = 14.dp)
                    .fillMaxWidth()
                    .height(60.dp),
                shape = RoundedCornerShape(18.dp),
            ) { Text("Save run", fontSize = 18.sp, fontWeight = FontWeight.SemiBold) }
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp),
        ) {
            FieldLabel("When")
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .border(1.5.dp, RunColors.Line, RoundedCornerShape(14.dp))
                    .background(RunColors.Surface, RoundedCornerShape(14.dp))
                    .clickable { pickingDate = true }
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.CenterStart,
            ) { Text(dateFmt.format(Date(whenMs)), fontSize = 18.sp) }

            FieldLabel("Distance")
            NumberField(distance, { distance = it }, "km", KeyboardType.Decimal, Modifier.fillMaxWidth())

            FieldLabel("Time")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField(hours, { hours = it }, "h", KeyboardType.Number, Modifier.weight(1f))
                NumberField(minutes, { minutes = it }, "m", KeyboardType.Number, Modifier.weight(1f))
                NumberField(seconds, { seconds = it }, "s", KeyboardType.Number, Modifier.weight(1f))
            }
            if (valid) {
                Text(
                    buildAnnotatedString {
                        append("Pace ")
                        withStyle(SpanStyle(color = RunColors.Snow, fontWeight = FontWeight.SemiBold)) {
                            append(Format.avgPace(distanceM!!, durationMs!!))
                        }
                    },
                    Modifier.padding(top = 14.dp),
                    color = RunColors.Mist,
                    fontSize = 15.sp,
                )
            }

            FieldLabel("Note (optional)")
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                shape = RoundedCornerShape(14.dp),
                colors = fieldColors(),
            )
        }
    }

    if (pickingDate) {
        val zone = ZoneId.systemDefault()
        // The picker works in UTC midnights; seed it with the current local day.
        val initial = Instant.ofEpochMilli(whenMs).atZone(zone).toLocalDate()
            .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val state = rememberDatePickerState(initialSelectedDateMillis = initial)
        DatePickerDialog(
            onDismissRequest = { pickingDate = false },
            confirmButton = {
                TextButton(onClick = { pickingDate = false; pickedDateUtc = state.selectedDateMillis ?: initial }) {
                    Text("Next")
                }
            },
            dismissButton = { TextButton(onClick = { pickingDate = false }) { Text("Cancel") } },
        ) { DatePicker(state) }
    }

    pickedDateUtc?.let { dateUtc ->
        val zone = ZoneId.systemDefault()
        val now = Instant.ofEpochMilli(whenMs).atZone(zone)
        val state = rememberTimePickerState(
            initialHour = now.hour,
            initialMinute = now.minute,
            is24Hour = DateFormat.is24HourFormat(LocalContext.current),
        )
        AlertDialog(
            onDismissRequest = { pickedDateUtc = null },
            confirmButton = {
                TextButton(onClick = {
                    whenMs = loggedStartMs(dateUtc, state.hour, state.minute, zone)
                    pickedDateUtc = null
                }) { Text("Done") }
            },
            dismissButton = { TextButton(onClick = { pickedDateUtc = null }) { Text("Cancel") } },
            text = { TimePicker(state) },
        )
    }
}

@Composable
private fun FieldLabel(text: String) =
    Text(text, Modifier.padding(top = 18.dp, bottom = 6.dp), color = RunColors.Mist, fontSize = 14.sp)

@Composable
private fun NumberField(
    value: String,
    onChange: (String) -> Unit,
    unit: String,
    keyboard: KeyboardType,
    modifier: Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = modifier,
        singleLine = true,
        textStyle = Numbers.copy(fontSize = 28.sp, fontWeight = FontWeight.SemiBold, color = RunColors.Snow),
        suffix = { Text(unit, color = RunColors.Mist, fontSize = 16.sp) },
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        shape = RoundedCornerShape(14.dp),
        colors = fieldColors(),
    )
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = RunColors.Amber,
    unfocusedBorderColor = RunColors.Line,
    focusedContainerColor = RunColors.Surface,
    unfocusedContainerColor = RunColors.Surface,
    cursorColor = RunColors.Amber,
)
