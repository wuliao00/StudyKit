package com.studykit.ui.habit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 断签保护卡的月度额度语义：跨月自动回血、月内只减不增、用完即拒。
 *
 * 只测额度刷新与消耗判定，不重复测 HabitScience 已有的连续天数行为。
 */
class ProtectionQuotaTest {

    private fun quota(period: String, cards: Int = 2, used: Int = 0) =
        HabitScience.Quota(period = period, cards = cards, used = used)

    @Test
    fun `month key is zero padded yyyy dash MM`() {
        assertEquals("2026-09", HabitScience.monthKey(LocalDate.of(2026, 9, 29)))
        assertEquals("2026-01", HabitScience.monthKey(LocalDate.of(2026, 1, 5)))
        assertEquals("2027-12", HabitScience.monthKey(LocalDate.of(2027, 12, 31)))
    }

    @Test
    fun `quota resets when the month changes`() {
        val stale = quota(period = "2026-08", used = 2)
        val refreshed = HabitScience.refreshQuota(stale, today = LocalDate.of(2026, 9, 1))
        assertEquals("2026-09", refreshed.period)
        assertEquals(0, refreshed.used)
        assertEquals(HabitScience.MONTHLY_PROTECTION_CARDS, refreshed.cards)
        assertEquals(2, refreshed.left)
    }

    @Test
    fun `quota stays untouched within the same month`() {
        val current = quota(period = "2026-09", used = 2)
        val refreshed = HabitScience.refreshQuota(current, today = LocalDate.of(2026, 9, 28))
        assertEquals(current, refreshed)
        assertEquals(0, refreshed.left)
    }

    @Test
    fun `first ever record gets a full month quota`() {
        val fresh = HabitScience.refreshQuota(quota(period = ""), today = LocalDate.of(2026, 9, 29))
        assertEquals("2026-09", fresh.period)
        assertEquals(HabitScience.MONTHLY_PROTECTION_CARDS, fresh.left)
    }

    @Test
    fun `spending a card decrements the balance`() {
        val before = quota(period = "2026-09", used = 0)
        val after = HabitScience.spendCard(before)
        assertEquals(1, after.used)
        assertEquals(1, after.left)
    }

    @Test
    fun `cannot spend the last card twice`() {
        val almostOut = quota(period = "2026-09", used = 1)
        assertTrue(almostOut.left > 0)
        val out = HabitScience.spendCard(almostOut)
        assertEquals(0, out.left)
        assertFalse("余额为 0 时不能再消耗", HabitScience.canSpend(out))
    }

    @Test
    fun `spending never goes negative`() {
        val exhausted = quota(period = "2026-09", used = 2)
        assertEquals(0, exhausted.left)
        assertEquals(0, HabitScience.spendCard(exhausted).left)
    }
}
