package com.studykit.data.memory

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ExamPolicy` 的纯逻辑单测 —— 考试临窗的目标保留率 ramp。
 *
 * 钉的是文档 L1887-1888 那四条 ⛔ 一票否决：边界取值、窗口外、非关联学科、0.97 上限。
 * 全部用固定日期夹具（`TODAY`），不读系统时钟。
 * ⛔ 用例的期望值一律写**字面量**、不引生产常量：期望值若读 `MAX_EFFECTIVE_RETENTION`，
 * 有人把常量改成 0.99 时测试会跟着一起移，闸门就永远测不到闸本身。
 */
class ExamPolicyTest {

    private fun exam(
        day: Long,
        subjects: List<String> = emptyList(),
        sprint: Double = 0.95,
        window: Int = 30,
        name: String = "考试",
    ) = ExamEntry(name = name, epochDay = TODAY.toEpochDay() + day, subjects = subjects, rSprint = sprint, windowDays = window)

    @Test
    fun `硬闸 ramp 边界：窗口起点等于 base`() {
        val e = exam(day = 30L, sprint = 0.95)
        assertEquals(0.80, ExamPolicy.effectiveRetention("数学", TODAY, listOf(e), 0.80), 1e-9)
    }

    @Test
    fun `硬闸 ramp 边界：考试当天等于冲刺值`() {
        val e = exam(day = 0L, sprint = 0.95)
        assertEquals(0.95, ExamPolicy.effectiveRetention("数学", TODAY, listOf(e), 0.80), 1e-9)
    }

    @Test
    fun `硬闸 ramp 边界：窗口外一天不抬`() {
        val e = exam(day = 31L, sprint = 0.95, window = 30)
        assertEquals(0.80, ExamPolicy.effectiveRetention("数学", TODAY, listOf(e), 0.80), 1e-9)
    }

    @Test
    fun `硬闸 非关联学科恒 base`() {
        val e = exam(day = 3L, subjects = listOf("数学"), sprint = 0.95)
        assertEquals(0.80, ExamPolicy.effectiveRetention("英语", TODAY, listOf(e), 0.80), 1e-9)
    }

    @Test
    fun `窗口内线性：中点是半程`() {
        val e = exam(day = 15L, sprint = 0.95, window = 30)
        assertEquals(0.875, ExamPolicy.effectiveRetention("数学", TODAY, listOf(e), 0.80), 1e-9)
    }

    @Test
    fun `硬闸 0 点 97 上限：冲刺值 0 点 99 也只到 0 点 97`() {
        val e = exam(day = 0L, sprint = 0.99)
        // 字面量 0.97 而不是 MAX_EFFECTIVE_RETENTION：常量被改成 0.99 时这条必须红，不能跟着移
        assertEquals(0.97, ExamPolicy.effectiveRetention("数学", TODAY, listOf(e), 0.80), 1e-9)
    }

    @Test
    fun `base 已高于冲刺值时不往回拉`() {
        val e = exam(day = 0L, sprint = 0.85)
        assertEquals(0.95, ExamPolicy.effectiveRetention("数学", TODAY, listOf(e), 0.95), 1e-9)
    }

    @Test
    fun `多场考试取最紧的那一场`() {
        val far = exam(day = 25L, sprint = 0.90, window = 30)   // 0.80 + 0.10*(1-25/30) = 0.8167
        val near = exam(day = 1L, sprint = 0.96, window = 30)   // 0.80 + 0.16*(1-1/30)  = 0.9547
        // 逐场算完取 max：0.9547 而不是两者的均值或先来后到
        assertEquals(0.9547, ExamPolicy.effectiveRetention("数学", TODAY, listOf(far, near), 0.80), 1e-3)
    }

    @Test
    fun `subjects 为空的那场 = 全学科生效`() {
        val e = exam(day = 0L, subjects = emptyList(), sprint = 0.95)
        assertTrue(ExamPolicy.linksSubject(e, "英语"))
        assertEquals(0.95, ExamPolicy.effectiveRetention("英语", TODAY, listOf(e), 0.80), 1e-9)
    }

    @Test
    fun `subject 为 null 时只吃全学科那场`() {
        val tied = exam(day = 0L, subjects = listOf("数学"))
        val all = exam(day = 0L, subjects = emptyList())
        assertFalse(ExamPolicy.linksSubject(tied, null))
        assertTrue(ExamPolicy.linksSubject(all, null))
        assertEquals(0.80, ExamPolicy.effectiveRetention(null, TODAY, listOf(tied), 0.80), 1e-9)
    }

    @Test
    fun `学科匹配掐空白并忽略大小写`() {
        val e = ExamEntry(name = "J", epochDay = TODAY.toEpochDay(), subjects = listOf(" Math "))
        assertTrue(ExamPolicy.linksSubject(e, "math"))
        assertFalse(ExamPolicy.linksSubject(e, "物理"))
    }

    @Test
    fun `过期考试不参与`() {
        val past = exam(day = -5L, sprint = 0.95)
        assertEquals(0.80, ExamPolicy.effectiveRetention("数学", TODAY, listOf(past), 0.80), 1e-9)
        assertNull(ExamPolicy.nearestExamDaysLeft("数学", TODAY, listOf(past)))
    }

    @Test
    fun `windowDays 为 0 时只在考试当天抬`() {
        val e = exam(day = 0L, sprint = 0.95, window = 0)
        assertEquals(0.95, ExamPolicy.effectiveRetention("数学", TODAY, listOf(e), 0.80), 1e-9)
        val e1 = exam(day = 1L, sprint = 0.95, window = 0)
        assertEquals(0.80, ExamPolicy.effectiveRetention("数学", TODAY, listOf(e1), 0.80), 1e-9)
    }

    @Test
    fun `nearestExamDaysLeft 取关联学科里最近的一场，无关联为 null`() {
        val near = exam(day = 4L, subjects = listOf("数学"))
        val far = exam(day = 9L, subjects = listOf("数学"))
        val other = exam(day = 1L, subjects = listOf("英语"))
        assertEquals(4, ExamPolicy.nearestExamDaysLeft("数学", TODAY, listOf(far, near, other)))
        assertEquals(1, ExamPolicy.nearestExamDaysLeft("英语", TODAY, listOf(far, near, other)))
        assertNull(ExamPolicy.nearestExamDaysLeft("物理", TODAY, listOf(far, near, other)))
    }

    @Test
    fun `nearestExamDaysLeft 考试当天返回 0 而不是 null`() {
        // 与 effectiveRetention 的 left=0→sprint 对齐：当天是最紧急的一天，horizon 不许把它丢掉
        val today = exam(day = 0L)
        val future = exam(day = 6L)
        assertEquals(0, ExamPolicy.nearestExamDaysLeft("数学", TODAY, listOf(future, today)))
        assertEquals(0, ExamPolicy.nearestExamDaysLeft("数学", TODAY, listOf(today)))
    }

    @Test
    fun `subjects 全空白串等于空列表 = 全学科`() {
        // 旧存储字符串合成的 ExamEntry 会切出 listOf("")：必须按全学科处理，
        // 否则症状是「全局考试悄悄不再影响任何卡」，静默且查不出来
        val blank = exam(day = 0L, subjects = listOf(""), sprint = 0.95)
        val spaced = exam(day = 0L, subjects = listOf("  "), sprint = 0.95)
        assertTrue(ExamPolicy.linksSubject(blank, "英语"))
        assertTrue(ExamPolicy.linksSubject(spaced, "英语"))
        assertTrue(ExamPolicy.linksSubject(blank, null))
        assertEquals(0.95, ExamPolicy.effectiveRetention("英语", TODAY, listOf(blank), 0.80), 1e-9)
        assertEquals(0.95, ExamPolicy.effectiveRetention(null, TODAY, listOf(spaced), 0.80), 1e-9)
        assertEquals(0, ExamPolicy.nearestExamDaysLeft("英语", TODAY, listOf(blank)))
    }

    private companion object {
        val TODAY: LocalDate = LocalDate.of(2026, 10, 6)
    }
}
