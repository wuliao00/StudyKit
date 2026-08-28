package com.studykit.ui.habit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 习惯模块纯逻辑单元测试：连续打卡计算、补卡窗口、图标映射、数量格式化。
 * 全部为纯 Kotlin 函数，不依赖 Android 框架。
 */
class HabitLogicTest {

    // ── habitStreak：连续打卡天数 ────────────────────────────────────────

    @Test
    fun `streak is zero when no check-ins`() {
        val today = LocalDate.of(2026, 8, 28)
        assertEquals(0, habitStreak(emptySet(), today))
    }

    @Test
    fun `streak counts back from today when today is checked in`() {
        val today = LocalDate.of(2026, 8, 28)
        val dates = setOf(
            today, today.minusDays(1), today.minusDays(2), today.minusDays(3),
        )
        assertEquals(4, habitStreak(dates, today))
    }

    @Test
    fun `streak counts back from yesterday when today is missing`() {
        val today = LocalDate.of(2026, 8, 28)
        val dates = setOf(today.minusDays(1), today.minusDays(2))
        assertEquals(2, habitStreak(dates, today))
    }

    @Test
    fun `streak stops at gap`() {
        val today = LocalDate.of(2026, 8, 28)
        val dates = setOf(
            today, today.minusDays(1),
            // 中间断签：today-2 缺席
            today.minusDays(3), today.minusDays(4),
        )
        assertEquals(2, habitStreak(dates, today))
    }

    @Test
    fun `streak is zero when only old dates exist`() {
        val today = LocalDate.of(2026, 8, 28)
        val dates = setOf(today.minusDays(2), today.minusDays(5))
        assertEquals(0, habitStreak(dates, today))
    }

    // ── canMakeUp：补卡窗口（过去 7 天内，不含今天与未来） ───────────────

    @Test
    fun `makeup rejected for today and future`() {
        val today = LocalDate.of(2026, 8, 28)
        assertFalse(canMakeUp(today, today))
        assertFalse(canMakeUp(today.plusDays(1), today))
    }

    @Test
    fun `makeup allowed within 7 days`() {
        val today = LocalDate.of(2026, 8, 28)
        assertTrue(canMakeUp(today.minusDays(1), today))
        assertTrue(canMakeUp(today.minusDays(7), today))
    }

    @Test
    fun `makeup rejected beyond 7 days`() {
        val today = LocalDate.of(2026, 8, 28)
        assertFalse(canMakeUp(today.minusDays(8), today))
        assertFalse(canMakeUp(today.minusDays(30), today))
    }

    // ── habitIconEmoji：旧图标键 → emoji ────────────────────────────────

    @Test
    fun `legacy icon keys map to emoji`() {
        assertEquals("📖", habitIconEmoji("book"))
        assertEquals("🏃", habitIconEmoji("run"))
        assertEquals("📚", habitIconEmoji("read"))
    }

    @Test
    fun `existing emoji and blank fall back correctly`() {
        assertEquals("💧", habitIconEmoji("💧"))
        assertEquals("🎯", habitIconEmoji(""))
    }

    // ── formatAmount：数量格式化 ────────────────────────────────────────

    @Test
    fun `integer amounts have no decimal point`() {
        assertEquals("500", formatAmount(500.0))
        assertEquals("0", formatAmount(0.0))
    }

    @Test
    fun `fractional amounts keep one decimal`() {
        assertEquals("0.5", formatAmount(0.5))
        assertEquals("2.8", formatAmount(2.75))
    }
}
