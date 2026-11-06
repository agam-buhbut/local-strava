package com.localstrava.metrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoalTest {
    @Test fun distanceReachedAtExactlyGoal() = assertTrue(Goal.Distance(5000.0).isReached(5000.0, 0))
    @Test fun distanceNotReachedJustShort() = assertFalse(Goal.Distance(5000.0).isReached(4999.9, 99_999_999))
    @Test fun timeUsesMovingTimeNotDistance() = assertFalse(Goal.Time(1_800_000).isReached(50_000.0, 1_799_999))
    @Test fun timeReachedAtExactlyGoal() = assertTrue(Goal.Time(1_800_000).isReached(0.0, 1_800_000))
    @Test fun progressHalfway() = assertEquals(0.5f, Goal.Time(600_000).progress(0.0, 300_000), 1e-6f)
    @Test fun progressClampsToOne() = assertEquals(1f, Goal.Distance(1000.0).progress(1500.0, 0), 0f)
    @Test fun distanceStepsByHalfKm() = assertEquals(Goal.Distance(5500.0), Goal.Distance(5000.0).stepped(+1))
    @Test fun distanceClampsAtMin() = assertEquals(Goal.Distance(500.0), Goal.Distance(500.0).stepped(-1))
    @Test fun distanceClampsAtMax() = assertEquals(Goal.Distance(100_000.0), Goal.Distance(100_000.0).stepped(+1))
    @Test fun timeStepsByFiveMin() = assertEquals(Goal.Time(2_100_000), Goal.Time(1_800_000).stepped(+1))
    @Test fun timeClampsAtMin() = assertEquals(Goal.Time(300_000), Goal.Time(300_000).stepped(-1))
    @Test fun timeClampsAtMax() = assertEquals(Goal.Time(36_000_000), Goal.Time(36_000_000).stepped(+1))
}
