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
 * 期望值一律写手算的字面量（沿用 Task 1/Task 2 立下的约定），不引用生产常量、也不调生产函数取期望。
 */
class SubjectSchedulingTest {

    private val today = LocalDate.of(2026, 10, 6)
    private fun exam(dayOffset: Long, subjects: List<String>) =
        ExamEntry("考", today.plusDays(dayOffset).toEpochDay(), subjects)

    @Test
    fun `settings 为 null 等于没有考试的默认口径`() {
        assertEquals(schedulingForSubject(AppSettings(), "数学", today), schedulingForSubject(null, "数学", today))
        assertEquals(
            MemoryScheduler.forSettings(ReviewStrictness.AUTO, 0L, today),
            schedulingForSubject(null, "数学", today),
        )
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
