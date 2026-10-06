package com.studykit.ui.study

import com.studykit.data.AppSettings
import com.studykit.data.memory.CardState
import com.studykit.data.memory.FsrsModel
import com.studykit.data.memory.KernelRating
import com.studykit.data.memory.KernelState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 端到端证明"训练参数真正流入调度器"（v2.9 计划 Task 5）。
 *
 * 这里不是单测 `FsrsKernel(w)` 的构造，而是从**设置层**（`AppSettings.fsrsActiveParams`）出发，
 * 经全仓唯一的取核入口 [KernelHub.forId] 拿到内核，再看它对同一份卡状态排出的间隔：
 *  - `fsrsActiveParams = null` → 用 [FsrsModel.DEFAULT_PARAMS]，排出的间隔与"没采纳过"的设置
 *    **逐比特一致**——注入不改变"没训练过"的人的行为；
 *  - `fsrsActiveParams = 改过的 21 数组` → 排出的间隔**确实不同**。
 *
 * **这一层绿不等于用户收到了校准值。** v2.9 就是这个教训：这三条当时全绿，而全部生产调用点走的
 * 是只认 id 的单参 `forId(id)`，那份向量一步都没进调度器。"调用点确实把整份设置交给了 hub"
 * 由 [ActiveParamsEndToEndTest]（背词/错题/提醒/首页四路）与
 * [GradePreviewCalibrationRenderTest]（评分按钮那一屏）钉住，单参重载也已删除（v3.0-A T2.6）。
 *
 * 之所以喂 [KernelState] 后只看 `nextIntervalDays` 就够证明"生效"：该函数只经 `decay(w) = -w[20]`
 * 与 `factor(w) = 0.9^(1/decay)-1` 用到参数（`raw = s/factor·(dr^(1/decay)-1)`）。**注意 target 不能取
 * 0.9**：那时 `dr^(1/decay)-1` 恰好等于 `factor`，两者相消使 `raw = s`，间隔与所有参数无关（恒等于
 * 稳定度）。取一个偏离 0.9 的目标（此处 0.85），比值 `(dr^(1/decay)-1)/factor` 才随 `w[20]` 变化——
 * 把 `w[20]` 从默认 0.1542 挪开，间隔必然跟着变；若注入被忽略（内核仍拿默认），两次结果会相等，本测试即红。
 */
class ParamInjectionTest {

    private val state = KernelState(stability = 10.0, difficulty = 5.0, cardState = CardState.REVIEW)

    // target 用 0.85 而非 0.9：0.9 会让 factor 与 dr 项相消、间隔恒等于稳定度而与参数无关（见类注释）。
    private fun interval(kernel: com.studykit.data.memory.SchedulingKernel): Double =
        kernel.nextIntervalDays(state, KernelRating.GOOD, targetRecall = 0.85, maxIntervalDays = 365.0)

    @Test
    fun `null 活跃参数走默认参数_与今天逐值一致`() {
        val today = interval(KernelHub.forId("FSRS", AppSettings()))
        val viaSettings = interval(KernelHub.forId("FSRS", AppSettings(fsrsActiveParams = null)))
        assertEquals(today, viaSettings, 1e-12)
    }

    @Test
    fun `改过的活跃参数真的改变了排期`() {
        val today = interval(KernelHub.forId("FSRS", AppSettings()))
        // 故意偏离默认：动 w[20]（decay）——它是 nextIntervalDays 唯一读到的参数维；
        // 顺带动 w[0]/w[2]（首评稳定度）证明整条向量都被换掉，而不只是默认对象被复用。
        val altered = FsrsModel.DEFAULT_PARAMS.copyOf()
        altered[20] = 0.30
        altered[0] = 0.05
        altered[2] = 5.0
        val viaInjected = interval(KernelHub.forId("FSRS", AppSettings(fsrsActiveParams = altered)))
        assertNotEquals(
            "注入的活跃参数必须真正进入调度器（排期结果不能与默认一致）",
            today, viaInjected, 1e-9,
        )
    }

    @Test
    fun `半衰期内核不受活跃FSRS参数影响`() {
        // activeParams 只作用于 FSRS 分支；HALF_LIFE 仍旧按 id 返回半衰期内核
        val altered = FsrsModel.DEFAULT_PARAMS.copyOf().also { it[20] = 0.4 }
        val k = KernelHub.forId("HALF_LIFE", AppSettings(fsrsActiveParams = altered))
        assertEquals("HALF_LIFE", k.id)
    }
}
