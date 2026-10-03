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

    // ── monthStreakThrough：连续数跨保护日延续 ──────────────────────────────

    @Test fun `连续数跨受保护缺卡日延续`() {
        // 两次缺卡（下标 2、4）都落在月内前 2 次额度里 ⇒ 整串不断，6 天全算进去
        assertEquals(6, monthStreakThrough(listOf(true, true, false, true, false, true)))
    }

    @Test fun `月末全打卡时连续数即整串`() {
        assertEquals(4, monthStreakThrough(listOf(true, true, true, true)))
    }

    @Test fun `第三次缺卡让连续数归零`() {
        // 三次缺卡（下标 2、3、4）：前两次受保护，最后一次（就在串尾）已破链 ⇒ 尾巴为 0
        assertEquals(0, monthStreakThrough(listOf(true, true, false, false, false)))
    }

    @Test fun `破链缺卡之前的历史不计，只数尾部未断的一段`() {
        // 下标 4 是第三次缺卡（前 2、3 受保护）⇒ 从尾部走到 4 就断，只留最后一格
        assertEquals(1, monthStreakThrough(listOf(true, false, false, true, false, true)))
    }

    @Test fun `空串连续数为零`() {
        assertEquals(0, monthStreakThrough(emptyList()))
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

    // ── 🔴 B17 复审 1：进行中月份不许把「今天之后」算成缺卡 ────────────────

    @Test fun `今天 10 号时本月的串只画到 10 号`() {
        val month = java.time.YearMonth.of(2026, 9)
        val today = LocalDate.of(2026, 9, 10)
        // 1..10 号全打上：真实世界里本月到今天为止**没有一次缺卡**
        val checked = (1..10).map { month.atDay(it) }.toSet()
        val pattern = monthMissPattern(checkedDates = checked, month = month, today = today)
        assertEquals("串长应停在今日（10 格），未来的日子根本还没发生", 10, pattern.size)
        val guarded = HabitGuard.guardedIndices(pattern)
        assertTrue(
            "受保护缺卡不许落到未来格上（日序应 ≤ 今日），实际下标=$guarded",
            guarded.all { it < today.dayOfMonth },
        )
    }

    @Test fun `全部未来日都缺时本月连续仍按真实打卡天数算`() {
        val month = java.time.YearMonth.of(2026, 9)
        val today = LocalDate.of(2026, 9, 10)
        val checked = (1..10).map { month.atDay(it) }.toSet()
        val pattern = monthMissPattern(checkedDates = checked, month = month, today = today)
        // 10 天全打 → 「本月连续」就是 10；旧口径把 11..30 号当缺卡，尾巴那格是破链缺卡 → 报 0
        assertEquals(10, monthStreakThrough(pattern))
    }

    @Test fun `已经过去的月份照旧铺满整月`() {
        val month = java.time.YearMonth.of(2026, 8) // 31 天，整月都在今日之前
        val today = LocalDate.of(2026, 9, 10)
        val pattern = monthMissPattern(checkedDates = emptySet(), month = month, today = today)
        assertEquals(month.lengthOfMonth(), pattern.size)
    }

    // ── monthMissPattern：逐日布尔串（展示灰圈/派生的输入，只画到今天及以前）──

    @Test fun `整月布尔串按当月天数逐日给出打卡与否`() {
        val month = java.time.YearMonth.of(2026, 2) // 28 天
        val checked = setOf(month.atDay(1), month.atDay(2), month.atDay(5))
        val pattern = monthMissPattern(
            checkedDates = checked,
            month = month,
            today = LocalDate.of(2026, 9, 10), // 整月都在过去 → 不裁剪
        )
        assertEquals(28, pattern.size)
        assertTrue(pattern[0]); assertTrue(pattern[1])
        assertFalse(pattern[2]); assertFalse(pattern[3]); assertTrue(pattern[4])
    }

    // ── gapTipShouldShow：宽恕贴士的「每次安装一次」闸门（B17 复审 ⚠3）──────

    @Test fun `额度内且这台安装没看过时才挂宽恕贴士`() {
        assertTrue(gapTipShouldShow(eligible = true, gapTipSeen = false))
    }

    @Test fun `宽恕贴士看过一次就永不再现`() {
        assertFalse(gapTipShouldShow(eligible = true, gapTipSeen = true))
    }

    @Test fun `不额度时无论看过没有都不挂`() {
        assertFalse(gapTipShouldShow(eligible = false, gapTipSeen = false))
        assertFalse(gapTipShouldShow(eligible = false, gapTipSeen = true))
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
