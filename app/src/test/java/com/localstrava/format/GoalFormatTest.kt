package com.localstrava.format

import com.localstrava.metrics.Goal
import org.junit.Assert.assertEquals
import org.junit.Test

class GoalFormatTest {
    @Test fun kmAlwaysTwoDecimals() = assertEquals("0.85", Format.km(850.0))
    @Test fun paceShortHasNoUnit() = assertEquals("5:30", Format.paceShort(330.0))
    @Test fun paceShortNullDashes() = assertEquals("--", Format.paceShort(null))
    @Test fun goalWholeKm() = assertEquals("5 km", Format.goal(Goal.Distance(5000.0)))
    @Test fun goalHalfKm() = assertEquals("5.5 km", Format.goal(Goal.Distance(5500.0)))
    @Test fun goalMinutes() = assertEquals("30 min", Format.goal(Goal.Time(1_800_000)))
    @Test fun goalWholeHour() = assertEquals("1 h", Format.goal(Goal.Time(3_600_000)))
    @Test fun goalHourAndMinutes() = assertEquals("1 h 30 min", Format.goal(Goal.Time(5_400_000)))
    @Test fun statusMetresLeft() =
        assertEquals("180 m until the 5 km bell", Format.goalStatus(Goal.Distance(5000.0), 4820.0, 0, reached = false))
    @Test fun statusKmLeft() =
        assertEquals("1.20 km until the 5 km bell", Format.goalStatus(Goal.Distance(5000.0), 3800.0, 0, reached = false))
    @Test fun statusMinutesRoundUp() = // 200 s left -> 4 min
        assertEquals("4 min until the 30 min bell", Format.goalStatus(Goal.Time(1_800_000), 0.0, 1_600_000, reached = false))
    @Test fun statusUnderAMinuteInSeconds() =
        assertEquals("45 s until the 30 min bell", Format.goalStatus(Goal.Time(1_800_000), 0.0, 1_755_000, reached = false))
    @Test fun statusAfterRing() =
        assertEquals("5 km bell rang", Format.goalStatus(Goal.Distance(5000.0), 5003.0, 0, reached = true))
}
