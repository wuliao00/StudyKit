package com.studykit.data.question

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 导入前缀的别名映射：官方阶段名 + 常见口语，都必须能落到枚举；陌生词必须拒绝 */
class QuestionTypeAliasTest {

    @Test
    fun `阶段别名覆盖中考高考与考公口语`() {
        assertEquals(ExamStage.JUNIOR, ExamStage.fromAlias("初中"))
        assertEquals(ExamStage.JUNIOR, ExamStage.fromAlias("中考"))
        assertEquals(ExamStage.SENIOR, ExamStage.fromAlias("高中"))
        assertEquals(ExamStage.SENIOR, ExamStage.fromAlias("高考"))
        assertEquals(ExamStage.COLLEGE, ExamStage.fromAlias("大学"))
        assertEquals(ExamStage.COLLEGE, ExamStage.fromAlias("本科"))
        assertEquals(ExamStage.POSTGRAD, ExamStage.fromAlias("考研"))
        assertEquals(ExamStage.CIVIL_SERVICE, ExamStage.fromAlias("考公"))
        assertEquals(ExamStage.CIVIL_SERVICE, ExamStage.fromAlias("国考"))
        assertEquals(ExamStage.CIVIL_SERVICE, ExamStage.fromAlias("省考"))
        assertNull(ExamStage.fromAlias("火星"))
    }

    @Test
    fun `题型别名覆盖判断简答主观材料`() {
        assertEquals(QuestionType.TRUE_FALSE, QuestionType.fromAlias("判断"))
        assertEquals(QuestionType.CALCULATION, QuestionType.fromAlias("计算"))
        assertEquals(QuestionType.SHORT_ANSWER, QuestionType.fromAlias("名词解释"))
        assertEquals(QuestionType.ESSAY, QuestionType.fromAlias("论述"))
        assertEquals(QuestionType.ESSAY, QuestionType.fromAlias("申论"))
        assertEquals(QuestionType.CASE_ANALYSIS, QuestionType.fromAlias("材料分析"))
        assertNull(QuestionType.fromAlias("火星题"))
    }

    @Test
    fun `存储值回读兼容大小写与脏值`() {
        assertEquals(QuestionType.TRUE_FALSE, QuestionType.fromStorage("TRUE_FALSE"))
        assertEquals(QuestionType.CASE_ANALYSIS, QuestionType.fromStorage("case_analysis"))
        assertEquals(QuestionType.SINGLE, QuestionType.fromStorage(""))
        assertEquals(ExamStage.SENIOR, ExamStage.fromStorage("senior"))
        assertEquals(ExamStage.UNSPECIFIED, ExamStage.fromStorage("火星"))
    }
}
