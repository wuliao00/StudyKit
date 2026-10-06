package com.studykit.ui.study

import com.studykit.data.AppSettings
import com.studykit.data.entity.Word
import com.studykit.data.memory.CardState
import com.studykit.data.memory.FsrsKernel
import com.studykit.data.memory.HalfLifeKernel
import com.studykit.data.memory.kernelStateFor
import com.studykit.data.memory.kernelStateOf
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 接线层（v2.7 计划 A Task 9）里那两枚纯函数的合约：
 *
 *  - [KernelHub.forId] 是**全仓唯一**把"那份设置"（内核 id + 活跃 FSRS 参数）变成内核实例的地方，
 *    坏值回 FSRS（与 `AppSettings.fromMap` 的白名单口径一致，不许出现第二套"默认内核"的解释）；
 *  - `kernelStateOf` 是 DB 行 → [com.studykit.data.memory.KernelState] 的唯一映射，
 *    两个内核各读各的字段、互不冒领（spec §2.1 单活跃内核的证人）。
 *
 * 这一头只管**映射**（给什么设置出什么核）。"设置真的传到了各个调用点"是另一层的活，
 * 由 [ActiveParamsEndToEndTest]（背词/错题/提醒/首页四路）与
 * [GradePreviewCalibrationRenderTest]（评分按钮那一屏）钉住；`gradeCard` 本身仍是
 * `AndroidViewModel` 里的挂起函数，要 Room + Robolectric 才跑得动，所以它的算术在这两头验。
 */
class KernelWiringTest {
    @Test fun `kernelFor picks by settings id, garbage falls back to FSRS`() {
        // 双参口径（v3.0-A T2.6 起单参那条重载已删）：这几条传的是"从没采纳过"的那份设置，
        // 结果与旧单参逐值一致
        val untouched = AppSettings()
        assertEquals("FSRS", KernelHub.forId("FSRS", untouched).id)
        assertEquals("HALF_LIFE", KernelHub.forId("HALF_LIFE", untouched).id)
        assertEquals("FSRS", KernelHub.forId("???", untouched).id)
        // settings=null 只表示"还没读到设置"（首帧），同样回默认 FSRS；
        // 它不是"这个调用点不参与校准"的开关——拿得到设置就必须传进来
        assertEquals("FSRS", KernelHub.forId(null, null).id)
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
        // 列量纲固定（A-T9 终审）：fsrs_difficulty 缺失时，kernelStateOf 把半衰期口径的
        // words.difficulty 折算成 FSRS 口径（5+(3−1)·0.5=6）；hDays 原样
        assertEquals(6.0, ks.difficulty, 1e-9)
        assertEquals(4.0, ks.hDays!!, 1e-9)
        // HALF_LIFE 视角读到的才是原生半衰期难度（不折算）：两内核各取各量纲
        val half = kernelStateFor(KernelHub.forId("HALF_LIFE", AppSettings()), w)
        assertEquals(3.0, half.difficulty, 1e-9)
    }
}
