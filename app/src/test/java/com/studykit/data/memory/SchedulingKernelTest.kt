// 本文件只钉"HalfLifeKernel 适配器 ≡ 直接委托 MemoryModel"；MemoryModel 公式自身漂移由 MemoryModelTest 负责。
package com.studykit.data.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SchedulingKernelTest {
    @Test fun `halfLife adapter reproduces v2_6 update and schedule`() {
        // h=30、d=2 是本用例自选的"成熟词"输入，不是从 MemoryModelTest 引来的
        val kernel = HalfLifeKernel()
        val before = KernelState(stability = null, difficulty = 2.0, cardState = CardState.REVIEW, hDays = 30.0)
        val after = kernel.review(before, elapsedDays = 2.0, rating = KernelRating.GOOD, conf = null)
        // 30 天半衰期、target 0.9 下"认识"一次应显著加固 h
        assertTrue("成熟词评认识后 h 必须上涨，实际 ${after.hDays}", (after.hDays ?: 0.0) > 30.0)
        val days = kernel.nextIntervalDays(after, rating = KernelRating.GOOD, targetRecall = 0.9, maxIntervalDays = 365.0)
        // 期望值在前：两条调用路径算的是同一个纯函数，浮点上本该逐比特相等；
        // 这里留 1e-12 只当表达式求值顺序差异的兜底，不是允许漂移。
        assertEquals(
            MemoryModel.schedule(MemoryState(after.hDays!!, after.difficulty), ReviewGrade.RECALL, 0.9, 365.0),
            days, 1e-12,
        )
        // AGAIN 映射到 FORGET，MemoryModel.schedule 的再见地板就是 ≤1 天（当天内再碰一次）
        assertTrue("AGAIN 走当日再见地板", kernel.nextIntervalDays(after, KernelRating.AGAIN, 0.9, 365.0) <= 1.0)
    }

    @Test fun `seedFromHalfLife keeps halfLife dims and mirrors stability via ratio`() {
        val s = MemoryState(halfLifeDays = 12.5, difficulty = 3.2)
        val k = HalfLifeKernel().seedFromHalfLife(s.halfLifeDays, s.difficulty)
        assertEquals(12.5, k.hDays!!, 1e-9)
        assertEquals(3.2, k.difficulty, 1e-9)
        assertEquals(12.5 / FSRS_HALF_OVER_S, k.stability!!, 1e-12)
        // 适配器契约：seed 出来的行按已复习状态给 REVIEW（只有 review 不发明 cardState）
        assertEquals(CardState.REVIEW, k.cardState)
    }

    @Test fun `review mirrors MemoryModel update exactly, not just direction`() {
        val kernel = HalfLifeKernel()
        val before = KernelState(stability = null, difficulty = 2.0, cardState = CardState.REVIEW, hDays = 30.0)
        for (rating in KernelRating.entries) {
            val expectedGrade = when (rating) {
                KernelRating.AGAIN -> ReviewGrade.FORGET
                KernelRating.HARD -> ReviewGrade.VAGUE
                KernelRating.GOOD, KernelRating.EASY -> ReviewGrade.RECALL
            }
            val exp = MemoryModel.update(
                MemoryState(halfLifeDays = 30.0, difficulty = 2.0), 2.0, expectedGrade, MemoryParams(),
            )
            val after = kernel.review(before, elapsedDays = 2.0, rating = rating, conf = null)
            // 两侧调的是同一个纯函数、同样入参 ⇒ 期望逐比特相等，容差 0.0
            assertEquals(exp.halfLifeDays, after.hDays!!, 0.0)
            assertEquals(exp.difficulty, after.difficulty, 0.0)
            assertEquals(exp.halfLifeDays / FSRS_HALF_OVER_S, after.stability!!, 0.0)
            // 适配器不发明 cardState（生命周期属 FSRS），每次评分都原样带回
            assertEquals(before.cardState, after.cardState)
        }
    }

    @Test fun `recall matches recallProbability incl argument order`() {
        val kernel = HalfLifeKernel()
        val s = KernelState(stability = null, difficulty = 1.0, cardState = CardState.REVIEW, hDays = 8.0)
        // 写反参数（elapsed↔h）会得到明显不同的值（(2,8)→0.841 vs (8,2)→0.0625），这条就是防呆
        assertEquals(MemoryModel.recallProbability(2.0, 8.0), kernel.recall(s, elapsedDays = 2.0), 1e-12)
        assertEquals(0.5, kernel.recall(s, elapsedDays = 8.0), 1e-12)
        // hDays=null 的降级口径：按 NEW 的半衰期算，不抛
        assertEquals(
            MemoryModel.recallProbability(1.0, MemoryState.NEW.halfLifeDays),
            kernel.recall(s.copy(hDays = null), elapsedDays = 1.0),
            1e-12,
        )
    }

    @Test fun `difficulty mirror roundtrips on the whole halfLife domain`() {
        // 列量纲固定（A-T9 终审）的前提：正反两函数在 halfD∈[1,10] 上逐点互逆，
        // words.difficulty 与 fsrs_difficulty 才能各守各的量纲互不污染
        for (halfD in listOf(1.0, 2.0, 3.2, 7.0, 9.5, 10.0)) {
            assertEquals("halfD=$halfD", halfD, halfDifficultyFromFsrs(fsrsDifficultyFromHalfLife(halfD)), 1e-9)
        }
        // 与 MIGRATION_6_7 回填 SQL 同式：fsrsD = 5 + (halfD−1)·0.5
        assertEquals(5.5, fsrsDifficultyFromHalfLife(2.0), 1e-12)
        // 手改库的脏值回退到地板/天花板，不产生 NaN
        assertEquals(1.0, halfDifficultyFromFsrs(-7.0), 1e-12)
        assertEquals(10.0, halfDifficultyFromFsrs(Double.POSITIVE_INFINITY), 1e-12)
    }
}
