package com.localstrava.ui.log

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/** Kilometres as typed (either decimal mark, e.g. Samsung's "," keyboard) → metres; null if unusable. */
fun parseDistanceMeters(text: String): Double? =
    text.trim().replace(',', '.').toDoubleOrNull()
        ?.takeIf { it > 0.0 && it <= MAX_KM }
        ?.let { it * 1000.0 }

/** h / m / s fields → total ms; blank counts as 0. Null if any field is out of range or the total is 0. */
fun parseDurationMs(hours: String, minutes: String, seconds: String): Long? {
    fun field(s: String, max: Long): Long? =
        if (s.isBlank()) 0L else s.trim().toLongOrNull()?.takeIf { it in 0..max }
    val h = field(hours, 99) ?: return null
    val m = field(minutes, 59) ?: return null
    val sec = field(seconds, 59) ?: return null
    return ((h * 3600 + m * 60 + sec) * 1000).takeIf { it > 0 }
}

/**
 * Material 3's date picker hands back midnight UTC of the picked day. Read it as a UTC date, then
 * pin the picked local time in [zone] — adding hours to the UTC instant would shift the run by the
 * zone offset (and onto the wrong day west of UTC).
 */
fun loggedStartMs(utcDateMs: Long, hour: Int, minute: Int, zone: ZoneId): Long =
    Instant.ofEpochMilli(utcDateMs).atZone(ZoneOffset.UTC).toLocalDate()
        .atTime(LocalTime.of(hour, minute))
        .atZone(zone)
        .toInstant()
        .toEpochMilli()

/** No time picked means "I just finished": the run started [durationMs] before [nowMs]. */
fun logStartMs(pickedStartMs: Long?, nowMs: Long, durationMs: Long): Long = pickedStartMs ?: (nowMs - durationMs)

private const val MAX_KM = 1000.0
