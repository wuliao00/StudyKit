package com.studykit.ui.study

import com.studykit.data.entity.Word
import com.studykit.data.memory.CardState
import com.studykit.data.memory.FsrsKernel
import com.studykit.data.memory.HalfLifeKernel
import com.studykit.data.memory.kernelStateOf
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 接线层（v2.7 计划 A Task 9）里那两枚纯函数的合约：
 *
 *  - [KernelHub.forId] 是**全仓唯一**把设置里的内核 id 变成内核实例的地方，坏值回 FSRS
 *    （与 `AppSettings.fromMap` 的白名单口径一致，不许出现第二套"默认内核"的解释）；
 *  - `kernelStateOf` 是 DB 行 → [com.studykit.data.memory.KernelState] 的唯一映射，
 *    两个内核各读各的字段、互不冒领（spec §2.1 单活跃内核的证人）。
 *
 * 为什么不测 `gradeCard` 本身：它是 `AndroidViewModel` 里的挂起函数，要 Room + Robolectric
 * 才能跑，而这一段的错全在这两个映射点上；调度本身的行为由 FsrsKernelTest /
 * SchedulingKernelTest / MemoryModelTest 各自钉住。
 */
class KernelWiringTest {
    @Test fun `kernelFor picks by settings id, garbage falls back to FSRS`() {
        assertEquals("FSRS", KernelHub.forId("FSRS").id)
        assertEquals("HALF_LIFE", KernelHub.forId("HALF_LIFE").id)
        assertEquals("FSRS", KernelHub.forId("???").id)
        assertEquals("FSRS", KernelHub.forId(null).id)
    }

    @Test fun `kernelStateOf maps a fsrs-written row`() {
        val w = Word(uuid = "u", word = "w", meaning = "m", example = "",
            fsrsStability = 5.0, fsrsDifficulty = 3.0, fsrsState = 2, kernel = "FSRS",
            halfLifeDays = 63.94736842105263, difficulty = 2.0)
        val ks = kernelStateOf(w)
        assertEquals(5.0, ks.stability!!, 1e-9)
        assertEquals(3.0, ks.difficulty, 1e-9)
        assertEquals(CardState.REVIEW, ks.cardState)
        assertEquals(63.94736842105263, ks.hDays!!, 1e-9)
        // FSRS 读 stability，HalfLife 读 hDays：同一行两内核各取所需，互不冒领
        assertEquals(0.9, FsrsKernel().recall(ks, 5.0), 1e-9)
        assertEquals(0.5, HalfLifeKernel().recall(ks, 63.94736842105263), 1e-9)
    }

    @Test fun `kernelStateOf maps a never-fsrs-reviewed legacy row`() {
        val w = Word(uuid = "u2", word = "w", meaning = "m", example = "",
            fsrsStability = null, fsrsDifficulty = null, fsrsState = 1, kernel = "HALF_LIFE",
            halfLifeDays = 4.0, difficulty = 3.0)
        val ks = kernelStateOf(w)
        assertEquals(null, ks.stability)
        assertEquals(CardState.LEARNING, ks.cardState)
        // FSRS 未评过的行：difficulty 借半衰期口径值进 FSRS（内部再 sanitize/夹取），hDays 原样
        assertEquals(3.0, ks.difficulty, 1e-9)
        assertEquals(4.0, ks.hDays!!, 1e-9)
    }
}
