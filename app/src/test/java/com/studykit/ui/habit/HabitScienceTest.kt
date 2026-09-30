package com.studykit.ui.habit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 习惯模块的科学化改造测试：
 * 66 天中位数（取代 21 天神话）、周达标率 + 断签保护卡（弹性连续）、
 * 执行意图（if-then）模板、习惯叠加提示。
 *
 * 依据：Lally et al. 2010（EJSP，习惯形成中位数 66 天，18–254 天；漏做一天不破坏养成）、
 * Gollwitzer & Sheeran 2006（执行意图元分析 d=0.65）。
 */
class HabitScienceTest {

    private val today = LocalDate.of(2026, 9, 29)

    // ── 目标天数：默认 66 天，文案不得宣称 21 天必成 ────────────────────

    @Test
    fun `default goal days is the research median not the 21 day myth`() {
        assertEquals(66, HabitScience.DEFAULT_TARGET_DAYS)
        assertTrue(HabitScience.GOAL_HINT.contains("66"))
        assertFalse("文案不能再出现「21 天养成习惯」的断言", HabitScience.GOAL_HINT.contains("21 天养成"))
    }

    @Test
    fun `progress phase describes early middle and mastery windows`() {
        assertEquals(HabitScience.Phase.EARLY, HabitScience.phase(10))
        assertEquals(HabitScience.Phase.MIDDLE, HabitScience.phase(40))
        assertEquals(HabitScience.Phase.MASTERY, HabitScience.phase(70))
    }

    // ── 周达标率：漏一天不算破功 ─────────────────────────────────────────

    @Test
    fun `weekly rate counts check-ins within the last seven days`() {
        val dates = setOf(
            today, today.minusDays(1), today.minusDays(2),
            today.minusDays(4), today.minusDays(6),
        )
        val rate = HabitScience.weeklyRate(dates, today)
        assertEquals(5, rate.checkedDays)
        assertEquals(7, rate.windowDays)
        assertEquals(71, rate.percent)
        assertTrue("5/7 应判定为达标", rate.metTarget(HabitScience.WEEKLY_TARGET_DAYS))
    }

    @Test
    fun `weekly rate target is five of seven days`() {
        assertEquals(5, HabitScience.WEEKLY_TARGET_DAYS)
        val four = setOf(today, today.minusDays(1), today.minusDays(2), today.minusDays(3))
        assertFalse(HabitScience.weeklyRate(four, today).metTarget(HabitScience.WEEKLY_TARGET_DAYS))
    }

    // ── 断签保护卡：月度额度内的一次缺席不清零连续天数 ──────────────────

    @Test
    fun `streak survives one protected gap within monthly quota`() {
        val dates = setOf(
            today, today.minusDays(1),
            today.minusDays(3),   // 昨天缺席
            today.minusDays(4), today.minusDays(5),
        )
        val result = HabitScience.streakWithProtection(dates, today, protectionCards = 1)
        assertEquals(5, result.streak)          // 跨过缺口继续累计
        assertEquals(1, result.cardsUsed)       // 跨过缺口消耗一张
        assertTrue(result.keptStreak)
    }

    @Test
    fun `streak breaks at gap when no protection card is left`() {
        val dates = setOf(today, today.minusDays(1), today.minusDays(3), today.minusDays(4))
        val result = HabitScience.streakWithProtection(dates, today, protectionCards = 0)
        assertEquals(2, result.streak)
        assertEquals(1, result.gaps)
        assertFalse(result.keptStreak)
    }

    @Test
    fun `one protection card covers only one gap`() {
        val dates = setOf(
            today, today.minusDays(1),
            today.minusDays(3), today.minusDays(5),   // 两个缺口
        )
        val result = HabitScience.streakWithProtection(dates, today, protectionCards = 1)
        assertEquals(3, result.streak)   // 只跨过最近的一个缺口（today、-1、-3）
        assertEquals(1, result.cardsUsed)
        assertEquals(1, result.gaps)     // 剩下的缺口无卡可用
    }

    @Test
    fun `monthly free quota is two cards`() {
        assertEquals(2, HabitScience.MONTHLY_PROTECTION_CARDS)
    }

    @Test
    fun `gap beyond seven days is not forgivable`() {
        val dates = setOf(today, today.minusDays(10), today.minusDays(11))
        val result = HabitScience.streakWithProtection(dates, today, protectionCards = 2)
        assertEquals(1, result.streak)
    }

    // ── 执行意图：当【时间/地点】，我做【行为】──────────────────────────

    @Test
    fun `implementation intention renders when-then sentence`() {
        val plan = HabitScience.intention(timeSlot = "早上起床后", place = "书桌前", action = "背 10 个单词")
        assertEquals("当我早上起床后坐在书桌前，我就背 10 个单词。", plan.text)
        assertTrue(plan.isComplete)
    }

    @Test
    fun `incomplete intention is flagged so ui can prompt`() {
        val plan = HabitScience.intention(timeSlot = "", place = "", action = "背单词")
        assertFalse("缺时间/地点时不算完整执行意图", plan.isComplete)
        assertEquals("我要背单词。", plan.text)
    }

    @Test
    fun `intention templates preload time place and action`() {
        val tpl = HabitScience.INTENTION_TEMPLATES.first()
        assertTrue(tpl.timeSlot.isNotBlank())
        assertTrue(tpl.action.isNotBlank())
        assertTrue(HabitScience.INTENTION_TEMPLATES.size >= 4)
    }

    // ── 习惯叠加：把新习惯接到既有线索上 ───────────────────────────────

    @Test
    fun `stacking suggestion attaches new habit to an existing anchor`() {
        val anchor = HabitScience.StackAnchor("背单词", "早上起床后")
        val suggestion = HabitScience.stackWith("跑步 30 分钟", anchor)
        assertTrue(suggestion.text.contains("背单词之后"))
        assertTrue(suggestion.text.contains("跑步 30 分钟"))
    }

    @Test
    fun `stacking needs an anchor to be meaningful`() {
        assertFalse(HabitScience.stackWith("跑步", null).isComplete)
    }
}
