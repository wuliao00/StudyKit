package com.studykit.ui.settings

import com.studykit.data.AppSettings
import com.studykit.data.memory.ExamEntry
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 考试清单编辑器的解析与校验（v3.0-B T9）。
 *
 * 校验的立场与 `fromMap` 一致：**说不通就说什么不通**，逐字段给错误串，不悄悄纠正用户填的东西。
 * 只有"分隔符/空白"这种纯格式噪音才无声修掉。
 */
class ExamEditorTest {

    @Test
    fun `学科串按逗号顿号空白切，去空去重掐空白`() {
        assertEquals(
            listOf("数学", "物理"),
            examSubjectsFromText(" 数学 , 物理、、 数学 、  "),
        )
        assertEquals(emptyList<String>(), examSubjectsFromText(""))
        assertEquals(emptyList<String>(), examSubjectsFromText("，、 "))
    }

    @Test
    fun `学科数量封顶 8 条`() {
        val many = (1..20).joinToString(",") { "科$it" }
        assertEquals(8, examSubjectsFromText(many).size)
    }

    @Test
    fun `学科串写回用顿号`() {
        assertEquals("数学、物理", examSubjectsToText(listOf("数学", "物理")))
        assertEquals("", examSubjectsToText(emptyList()))
    }

    @Test
    fun `日期字段：格式错与越界给不同的话，留空也报错`() {
        assertEquals("按 2026-12-19 这样的 yyyy-MM-dd 填", validateExamDraft("", "95", "30").date)
        assertEquals("按 2026-12-19 这样的 yyyy-MM-dd 填", validateExamDraft("乱七八糟", "95", "30").date)
        assertTrue(validateExamDraft("1999-01-01", "95", "30").date!!.contains("之间"))
        assertNull(validateExamDraft("2026-12-19", "95", "30").date)
    }

    @Test
    fun `冲刺保留率收百分数也收小数，越界报错`() {
        assertNull(validateExamDraft("2026-12-19", "95", "30").sprint)
        assertNull(validateExamDraft("2026-12-19", "0.95", "30").sprint)
        assertNotNull(validateExamDraft("2026-12-19", "40", "30").sprint)
        assertNotNull(validateExamDraft("2026-12-19", "abc", "30").sprint)
    }

    @Test
    fun `窗口天数越界报错，0 合法`() {
        assertNull(validateExamDraft("2026-12-19", "95", "0").window)
        assertNull(validateExamDraft("2026-12-19", "95", "365").window)
        assertNotNull(validateExamDraft("2026-12-19", "95", "366").window)
        assertNotNull(validateExamDraft("2026-12-19", "95", "-1").window)
    }

    @Test
    fun `有错时 examFromDraft 不给条目`() {
        assertNull(examFromDraft("期末考", "错的", listOf("数学"), "95", "30", fallback = ExamEntry("x", 1L)))
    }

    @Test
    fun `draft 转条目：百分数变比例，学科与名称都到位`() {
        val e = examFromDraft(" 期末考 ", "2026-12-19", listOf("数学"), "94", "21", fallback = ExamEntry("x", 1L))!!
        assertEquals("期末考", e.name)
        assertEquals(LocalDate.parse("2026-12-19").toEpochDay(), e.epochDay)
        assertEquals(listOf("数学"), e.subjects)
        assertEquals(0.94, e.rSprint, 1e-9)
        assertEquals(21, e.windowDays)
    }

    @Test
    fun `名称留空退回默认名，不产生空标题的考试`() {
        val e = examFromDraft("", "2026-12-19", emptyList(), "95", "30", fallback = ExamEntry("x", 1L))!!
        assertEquals(ExamEntry.DEFAULT_NAME, e.name)
    }

    @Test
    fun `ramp 说明照文档那句，带上窗口天数`() {
        val s = rampHint(21)
        assertTrue(s, s.contains("考前 21 天内"))
        assertTrue(s, s.contains("复习量会涨"))
        assertTrue(s, s.contains("涌量"))
    }

    @Test
    fun `套卷说明：不限时与两种暂停口径都说得清`() {
        assertTrue(mockTimingHint(0, false).contains("不限时"))
        assertTrue(mockTimingHint(45, true), mockTimingHint(45, true).contains("也照常计时"))
        assertTrue(mockTimingHint(45, false), mockTimingHint(45, false).contains("不照常计时"))
        assertTrue(mockTimingHint(45, false).contains("待批改"))
    }

    @Test
    fun `hasError 三个字段任一非空即为真`() {
        assertFalse(ExamDraftError(null, null, null).hasError)
        assertTrue(ExamDraftError("x", null, null).hasError)
    }

    @Test
    fun `清单里的日期与存储范围同一条闸门`() {
        // 校验用的是 AppSettings.EXAM_DAY_RANGE，不是自己另抄一套上下界
        val outside = AppSettings.EXAM_DAY_RANGE.first - 1
        assertNotNull(validateExamDraft(LocalDate.ofEpochDay(outside).toString(), "95", "30").date)
    }

    private fun assertNotNull(v: String?) = org.junit.Assert.assertNotNull(v)
}
