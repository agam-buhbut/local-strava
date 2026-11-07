package com.localstrava.ui.log

import org.junit.Assert.assertEquals
import org.junit.Test

class LogStartTest {
    private val now = 1_762_500_000_000L
    private val tenMin = 10 * 60_000L

    // "When" left at now: the run just ended, so it started `duration` ago.
    @Test fun untouchedWhenEndsTheRunNow() = assertEquals(now - tenMin, logStartMs(null, now, tenMin))

    // A picked date/time is the start the user typed in; keep it as is.
    @Test fun pickedWhenIsTheStart() = assertEquals(now - 86_400_000L, logStartMs(now - 86_400_000L, now, tenMin))
}
