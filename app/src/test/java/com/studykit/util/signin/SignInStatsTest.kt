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
    @Test
    fun weekRunsMondayToSundayAndStopsAtToday() {
        val summary = SignInStats.summary(
            listOf("2026-11-02", "2026-11-03", "2026-11-05", "2026-11-08"),
            today,
        )
        assertEquals(7, summary.weekDays.size)
        assertEquals(java.time.LocalDate.of(2026, 11, 2), summary.weekDays.first().date)
        assertEquals(java.time.LocalDate.of(2026, 11, 8), summary.weekDays.last().date)
        assertTrue(summary.weekDays[0].signed)
        assertTrue(summary.weekDays[1].signed)
        assertFalse("11-04 未签到", summary.weekDays[2].signed)
        assertTrue(summary.weekDays[3].signed)
        assertFalse("未来日期即使传入也不涂色", summary.weekDays[6].signed)
        assertEquals(3, summary.weekSignedCount)
    }

    @Test
    fun monthGridPadsLeadingBlanksAndAlwaysHasSixRows() {
        val today = java.time.LocalDate.of(2026, 11, 5)
        val signed = setOf(
            java.time.LocalDate.of(2026, 11, 5),
            java.time.LocalDate.of(2026, 11, 1),
        )
        val grid = SignInStats.monthGrid(java.time.YearMonth.of(2026, 11), signed, today)
        assertEquals(42, grid.cells.size)
        assertEquals(null, grid.cells[0].date)
        assertEquals(null, grid.cells[5].date)
        assertEquals(java.time.LocalDate.of(2026, 11, 1), grid.cells[6].date)
        assertTrue(grid.cells[6].signed)
        assertEquals(java.time.LocalDate.of(2026, 11, 5), grid.cells[10].date)
        assertTrue(grid.cells[10].signed)
        assertTrue(grid.cells[10].today)
        assertFalse(grid.cells[11].today)
    }

    @Test
    fun milestoneOnlyFiresOnExactStreak() {
        assertEquals(3, SignInStats.milestoneFor(3))
        assertEquals(7, SignInStats.milestoneFor(7))
        assertEquals(14, SignInStats.milestoneFor(14))
        assertEquals(30, SignInStats.milestoneFor(30))
        assertEquals(66, SignInStats.milestoneFor(66))
        assertEquals(null, SignInStats.milestoneFor(8))
        assertEquals(null, SignInStats.milestoneFor(0))
    }

}
