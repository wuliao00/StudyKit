package com.studykit.ui.habit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 断签保护的**展示层派生**纯逻辑（计划 B Task 17 / spec D6）。
 *
 * 与 [HabitGuard]（Plan A 的额度判定）互补：这里只吃「整月逐日打卡布尔串」，
 * 断言两件研究口径要求的事——
 *  - 连续数跨受保护缺卡日**延续**（漏的这 1~2 天不但不归零，还被算进这一串里）；
 *  - 一月第 3 次缺卡（`breaksAt` 为真那一格）让这一串**归零**。
 * [HabitGuardTest] 已钉 `guardedIndices` / `breaksAt` / `MISSING_GUARD_TEXT`，这里再钉一次
 * 气泡文案常量与展示派生，措辞改一个字就红。
 *
 * 全为纯 Kotlin，不碰时钟、不碰 Android。
 */
class HabitGuardDisplayTest {

    // ── streakThrough：连续数跨保护日延续 ──────────────────────────────

    @Test fun `连续数跨受保护缺卡日延续`() {
        // 两次缺卡（下标 2、4）都落在月内前 2 次额度里 ⇒ 整串不断，6 天全算进去
        assertEquals(6, streakThrough(listOf(true, true, false, true, false, true)))
    }

    @Test fun `月末全打卡时连续数即整串`() {
        assertEquals(4, streakThrough(listOf(true, true, true, true)))
    }

    @Test fun `第三次缺卡让连续数归零`() {
        // 三次缺卡（下标 2、3、4）：前两次受保护，最后一次（就在串尾）已破链 ⇒ 尾巴为 0
        assertEquals(0, streakThrough(listOf(true, true, false, false, false)))
    }

    @Test fun `破链缺卡之前的历史不计，只数尾部未断的一段`() {
        // 下标 4 是第三次缺卡（前 2、3 受保护）⇒ 从尾部走到 4 就断，只留最后一格
        assertEquals(1, streakThrough(listOf(true, false, false, true, false, true)))
    }

    @Test fun `空串连续数为零`() {
        assertEquals(0, streakThrough(emptyList()))
    }

    // ── gapDayHintEligible：打卡界面「昨日缺卡且额度未用尽」才温和提示 ──

    @Test fun `昨日缺卡且仍在保护额度内时值得提示`() {
        val today = LocalDate.of(2026, 8, 20)
        // 8/1..8/18 全打、8/19（昨日）缺 ⇒ 本月第一次缺，未用尽额度
        val dates = datedRange(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 18)).toSet()
        assertTrue(gapDayHintEligible(dates = dates, today = today))
    }

    @Test fun `今天已打卡不再提示`() {
        val today = LocalDate.of(2026, 8, 20)
        val dates = datedRange(LocalDate.of(2026, 8, 1), today).toSet()
        assertFalse(gapDayHintEligible(dates = dates, today = today))
    }

    @Test fun `昨日没缺不提示`() {
        val today = LocalDate.of(2026, 8, 20)
        val dates = datedRange(LocalDate.of(2026, 8, 1), today.minusDays(1)).toSet()
        assertFalse(gapDayHintEligible(dates = dates, today = today))
    }

    @Test fun `本月保护额度已用尽时不再温和提示`() {
        val today = LocalDate.of(2026, 8, 20)
        // 8/17、8/18 已缺（用尽 2 次额度），8/19（昨日）是第三次缺 ⇒ 已破链，不该再说「别有负担」
        val dates = datedRange(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 16)).toSet()
        assertFalse(gapDayHintEligible(dates = dates, today = today))
    }

    // ── monthMissPattern：整月逐日布尔串（展示灰圈/派生的输入）──────────

    @Test fun `整月布尔串按当月天数逐日给出打卡与否`() {
        val month = java.time.YearMonth.of(2026, 2) // 28 天
        val checked = setOf(month.atDay(1), month.atDay(2), month.atDay(5))
        val pattern = monthMissPattern(checkedDates = checked, month = month)
        assertEquals(28, pattern.size)
        assertTrue(pattern[0]); assertTrue(pattern[1])
        assertFalse(pattern[2]); assertFalse(pattern[3]); assertTrue(pattern[4])
    }

    // ── 气泡文案常量钉死（措辞改动即红；与 HabitGuardTest 同一条纪律的展示侧复读）──

    @Test fun `气泡文案常量钉死为断签保护口径`() {
        assertEquals("未打卡（已用断签保护）", HabitGuard.MISSING_GUARD_TEXT)
    }

    private fun datedRange(from: LocalDate, toInclusive: LocalDate): List<LocalDate> {
        val out = ArrayList<LocalDate>()
        var d = from
        while (!d.isAfter(toInclusive)) { out.add(d); d = d.plusDays(1) }
        return out
    }
}
