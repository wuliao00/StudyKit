package com.studykit.util.signin

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SignInStatsTest {

    private val today = LocalDate.of(2026, 11, 5)

    @Test
    fun emptyDaysHaveNoStreakAndFullWindow() {
        val summary = SignInStats.summary(emptyList(), today)
        assertFalse(summary.signedToday)
        assertEquals(0, summary.currentStreak)
        assertEquals(0, summary.longestStreak)
        assertEquals(0, summary.totalDays)
        assertEquals(SignInStats.WINDOW_DAYS, summary.last14.size)
        assertTrue(summary.last14.none { it.signed })
        assertEquals(today, summary.last14.last().date)
    }

    @Test
    fun streakEndingYesterdayIsKeptBeforeTodaySignIn() {
        val summary = SignInStats.summary(
            listOf("2026-11-02", "2026-11-03", "2026-11-04"),
            today,
        )
        assertFalse(summary.signedToday)
        assertEquals(3, summary.currentStreak)
        assertEquals(3, summary.longestStreak)
        assertEquals(3, summary.totalDays)
    }

    @Test
    fun signingTodayExtendsTheStreak() {
        val summary = SignInStats.summary(
            listOf("2026-11-03", "2026-11-04", "2026-11-05"),
            today,
        )
        assertTrue(summary.signedToday)
        assertEquals(3, summary.currentStreak)
        assertEquals(3, summary.longestStreak)
        assertEquals(3, summary.totalDays)
        assertTrue(summary.last14.last().signed)
    }

    @Test
    fun longestStreakSurvivesGaps() {
        val summary = SignInStats.summary(
            listOf(
                "2026-10-01", "2026-10-02", "2026-10-03",
                "2026-10-10", "2026-10-11",
                "2026-11-05",
            ),
            today,
        )
        assertEquals(3, summary.longestStreak)
        assertEquals(1, summary.currentStreak)
        assertEquals(6, summary.totalDays)
    }

    @Test
    fun futureAndGarbageDatesAreIgnored() {
        val summary = SignInStats.summary(
            listOf("2026-11-06", "not-a-date", "2026-11-04", "2026-11-05"),
            today,
        )
        assertEquals(2, summary.totalDays)
        assertTrue(summary.signedToday)
        assertEquals(2, summary.currentStreak)
    }
}
