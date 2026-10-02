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
}
