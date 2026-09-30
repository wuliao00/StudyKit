package com.studykit.ui.habit

import com.studykit.data.entity.Habit
import com.studykit.tips.TipEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 习惯「按证据改造」后新增判定的纯函数测试（不含对 HabitScience 既有行为的重复验证）：
 * 卡片展示数据组装、补卡是否允许与保护卡消耗、列表提示触发条件、
 * 创建页执行意图校验、习惯日历周达标标记。
 */
class HabitUiLogicTest {

    private val today = LocalDate.of(2026, 9, 29)

    private fun dayHabit(
        cueTime: String = "早上起床后",
        cuePlace: String = "书桌前",
        name: String = "背单词",
        targetDays: Int = HabitScience.DEFAULT_TARGET_DAYS,
        protectionCards: Int = HabitScience.MONTHLY_PROTECTION_CARDS,
        protectionUsed: Int = 0,
        protectionPeriod: String = "2026-09",
    ) = Habit(
        uuid = "u-$name",
        name = name,
        icon = "🎯",
        targetDays = targetDays,
        cueTime = cueTime,
        cuePlace = cuePlace,
        weeklyTargetDays = HabitScience.WEEKLY_TARGET_DAYS,
        protectionCards = protectionCards,
        protectionUsed = protectionUsed,
        protectionPeriod = protectionPeriod,
    )

    // ── 卡片展示数据组装 ────────────────────────────────────────────────

    @Test
    fun `card display shows intention day target and protected streak`() {
        val dates = (0 until 5).map { today.minusDays(it.toLong()) }.toSet()
        val streak = HabitScience.StreakResult(streak = 8, cardsUsed = 1, gaps = 0, keptStreak = true)
        val display = buildHabitCardDisplay(
            habit = dayHabit(),
            dates = dates,
            today = today,
            daysElapsed = 40,
            streak = streak,
        )
        assertTrue("应展示执行意图句", display.intentionText.contains("书桌前"))
        assertTrue("第 N 天应对齐 66 天中位数", display.dayCounterText.contains("第 40 天"))
        assertTrue(display.dayCounterText.contains("/ 66 天"))
        assertTrue("5/7 达标应标注已达标", display.weeklyText.contains("5/7") && display.weeklyMet)
        assertTrue("连续含保护应显示消耗张数", display.streakText.contains("含保护 1"))
    }

    @Test
    fun `card copy avoids the 21 day myth and threatening wording`() {
        val display = buildHabitCardDisplay(
            habit = dayHabit(),
            dates = setOf(today),
            today = today,
            daysElapsed = 10,
            streak = HabitScience.StreakResult(1, 0, 0, true),
        )
        val all = display.intentionText + display.dayCounterText + display.phaseHint +
            display.weeklyText + display.streakText
        assertFalse(all.contains("21 天养成"))
        assertFalse(all.contains("断签"))
        assertFalse(all.contains("清零"))
    }

    // ── 补卡：卡用完拒绝；允许时写 isMakeup 并递增已用卡数 ────────────────

    @Test
    fun `makeup rejected when no protection card left`() {
        val habit = dayHabit(protectionCards = 2, protectionUsed = 2)
        val decision = decideMakeup(habit, today.minusDays(1), today)
        assertTrue("卡用完时应拒绝补卡", decision is MakeupDecision.Blocked)
        assertNotNull((decision as MakeupDecision.Blocked).reason)
    }

    @Test
    fun `makeup allowed writes isMakeup and increments used cards`() {
        val habit = dayHabit(protectionCards = 2, protectionUsed = 0)
        val decision = decideMakeup(habit, today.minusDays(2), today)
        assertTrue(decision is MakeupDecision.Allowed)
        decision as MakeupDecision.Allowed
        assertTrue("补卡记录应标记为回补", decision.isMakeup)
        assertEquals("已用保护卡应加一", 1, decision.protectionUsedAfter)
        assertEquals(1, decision.cardsLeftAfter)
    }

    @Test
    fun `makeup rejected beyond the window even with cards`() {
        val habit = dayHabit()
        assertTrue(decideMakeup(habit, today.minusDays(30), today) is MakeupDecision.Blocked)
    }

    // ── 列表提示触发条件 ────────────────────────────────────────────────

    @Test
    fun `gap day tip only when yesterday missed and cards remain`() {
        val yesterdayMissing = setOf(today)  // 昨天不在集合内
        assertTrue(
            "昨天缺席且有卡才提示 GapDay",
            habitTipEvent(yesterdayMissing, today, streak = 0, cardsLeft = 1) is TipEvent.GapDay,
        )
        assertNull(
            "昨天缺席但没卡时不提示",
            habitTipEvent(yesterdayMissing, today, streak = 0, cardsLeft = 0),
        )
        val yesterdayPresent = setOf(today, today.minusDays(1))
        assertNull(
            "昨天已打卡不提示缺席",
            habitTipEvent(yesterdayPresent, today, streak = 3, cardsLeft = 2),
        )
    }

    @Test
    fun `sixty six day reminder fires at 21 not at 70`() {
        val consecutive = (0 until 70).map { today.minusDays(it.toLong()) }.toSet()
        assertTrue(
            "第 21 天给出 StreakReached 事件",
            habitTipEvent(consecutive, today, streak = 21, cardsLeft = 2) == TipEvent.StreakReached(21),
        )
        assertNull(
            "第 70 天连续且无缺口时不再提示",
            habitTipEvent(consecutive, today, streak = 70, cardsLeft = 2),
        )
    }

    // ── 创建页执行意图校验 ──────────────────────────────────────────────

    @Test
    fun `create page prompts when intention missing when or where`() {
        assertNotNull(intentionValidationError(cueTime = "", cuePlace = "", action = "背单词"))
        assertNotNull(intentionValidationError(cueTime = "早上", cuePlace = "", action = "背单词"))
        assertNull(intentionValidationError(cueTime = "早上起床后", cuePlace = "书桌前", action = "背单词"))
    }

    // ── 习惯日历：按 weeklyTarget 标记整周达标 ──────────────────────────

    @Test
    fun `calendar week met at five of seven but not four`() {
        val week = (0 until 7).map { today.minusDays(it.toLong()) }
        val five = week.take(5).toSet()
        val four = week.take(4).toSet()
        val target = HabitScience.WEEKLY_TARGET_DAYS
        assertTrue("5/7 判达标", isCalendarWeekMet(week, five, target))
        assertFalse("4/7 不达标", isCalendarWeekMet(week, four, target))
    }
}
