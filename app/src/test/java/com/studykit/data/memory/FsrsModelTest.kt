package com.studykit.data.memory

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.pow

/**
 * Task 1 回归网：证明抽出来的 `FsrsModel` 纯函数与既有 `FsrsKernel` 逐值同式。
 * 期望值取自 `docs/evidence/fsrs6-golden.json`（py-fsrs 6.3.2 预言机直算）。
 */
class FsrsModelTest {

    private val w = FsrsModel.DEFAULT_PARAMS

    @Test fun `recall matches kernel decay and factor`() {
        // 与 FsrsKernel 同式：DECAY=-w[20], FACTOR=0.9^(1/DECAY)-1
        val decay = -w[20]
        val factor = 0.9.pow(1.0 / decay) - 1.0
        assertEquals(
            (1.0 + factor * 2.0 / 10.0).pow(decay),
            FsrsModel.recall(w, stability = 10.0, elapsedDays = 2.0),
            1e-12,
        )
    }

    @Test fun `next stability good matches golden 10p96433`() {
        // 取自 fsrs6-golden.json：S=2.3065,D=2.118103970459016,t=2.0,GOOD → S'=10.964332335820698
        val s = FsrsModel.recallStability(
            w, s = 2.3065, d = 2.118103970459016,
            rr = FsrsModel.recall(w, 2.3065, 2.0), rating = 3,
        )
        assertEquals(10.964332335820698, s, 1e-6)
    }
}
