package com.localstrava.ui.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

class LogRunInputTest {
    @Test fun distanceDot() = assertEquals(8250.0, parseDistanceMeters("8.25")!!, 1e-9)
    @Test fun distanceComma() = assertEquals(8250.0, parseDistanceMeters("8,25")!!, 1e-9)
    @Test fun distanceTrims() = assertEquals(5000.0, parseDistanceMeters(" 5 ")!!, 1e-9)
    @Test fun distanceBlank() = assertNull(parseDistanceMeters(""))
    @Test fun distanceZero() = assertNull(parseDistanceMeters("0"))
    @Test fun distanceNegative() = assertNull(parseDistanceMeters("-3"))
    @Test fun distanceGarbage() = assertNull(parseDistanceMeters("abc"))
    @Test fun distanceOver1000Km() = assertNull(parseDistanceMeters("1000.01"))
    @Test fun durationBlankHoursIsZero() = assertEquals(2_650_000L, parseDurationMs("", "44", "10"))
    @Test fun durationWithHours() = assertEquals(4_390_000L, parseDurationMs("1", "13", "10"))
    @Test fun durationAllZero() = assertNull(parseDurationMs("0", "0", "0"))
    @Test fun durationMinutes60() = assertNull(parseDurationMs("0", "60", "0"))
    @Test fun durationSeconds60() = assertNull(parseDurationMs("0", "1", "60"))
    @Test fun durationNegative() = assertNull(parseDurationMs("-1", "0", "0"))

    private val nov3Utc = LocalDate.of(2026, 11, 3).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    private fun local(zone: String) =
        ZonedDateTime.of(2026, 11, 3, 18, 30, 0, 0, ZoneId.of(zone)).toInstant().toEpochMilli()
    @Test fun startEastOfUtc() =
        assertEquals(local("Asia/Jerusalem"), loggedStartMs(nov3Utc, 18, 30, ZoneId.of("Asia/Jerusalem")))
    @Test fun startWestOfUtcKeepsTheDay() =
        assertEquals(local("America/Los_Angeles"), loggedStartMs(nov3Utc, 18, 30, ZoneId.of("America/Los_Angeles")))
}
