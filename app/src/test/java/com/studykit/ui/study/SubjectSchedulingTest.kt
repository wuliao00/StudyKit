package com.studykit.ui.study

import com.studykit.data.AppSettings
import com.studykit.data.memory.ExamEntry
import com.studykit.data.memory.MemoryScheduler
import com.studykit.data.memory.ReviewStrictness
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `(设置, 学科, 今天) -> Scheduling` 这一条组合路径的钉子。
 *
 * 它存在的理由就一个：**全仓只许有这一处**把「严格度 + 考试清单 + 学科」拼成调度参数。
 * v2.9 的教训是 calibrated FSRS 参数在设置层存好了、生产调用点却只交 id，于是训练出来的参数
 * 一辈子没进过调度器（`KernelHub` 为此删掉了单参重载）。这里防的是同一件事的第二次发生。
 *
 * 期望值一律写手算的字面量（沿用 Task 1/Task 2 立下的约定），不拿生产函数给期望取数。
 * 对 `MemoryScheduler.forSettings` 的调用**只作同语义对照**留档（评审 6 的裁定）：
 * 值本身由字面量那条钉住，对照那条红线时只会说"两条都变了"，单独不具备鉴别力。
 */
class SubjectSchedulingTest {

    private val today = LocalDate.of(2026, 10, 6)
    private fun exam(dayOffset: Long, subjects: List<String>) =
        ExamEntry("考", today.plusDays(dayOffset).toEpochDay(), subjects)

    @Test
    fun `settings 为 null 等于没有考试的默认口径`() {
        val none = schedulingForSubject(null, "数学", today)
        // 字面量钉值：AUTO 无手动档、清单为空 → 默认保留率 0.9；间隔上限按半年保持期
        // 180×0.2=36 天（delta 断言：0.2 不可精确表示，位相等会挂在 36.000000000000004 上）
        assertEquals(0.9, none.targetRecall, 1e-9)
        assertEquals(36.0, none.maxIntervalDays, 1e-6)
        assertEquals(schedulingForSubject(AppSettings(), "数学", today), none)
        // 对照（合法，但鉴别力由上面两条字面量负责）：旧 forSettings 的"没设考试"哨兵同值
        assertEquals(MemoryScheduler.forSettings(ReviewStrictness.AUTO, 0L, today), none)
    }

    @Test
    fun `错题学科用清单里它那场考试的 ramp`() {
        val s = AppSettings(
            reviewStrictness = ReviewStrictness.RELAXED,
            exams = listOf(exam(10L, listOf("数学"))),
        )
        val math = schedulingForSubject(s, "数学", today)
        val english = schedulingForSubject(s, "英语", today)
        // RELAXED base=0.80，数学那场 10 天后考、窗 30 天 -> 0.80 + (0.95-0.80)*(1-10/30) = 0.80 + 0.15*(2/3) = 0.90
        // （brief 原文把这个式子写成 0.85，是手算错：它把 0.15 的 2/3 当成了 0.05。式子本身、
        //  以及 Task 5 那条同一个式子的用例「冲刺保留 90%」都给出 0.90，故改期望值、不改实现。）
        assertEquals(0.90, math.targetRecall, 1e-9)
        assertEquals(0.80, english.targetRecall, 1e-9)   // 英语没在这场考试的学科里，退回 RELAXED 那个 base
    }

    @Test
    fun `exams 空时旧 exam_epoch_day 仍然说了算`() {
        val legacy = AppSettings(examEpochDay = today.plusDays(10L).toEpochDay())
        val viaLegacy = schedulingForSubject(legacy, "数学", today)
        val viaList = schedulingForSubject(legacy.copy(exams = listOf(exam(10L, emptyList())), examEpochDay = 0L), "数学", today)
        assertEquals(viaList, viaLegacy)
    }
}
