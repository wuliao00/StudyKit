package com.studykit.ui.study

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「今天从哪开始」三步判定的纯单测（主动用户改版）。
 *
 * 这些用例钉的是**验收口径**而不是实现细节：
 * - 三个完成态只由真实读数决定，且每天从 0/3 开始；
 * - 「没有到期词」「题库还没题」这两种空态不让用户卡在永远差一格；
 * - 完成数不会超过 3，也不会把「签到」重复计数。
 */
class DailyStartPlannerTest {

    private fun plan(
        signedToday: Boolean = false,
        currentStreak: Int = 0,
        reviewedToday: Int = 0,
        dueCount: Int = 10,
        wordTotal: Int = 100,
        practicedToday: Int = 0,
        hasQuestions: Boolean = true,
    ) = DailyStartPlanner.plan(
        signedToday = signedToday,
        currentStreak = currentStreak,
        reviewedToday = reviewedToday,
        dueCount = dueCount,
        wordTotal = wordTotal,
        practicedToday = practicedToday,
        hasQuestions = hasQuestions,
    )

    @Test
    fun 什么都没做时是零比三且三步都有下一步() {
        val ui = plan()
        assertEquals(0, ui.doneCount)
        assertFalse(ui.allDone)
        assertEquals(3, ui.steps.size)
        assertEquals(listOf(false, false, false), ui.steps.map { it.done })
        assertEquals("去签到", ui.steps[0].actionLabel)
        assertEquals("开始复习", ui.steps[1].actionLabel)
        assertEquals("开始小测", ui.steps[2].actionLabel)
    }

    @Test
    fun 签到完成只算一步且带连续天数() {
        val ui = plan(signedToday = true, currentStreak = 4)
        assertEquals(1, ui.doneCount)
        assertTrue(ui.steps[0].done)
        assertEquals("今天已签到 · 连续 4 天", ui.steps[0].detail)
        assertEquals("签到日历", ui.steps[0].actionLabel)
    }

    @Test
    fun 今日复习满五个词才算复习完成() {
        assertEquals(0, plan(reviewedToday = 4, dueCount = 3).doneCount)
        val ui = plan(reviewedToday = 5, dueCount = 3)
        assertTrue(ui.steps[1].done)
        assertEquals("今天已复习 5 个词", ui.steps[1].detail)
    }

    @Test
    fun 到期词清零也算完成不留永远差一格() {
        // 只剩 2 个到期词、今天全复习完：dueCount = 0、reviewedToday = 2，仍应算完成。
        val ui = plan(reviewedToday = 2, dueCount = 0)
        assertTrue(ui.steps[1].done)
        assertTrue(ui.steps[1].detail.contains("已复习 2 个词"))
        // 一个词都没有时不能假装完成：这是「去加词」的真实下一步。
        val empty = plan(wordTotal = 0, dueCount = 0)
        assertFalse(empty.steps[1].done)
        assertEquals("去加词", empty.steps[1].actionLabel)
    }

    @Test
    fun 小测满三道才完成() {
        val two = plan(practicedToday = 2)
        assertFalse(two.steps[2].done)
        assertTrue(two.steps[2].detail.contains("再做 1 道"))
        val three = plan(practicedToday = 3)
        assertTrue(three.steps[2].done)
        assertEquals("今天已练 3 道", three.steps[2].detail)
    }

    @Test
    fun 题库为空时小测不去开空练习页() {
        val ui = plan(hasQuestions = false)
        assertFalse(ui.steps[2].done)
        assertEquals("去录题", ui.steps[2].actionLabel)
        assertTrue(ui.steps[2].detail.contains("先录一道"))
    }

    @Test
    fun 三步齐了才是已起飞且完成数封顶三() {
        val ui = plan(
            signedToday = true,
            currentStreak = 7,
            reviewedToday = 9,
            dueCount = 0,
            practicedToday = 12,
        )
        assertTrue(ui.allDone)
        assertEquals(3, ui.doneCount)
        assertEquals(3, ui.steps.count { it.done })
    }
}
