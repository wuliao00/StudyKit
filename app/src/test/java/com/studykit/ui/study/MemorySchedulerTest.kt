package com.studykit.ui.study

import com.studykit.data.memory.ExamEntry
import com.studykit.data.memory.MemoryModel
import com.studykit.data.memory.MemoryParams
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

    /**
     * 手动档压过考试推导出的 target —— 只在**窗外**成立。
     *
     * 300 天后考、ramp 窗 30 天，所以这一格读的是没被抬过的 base。窗内手动档会跟着 ramp 往上走
     * （只抬不降，见下面 `manual 严格度也参与 ramp` 那一格）；把这条测试挪进窗口里它会红，
     * 那不是 bug，是这句话的字面范围。
     */
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

    // ── v3.0-B T3：per-subject 调度 ────────────────────────────────────────

    /**
     * `params` 不是摆设：两个分支都要认它。
     *
     * 这一格盯的是"传进去的一份非默认参数会不会被静默丢掉" —— 丢掉不会崩、不会红别的测试，
     * 只会让校准器训练出来的参数又走回发布默认值那条老路（v2.9 的 calibrated 参数就是这么丢的）。
     */
    @Test
    fun `自定义 params 在有无考试两个分支都算数`() {
        val params = MemoryParams(defaultTargetRecall = 0.72, absoluteMaxIntervalDays = 40.0)
        val noExam = MemoryScheduler.forSubject(ReviewStrictness.AUTO, emptyList(), "数学", today, params)
        assertEquals(0.72, noExam.targetRecall, 1e-9)
        // 默认保持期 180 天的 1/5 = 36 天，没顶到 absoluteMaxIntervalDays=40，所以这里读的是 36
        assertEquals(36.0, noExam.maxIntervalDays, 1e-9)
        // 顶到上限的那一档：窗外的远考试给 300 天 -> 60 天 -> 夹到 40
        val far = MemoryScheduler.forSubject(
            ReviewStrictness.AUTO, listOf(exam(300L, window = 0)), "数学", today, params,
        )
        assertEquals(40.0, far.maxIntervalDays, 1e-9)
    }

    private fun exam(dayOffset: Long, subjects: List<String> = emptyList(), sprint: Double = 0.95, window: Int = 30) =
        ExamEntry(name = "考", epochDay = today.plusDays(dayOffset).toEpochDay(), subjects = subjects, rSprint = sprint, windowDays = window)

    @Test
    fun `forSettings 等于把单日期摊成一场全学科考试的 forSubject`() {
        val day = today.plusDays(12).toEpochDay()
        assertEquals(
            MemoryScheduler.forSettings(ReviewStrictness.AUTO, day, today),
            MemoryScheduler.forSubject(ReviewStrictness.AUTO, listOf(exam(12L)), null, today),
        )
        // 「没设考试」两路同值：0L 哨兵 -> 不合成 -> 默认保持期
        assertEquals(
            MemoryScheduler.forSettings(ReviewStrictness.AUTO, 0L, today),
            MemoryScheduler.forSubject(ReviewStrictness.AUTO, emptyList(), "数学", today),
        )
        // 已过的日期也同值（都走"没有未来考试"那一支）
        val past = today.minusDays(3).toEpochDay()
        assertEquals(
            MemoryScheduler.forSettings(ReviewStrictness.STANDARD, past, today),
            MemoryScheduler.forSubject(ReviewStrictness.STANDARD, listOf(ExamEntry("考", past)), null, today),
        )
    }

    @Test
    fun `硬闸 窗口内：targetRecall 抬到冲刺值，maxIntervalDays 仍按考试剩余天数`() {
        val s = MemoryScheduler.forSubject(ReviewStrictness.AUTO, listOf(exam(15L, sprint = 0.95, window = 30)), "数学", today)
        // AUTO 档 base = targetRecallFor(15)=0.95（15 天 <60 天档），ramp 只抬到 min(…,0.97)
        assertEquals(0.95, s.targetRecall, 1e-9)
        assertEquals(MemoryModel.maxIntervalDaysFor(15), s.maxIntervalDays, 1e-9)
    }

    @Test
    fun `硬闸 非关联学科退回默认保持期，不被别人的考试日拖紧`() {
        val s = MemoryScheduler.forSubject(ReviewStrictness.AUTO, listOf(exam(5L, subjects = listOf("数学"))), "英语", today)
        assertEquals(
            MemoryScheduler.forSettings(ReviewStrictness.AUTO, 0L, today),
            s,
        )
    }

    @Test
    fun `manual 严格度也参与 ramp 取 max 且封顶 0 点 97`() {
        val relaxed = MemoryScheduler.forSubject(
            ReviewStrictness.RELAXED, listOf(exam(0L, sprint = 0.99, window = 30)), "数学", today,
        )
        // 期望值写死字面量而不是读 MAX_EFFECTIVE_RETENTION / maxIntervalDaysFor(1)：
        // 这两处读的若不是同一个生产常量，"闸门"就会跟着常量一起移，测试永远红不了。
        assertEquals(0.97, relaxed.targetRecall, 1e-9)
        // RELAXED base=0.80，left=0 -> 冲刺 0.99 -> 封顶 0.97。间隔上限仍按剩余天数（当天夹到 1 天 -> 地板 3 天）
        assertEquals(3.0, relaxed.maxIntervalDays, 1e-9)
    }

    @Test
    fun `manual STRICT 不被低冲刺值放松（只抬不降）`() {
        val s = MemoryScheduler.forSubject(
            ReviewStrictness.STRICT, listOf(exam(0L, sprint = 0.85, window = 30)), "数学", today,
        )
        assertEquals(0.95, s.targetRecall, 1e-9)   // STRICT=0.95 高于冲刺 0.85
    }

    @Test
    fun `多场考试：学科取自己那场，间隔上限也取那场`() {
        val exams = listOf(exam(100L, subjects = listOf("英语"), window = 30), exam(20L, subjects = listOf("数学"), window = 30))
        val math = MemoryScheduler.forSubject(ReviewStrictness.AUTO, exams, "数学", today)
        val eng = MemoryScheduler.forSubject(ReviewStrictness.AUTO, exams, "英语", today)
        // 4.0 = 20 天的 1/5，20.0 = 100 天的 1/5（写死数字，别让断言与实现读同一个函数）
        assertEquals(4.0, math.maxIntervalDays, 1e-9)
        assertEquals(20.0, eng.maxIntervalDays, 1e-9)
    }

    /**
     * 考试**当天**：v3.0-B T3 有意改掉的一处行为（不是回归）。
     *
     * 旧 `forSettings` 里"今天就是考试日"与"没填"同值（0.9 / 36 天）—— 那是"只有一个全局日期、
     * 背后没有 ramp"留下的副作用。现在当天是最紧的一档：horizon 夹到 1 天（不夹就是把最紧急的一天
     * 送进 `targetRecallFor(0)` / `maxIntervalDaysFor(0)` 的"无信息"分支，反而松开），
     * 于是 target 0.95、间隔地板 3 天。
     */
    @Test
    fun `考试当天是最紧的一档不再等同没填`() {
        val onExamDay = MemoryScheduler.forSettings(ReviewStrictness.AUTO, today.toEpochDay(), today)
        val none = MemoryScheduler.forSettings(ReviewStrictness.AUTO, 0L, today)
        assertEquals(0.95, onExamDay.targetRecall, 1e-9)
        assertEquals(3.0, onExamDay.maxIntervalDays, 1e-9)
        assertEquals(0.9, none.targetRecall, 1e-9)
        assertEquals(36.0, none.maxIntervalDays, 1e-9)
    }

    /**
     * 分派带出的第二处有意改变（同一处裁定，另一条支流）：手动档在临窗 30 天内跟着 ramp 往上抬 ——
     * 旧 `forSettings` 对手动档完全不看考试日。只抬不降，`STRICT` 本来就等于冲刺值所以纹丝不动。
     */
    @Test
    fun `手动档在窗口内跟着 ramp 抬，窗口外与旧算式逐值相同`() {
        // 15 天后考：0.80 + (0.95 − 0.80)·(1 − 15/30) = 0.875
        assertEquals(0.875, MemoryScheduler.forSettings(ReviewStrictness.RELAXED, examIn(15), today).targetRecall, 1e-9)
        // 窗口外一天（left=31 > windowDays=30）不抬 —— 这一支仍是旧值
        assertEquals(0.80, MemoryScheduler.forSettings(ReviewStrictness.RELAXED, examIn(31), today).targetRecall, 1e-9)
        assertEquals(0.95, MemoryScheduler.forSettings(ReviewStrictness.STRICT, examIn(15), today).targetRecall, 1e-9)
        // 间隔上限不进 ramp：仍按剩余天数给
        assertEquals(MemoryModel.maxIntervalDaysFor(15), MemoryScheduler.forSettings(ReviewStrictness.RELAXED, examIn(15), today).maxIntervalDays, 1e-9)
    }
}
