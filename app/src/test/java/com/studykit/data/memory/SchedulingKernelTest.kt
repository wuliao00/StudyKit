package com.studykit.data.memory

import org.junit.Assert.assertEquals
import org.junit.Test

class SchedulingKernelTest {
    @Test fun `halfLife adapter reproduces v2_6 update and schedule`() {
        // 与 MemoryModelTest 黄金轨迹同源：h=30、d=2、拖 2 天评认识
        val kernel = HalfLifeKernel()
        val before = KernelState(stability = null, difficulty = 2.0, cardState = CardState.REVIEW, hDays = 30.0)
        val after = kernel.review(before, elapsedDays = 2.0, rating = KernelRating.GOOD, conf = null)
        // 30 天半衰期、target 0.9 下"认识"一次应显著加固 h（对照 MemoryModelTest 黄金轨迹量级）
        check((after.hDays ?: 0.0) > 30.0)
        val days = kernel.nextIntervalDays(after, rating = KernelRating.GOOD, targetRecall = 0.9, maxIntervalDays = 365.0)
        assertEquals(days, MemoryModel.schedule(
            MemoryState(after.hDays!!, after.difficulty), ReviewGrade.RECALL, 0.9, 365.0,
        ), 1e-9)
    }

    @Test fun `toKernelState roundtrip`() {
        val s = MemoryState(halfLifeDays = 12.5, difficulty = 3.2)
        val k = HalfLifeKernel().seedFromHalfLife(s.halfLifeDays, s.difficulty)
        assertEquals(12.5, k.hDays!!, 1e-9)
        assertEquals(3.2, k.difficulty, 1e-9)
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
            assertEquals(exp.halfLifeDays, after.hDays!!, 1e-12)
            assertEquals(exp.difficulty, after.difficulty, 1e-12)
            assertEquals(
                exp.halfLifeDays / HalfLifeKernel.FSRS_SEED_RATIO, after.stability!!, 1e-12,
            )
            val expectedState = if (rating == KernelRating.AGAIN) CardState.RELEARNING else CardState.REVIEW
            assertEquals(expectedState, after.cardState)
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
}
