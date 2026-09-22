package com.studykit.data.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 记忆模型的黄金轨迹测试。
 *
 * 这套公式的所有失败模式都是**静默**的：指数写反、少一个括号、忘了夹边界，
 * 编译和真机点两下都看不出来，只会表现为"复习排得越来越怪"。
 * 所以这里钉的不是"实现等于实现"，而是**手算得出的数字**：
 * 每个期望值都能用计算器从公式复现，注释里写了推导。
 */
class MemoryModelTest {

    private val params = MemoryParams()

    // ── 回忆概率 ─────────────────────────────────────────────────────────

    @Test
    fun `probability is exactly one half at one half-life`() {
        assertEquals(0.5, MemoryModel.recallProbability(1.0, 1.0), 1e-9)
        assertEquals(0.5, MemoryModel.recallProbability(30.0, 30.0), 1e-9)
    }

    /** 2^(−0.5) = 0.70710678… */
    @Test
    fun `probability at half a half-life is one over root two`() {
        assertEquals(0.70710678, MemoryModel.recallProbability(0.5, 1.0), 1e-6)
    }

    @Test
    fun `probability decreases monotonically with elapsed time`() {
        var previous = 1.01
        for (step in 0..40) {
            val p = MemoryModel.recallProbability(step * 0.5, 7.0)
            assertTrue("第 $step 步概率没有下降：$p >= $previous", p < previous)
            previous = p
        }
    }

    @Test
    fun `zero gap means fully recalled`() {
        assertEquals(1.0, MemoryModel.recallProbability(0.0, 5.0), 1e-9)
    }

    /** 脏数据必须降级而不是炸掉整条队列 */
    @Test
    fun `illegal half-life degrades to zero probability`() {
        assertEquals(0.0, MemoryModel.recallProbability(3.0, 0.0), 1e-9)
        assertEquals(0.0, MemoryModel.recallProbability(3.0, -1.0), 1e-9)
        assertEquals(0.0, MemoryModel.recallProbability(3.0, Double.NaN), 1e-9)
        assertEquals(0.0, MemoryModel.recallProbability(Double.NaN, 5.0), 1e-9)
    }

    // ── 间隔换算 ─────────────────────────────────────────────────────────

    /** Δt = h·log2(1/target)；h=10、target=0.9 → 10×0.152003 = 1.52003 天 */
    @Test
    fun `interval scales linearly with half-life`() {
        assertEquals(1.52003, MemoryModel.intervalDays(10.0, 0.9, params), 1e-4)
        assertEquals(15.2003, MemoryModel.intervalDays(100.0, 0.9, params), 1e-3)
    }

    @Test
    fun `looser target buys a longer interval`() {
        val strict = MemoryModel.intervalDays(30.0, 0.95, params)
        val standard = MemoryModel.intervalDays(30.0, 0.88, params)
        val relaxed = MemoryModel.intervalDays(30.0, 0.80, params)
        assertTrue("$relaxed 应 > $standard", relaxed > standard)
        assertTrue("$standard 应 > $strict", standard > strict)
    }

    @Test
    fun `interval is hard capped`() {
        assertEquals(params.absoluteMaxIntervalDays, MemoryModel.intervalDays(100000.0, 0.9, params), 1e-6)
    }

    // ── 状态转移：黄金轨迹 ───────────────────────────────────────────────

    /**
     * 新词第一次"认识"之后 h 应该是多少。
     *
     * 手算：h=0.5、d=1、按 target=0.9 排出的首复习 gap=0.076 天 ⇒ p=0.9、1−p=0.1
     * gain = e^3.81 · 1^−0.534 · 0.5^−0.127 · 0.1^0.970 = 45.150 × 1.09202 × 0.10716 = 5.2835
     * h' = 0.5 × (5.2835 + 1) = 3.1418 天
     */
    @Test
    fun `first successful recall multiplies the half life about sixfold`() {
        val next = MemoryModel.update(MemoryState.NEW, gapDays = 0.076, grade = ReviewGrade.RECALL, params = params)
        assertEquals(3.1418, next.halfLifeDays, 0.02)
        assertEquals(1.0, next.difficulty, 1e-9)
    }

    /**
     * 连续答对时，复习间隔必须**严格拉长**。
     *
     * 这正是现在那套"永远 +1 天 / +3 天"最缺的东西：背到第 20 次还只隔 3 天。
     * 期望序列（target=0.9，逐次手算）：0.076 → 0.478 → 2.48 → 10.9 → 41.5 → 140
     */
    @Test
    fun `consecutive recalls produce strictly growing intervals`() {
        var state = MemoryState.NEW
        var gap = MemoryModel.intervalDays(state.halfLifeDays, 0.9, params)
        val intervals = mutableListOf<Double>()
        repeat(6) {
            state = MemoryModel.update(state, gap, ReviewGrade.RECALL, params)
            gap = MemoryModel.intervalDays(state.halfLifeDays, 0.9, params)
            intervals += gap
        }
        for (i in 1 until intervals.size) {
            assertTrue("第 $i 次间隔没有变长：${intervals}", intervals[i] > intervals[i - 1])
        }
        // 量级必须和手算一致（放 20% 容差，参数微调时不至于全线红）
        assertEquals(0.478, intervals[0], 0.10)
        assertEquals(2.48, intervals[1], 0.5)
        assertEquals(10.9, intervals[2], 2.0)
        assertEquals(41.5, intervals[3], 8.0)
    }

    /**
     * 墨墨真机把间隔直接印在按钮上：一个已经认识几轮的词，"认识"给的是 **35 天后**。
     * 这条测试钉住的是"我们的模型处在同一个量级"，不是逐位相同。
     */
    @Test
    fun `reproduces_the_interval_printed_on_momo_buttons`() {
        // 4 次连续认识之后（第 5 次复习当场）
        var state = MemoryState.NEW
        var gap = MemoryModel.intervalDays(state.halfLifeDays, 0.9, params)
        repeat(4) {
            state = MemoryModel.update(state, gap, ReviewGrade.RECALL, params)
            gap = MemoryModel.intervalDays(state.halfLifeDays, 0.9, params)
        }
        val next = MemoryModel.intervalDays(state.halfLifeDays, 0.9, params)
        assertTrue("第 5 次应排到 20~70 天后，实际 $next 天", next in 20.0..70.0)
    }

    // ── 忘记 ─────────────────────────────────────────────────────────────

    /**
     * 失败支是"直接回归出一个新 h"，不是"把 h 乘个小于 1 的系数"，
     * 所以它有一个 ≈1.2 天的不动点：h 很大时忘记会把 h 狠狠砍下来，
     * h 本来就很小时忘记反而略微抬高 h。**这是发布参数的固有行为**，
     * 这里把它钉成测试，是为了防止以后有人"顺手修正"成单调下降。
     */
    @Test
    fun `forgetting pulls a long memory down and never explodes a short one`() {
        val long = MemoryState(halfLifeDays = 10.0, difficulty = 1.0)
        val afterLong = MemoryModel.update(long, gapDays = 10.0, grade = ReviewGrade.FORGET, params = params)
        assertTrue("h=10 忘记后应显著下降，实际 ${afterLong.halfLifeDays}", afterLong.halfLifeDays < 5.0)
        assertTrue("难度应上调", afterLong.difficulty > long.difficulty)

        val short = MemoryState(halfLifeDays = 0.5, difficulty = 1.0)
        val afterShort = MemoryModel.update(short, gapDays = 0.076, grade = ReviewGrade.FORGET, params = params)
        assertTrue("小 h 忘记后仍要留在 2 天以内，实际 ${afterShort.halfLifeDays}", afterShort.halfLifeDays < 2.0)
    }

    @Test
    fun `forgetting never schedules further than one day`() {
        val state = MemoryState(halfLifeDays = 500.0, difficulty = 1.0)
        val days = MemoryModel.schedule(
            MemoryModel.update(state, 100.0, ReviewGrade.FORGET, params),
            ReviewGrade.FORGET,
            targetRecall = 0.9,
            maxIntervalDays = 365.0,
            params = params,
        )
        assertTrue("忘记后最多一天，实际 $days", days <= 1.0)
        assertTrue("也不能排到过去", days >= 10.0 / (60.0 * 24.0) - 1e-9)
    }

    // ── 模糊 ─────────────────────────────────────────────────────────────

    /** 三档的偏序必须恒成立：认识 ≥ 模糊 > 忘记 */
    @Test
    fun `vague sits between recall and forget`() {
        val base = MemoryState(halfLifeDays = 8.0, difficulty = 1.5)
        val gap = MemoryModel.intervalDays(base.halfLifeDays, 0.9, params)
        val recall = MemoryModel.update(base, gap, ReviewGrade.RECALL, params)
        val vague = MemoryModel.update(base, gap, ReviewGrade.VAGUE, params)
        val forget = MemoryModel.update(base, gap, ReviewGrade.FORGET, params)
        assertTrue("h 偏序错了：recall=${recall.halfLifeDays} vague=${vague.halfLifeDays} forget=${forget.halfLifeDays}",
            recall.halfLifeDays >= vague.halfLifeDays && vague.halfLifeDays > forget.halfLifeDays)
        // 模糊按"成功加固、失败涨难度"处理：h 与认识同支，d 与忘记同步上调
        assertEquals(recall.halfLifeDays, vague.halfLifeDays, 1e-9)
        assertEquals(forget.difficulty, vague.difficulty, 1e-9)
    }

    // ── 边界与压力 ───────────────────────────────────────────────────────

    @Test
    fun `500 mixed reviews stay finite and inside bounds`() {
        var state = MemoryState.NEW
        var gap = 0.076
        val grades = ReviewGrade.values()
        repeat(500) { i ->
            state = MemoryModel.update(state, gap, grades[i % 3], params)
            val h = state.halfLifeDays
            assertTrue("第 $i 次出现非法 h：$h", h.isFinite() && h >= params.minHalfLifeDays && h <= params.maxHalfLifeDays)
            assertTrue("第 $i 次出现非法 d：${state.difficulty}",
                state.difficulty.isFinite() && state.difficulty >= 1.0 && state.difficulty <= params.maxDifficulty)
            gap = MemoryModel.schedule(state, grades[i % 3], 0.9, 45.0, params)
            assertTrue("第 $i 次间隔非法：$gap", gap.isFinite() && gap >= 0.0 && gap <= 45.0)
        }
    }

    @Test
    fun `garbage in the database cannot produce a past due date`() {
        val broken = MemoryState(halfLifeDays = Double.NaN, difficulty = Double.NEGATIVE_INFINITY)
        val next = MemoryModel.update(broken, gapDays = -3.0, grade = ReviewGrade.RECALL, params = params)
        assertTrue(next.halfLifeDays.isFinite() && next.halfLifeDays > 0.0)
        assertTrue(next.difficulty >= 1.0)
        val days = MemoryModel.schedule(next, ReviewGrade.RECALL, 0.9, 365.0, params)
        assertTrue(days > 0.0 && days.isFinite())
    }

    // ── 目标值与上限（考试日锚点） ───────────────────────────────────────

    @Test
    fun `longer retention buys a looser target and a longer cap`() {
        val soon = MemoryModel.targetRecallFor(20)
        val late = MemoryModel.targetRecallFor(300)
        assertTrue("target 应随保持期放宽：$soon -> $late", late <= soon)
        assertTrue(soon in 0.75..0.95 && late in 0.75..0.95)

        assertTrue(MemoryModel.maxIntervalDaysFor(30) < MemoryModel.maxIntervalDaysFor(300))
        assertEquals(3.0, MemoryModel.maxIntervalDaysFor(5), 1e-9)          // 下限夹住
        assertEquals(365.0, MemoryModel.maxIntervalDaysFor(10000), 1e-9)    // 上限夹住
    }

    @Test
    fun `missing exam date falls back to the default target`() {
        assertEquals(params.defaultTargetRecall, MemoryModel.targetRecallFor(0), 1e-9)
        assertEquals(params.defaultTargetRecall, MemoryModel.targetRecallFor(-5), 1e-9)
    }
}
