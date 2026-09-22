package com.studykit.ui.study

import com.studykit.data.memory.MemoryModel
import com.studykit.data.memory.MemoryScheduler
import com.studykit.data.memory.MemoryState
import com.studykit.data.memory.ReviewStrictness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 「设置页两个输入 → 模型两个数」这一步的契约，以及按钮上那行字的措辞。
 *
 * 挑出来单测是因为它错了会全局发作：考试日期填成过去、或者严格度没生效，
 * 表现是"所有人的复习量突然暴涨/消失"，而不是某一张卡算错。
 */
class MemorySchedulerTest {

    private val today = LocalDate.of(2026, 9, 22)

    private fun examIn(days: Int) = today.plusDays(days.toLong()).toEpochDay()

    @Test
    fun `no exam date uses the default target but still caps the interval`() {
        val s = MemoryScheduler.forSettings(ReviewStrictness.AUTO, 0L, today)
        assertEquals(0.9, s.targetRecall, 1e-9)
        // 上限按"半年保持期"给：36 天。不给上限的话，一个 h=2000 天的老词会被排到一年后
        assertEquals(36.0, s.maxIntervalDays, 1e-6)
    }

    /** 考试填在过去 = 没填。绝不能算出负保持期把间隔压成下限的 3 天 */
    @Test
    fun `a past exam date is treated as unset`() {
        val past = MemoryScheduler.forSettings(ReviewStrictness.AUTO, examIn(-40), today)
        val none = MemoryScheduler.forSettings(ReviewStrictness.AUTO, 0L, today)
        assertEquals(none.targetRecall, past.targetRecall, 1e-9)
        assertEquals(none.maxIntervalDays, past.maxIntervalDays, 1e-9)
    }

    /** 越近的考试越严格、上限越短（Cepeda 2006：最优间隔随保持期放大） */
    @Test
    fun `nearer exam means stricter target and shorter cap`() {
        val soon = MemoryScheduler.forSettings(ReviewStrictness.AUTO, examIn(30), today)
        val mid = MemoryScheduler.forSettings(ReviewStrictness.AUTO, examIn(120), today)
        val far = MemoryScheduler.forSettings(ReviewStrictness.AUTO, examIn(300), today)
        assertEquals(0.95, soon.targetRecall, 1e-9)
        assertEquals(0.85, mid.targetRecall, 1e-9)
        assertEquals(0.75, far.targetRecall, 1e-9)
        assertTrue(soon.maxIntervalDays < mid.maxIntervalDays)
        assertTrue(mid.maxIntervalDays < far.maxIntervalDays)
        assertEquals(6.0, soon.maxIntervalDays, 1e-6)
    }

    @Test
    fun `manual strictness overrides the exam-derived target`() {
        val exam = examIn(300)
        assertEquals(0.80, MemoryScheduler.forSettings(ReviewStrictness.RELAXED, exam, today).targetRecall, 1e-9)
        assertEquals(0.88, MemoryScheduler.forSettings(ReviewStrictness.STANDARD, exam, today).targetRecall, 1e-9)
        assertEquals(0.95, MemoryScheduler.forSettings(ReviewStrictness.STRICT, exam, today).targetRecall, 1e-9)
        // 手动档也要吃上限：上限仍由考试日期决定，不能被"我想严格"变成无限长
        assertEquals(60.0, MemoryScheduler.forSettings(ReviewStrictness.STRICT, exam, today).maxIntervalDays, 1e-6)
    }

    // ── 按钮上印的预览 ───────────────────────────────────────────────────

    /** 三档的间隔偏序必须在按钮上成立，否则用户会看到"忘记比认识排得更远"这种荒话 */
    @Test
    fun `preview keeps the ordering the buttons promise`() {
        val sched = MemoryScheduler.forSettings(ReviewStrictness.AUTO, examIn(120), today)
        val state = MemoryState(halfLifeDays = 3.0, difficulty = 1.0)
        val preview = MemoryModel.preview(
            state = state,
            gapDays = MemoryModel.intervalDays(state.halfLifeDays, sched.targetRecall),
            targetRecall = sched.targetRecall,
            maxIntervalDays = sched.maxIntervalDays,
        )
        assertTrue(
            "recall=${preview.recallDays} vague=${preview.vagueDays} forget=${preview.forgetDays}",
            preview.recallDays >= preview.vagueDays && preview.vagueDays > preview.forgetDays,
        )
        assertTrue("忘记后必须还在今天之内，实际 ${preview.forgetDays} 天", preview.forgetDays < 1.0)
        assertTrue("认识后的间隔 ${preview.recallDays} 天应落在 1~10 天量级", preview.recallDays in 1.0..10.0)
        assertEquals(0.85, preview.predictedRecall, 0.01)
    }

    /**
     * 拖得越久，按钮上的数字越大 —— 而且必须等于真机跑出来的那个值。
     *
     * 起因是真机第一轮：新词 abandon 拖了 22.8 小时，按钮印「今日」，
     * 点完实际排到 2.84 天后。原因是预览当时按"理想排期"算 gap，
     * 而越接近遗忘点提取、加固越强（间隔效应），于是预览严重低估。
     * 数字不许骗人，所以 gap 一律取真实已拖时间。
     */
    @Test
    fun `overdue words preview the interval they will actually get`() {
        val sched = MemoryScheduler.forSettings(ReviewStrictness.AUTO, 0L, today)
        val fresh = MemoryModel.preview(
            state = MemoryState.NEW, gapDays = 0.0,
            targetRecall = sched.targetRecall, maxIntervalDays = sched.maxIntervalDays,
        )
        val overdue = MemoryModel.preview(
            state = MemoryState.NEW, gapDays = 0.9507,
            targetRecall = sched.targetRecall, maxIntervalDays = sched.maxIntervalDays,
        )
        // 刚加进来、还没拖的词：提取发生在 p≈1 时，(1−p) 被夹到 1e-3，加固量几乎为零 → 仍排今天
        assertTrue("未拖的词应还排在今天内，实际 ${fresh.recallDays} 天", fresh.recallDays < 0.2)
        assertTrue("拖久了预览反而变短就是算错了：fresh=${fresh.recallDays} overdue=${overdue.recallDays}",
            overdue.recallDays > fresh.recallDays)
        // 真机实测值：h 0.5 → 18.7232，间隔 2.8437 天
        assertEquals(2.8437, overdue.recallDays, 0.02)
        assertEquals(0.2677, overdue.predictedRecall, 0.005)
    }

    @Test
    fun `preview never exceeds the cap`() {
        val sched = MemoryScheduler.forSettings(ReviewStrictness.AUTO, examIn(30), today)
        val state = MemoryState(halfLifeDays = 900.0, difficulty = 1.0)
        val preview = MemoryModel.preview(
            state = state,
            gapDays = MemoryModel.intervalDays(state.halfLifeDays, sched.targetRecall),
            targetRecall = sched.targetRecall,
            maxIntervalDays = sched.maxIntervalDays,
        )
        assertEquals(6.0, preview.recallDays, 1e-9)   // 600 天的半衰期也被 30 天考试的 6 天上限夹住
        assertTrue(preview.vagueDays <= 6.0 && preview.forgetDays <= 6.0)
    }

    // ── 文案 ─────────────────────────────────────────────────────────────

    @Test
    fun `interval wording reads as a sentence not a decimal`() {
        assertEquals("今日", formatIntervalLabel(0.0))
        assertEquals("今日", formatIntervalLabel(0.4))
        assertEquals("今日", formatIntervalLabel(0.99))
        assertEquals("明天", formatIntervalLabel(1.0))
        assertEquals("明天", formatIntervalLabel(1.4))
        assertEquals("2 天后", formatIntervalLabel(1.5))
        assertEquals("35 天后", formatIntervalLabel(35.4))
        assertEquals("36 天后", formatIntervalLabel(35.6))
    }

    /** 脏数字也要说人话：NaN/负数不能印成 "NaN 天后" */
    @Test
    fun `garbage input still reads as today`() {
        assertEquals("今日", formatIntervalLabel(Double.NaN))
        assertEquals("今日", formatIntervalLabel(Double.NEGATIVE_INFINITY))
        assertEquals("今日", formatIntervalLabel(-3.0))
    }
}
