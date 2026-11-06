package com.localstrava.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoggedActivityTest {
    private val a = loggedActivity(1_000L, 8000.0, 2_650_000L, "Track, 8 × 400 m")
    @Test fun endsAtStartPlusDuration() = assertEquals(2_651_000L, a.endedAtEpochMs)
    @Test fun movingTimeIsDuration() = assertEquals(2_650_000L, a.movingTimeMs)
    @Test fun markedLoggedWithNoClimb() { assertTrue(a.logged); assertEquals(0.0, a.elevationGainMeters, 0.0) }
    @Test fun newRowHasNoId() = assertEquals(0L, a.id)
    @Test fun noteIsTrimmed() = assertEquals("Track", loggedActivity(0, 1000.0, 1000, "  Track ").note)
    @Test fun blankNoteBecomesNull() = assertNull(loggedActivity(0, 1000.0, 1000, "   ").note)
}
