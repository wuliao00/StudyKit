package com.studykit.data.question

import com.studykit.data.entity.Question
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuestionGraderTest {

    private fun question(
        answerIndex: Int = 0,
        type: QuestionType = QuestionType.SINGLE,
        answerIndicesJson: String = "[]",
        answerTextJson: String = "[]",
        options: String = """["A","B","C","D"]""",
    ) = Question(
        id = 1L,
        uuid = "q1",
        subject = "数学",
        stem = "题干",
        optionsJson = options,
        answerIndex = answerIndex,
        explanation = "",
        questionType = type.storageValue,
        answerIndicesJson = answerIndicesJson,
        answerTextJson = answerTextJson,
    )

    // ── 单选 ────────────────────────────────────────────────────────────────

    @Test
    fun `单选选中正确项判对且只标选中项`() {
        val result = QuestionGrader.grade(
            question(answerIndex = 2),
            QuizResponse.Single(2),
        )
        assertTrue(result.correct)
        assertFalse(result.needsSelfJudge)
        assertEquals(mapOf(2 to OptionGrade.CORRECT), result.optionStates)
        assertEquals("C", result.userAnswerText)
        assertEquals("C", result.correctAnswerText)
    }

    @Test
    fun `单选选中错误项判错并标 WRONG`() {
        val result = QuestionGrader.grade(
            question(answerIndex = 2),
            QuizResponse.Single(0),
        )
        assertFalse(result.correct)
        assertEquals(mapOf(0 to OptionGrade.WRONG), result.optionStates)
        assertEquals("A", result.userAnswerText)
        assertEquals("C", result.correctAnswerText)
    }

    // ── 多选 ────────────────────────────────────────────────────────────────

    @Test
    fun `多选集合完全相等才算全对`() {
        val q = question(
            answerIndex = 0,
            type = QuestionType.MULTIPLE,
            answerIndicesJson = "[0,2]",
        )
        val result = QuestionGrader.grade(q, QuizResponse.Multiple(setOf(0, 2)))
        assertTrue(result.correct)
        assertEquals(2, result.partialCorrect)
        assertEquals(2, result.partialTotal)
        assertEquals(OptionGrade.CORRECT, result.optionStates[0])
        assertEquals(OptionGrade.CORRECT, result.optionStates[2])
        assertEquals("A,C", result.correctAnswerText)
    }

    @Test
    fun `多选漏选与错选不算全对但给出部分正确`() {
        val q = question(
            answerIndex = 0,
            type = QuestionType.MULTIPLE,
            answerIndicesJson = "[0,2]",
        )
        val result = QuestionGrader.grade(q, QuizResponse.Multiple(setOf(0, 1)))
        assertFalse(result.correct)
        assertEquals(1, result.partialCorrect)
        assertEquals(2, result.partialTotal)
        assertEquals(OptionGrade.CORRECT, result.optionStates[0])
        assertEquals(OptionGrade.WRONG, result.optionStates[1])
        assertEquals(OptionGrade.MISSED, result.optionStates[2])
    }

    @Test
    fun `多选空选择不算对且所有正确项标 MISSED`() {
        val q = question(
            answerIndex = 0,
            type = QuestionType.MULTIPLE,
            answerIndicesJson = "[0,2]",
        )
        val result = QuestionGrader.grade(q, QuizResponse.Multiple(emptySet()))
        assertFalse(result.correct)
        assertEquals(0, result.partialCorrect)
        assertEquals(2, result.partialTotal)
        assertEquals(OptionGrade.MISSED, result.optionStates[0])
        assertEquals(OptionGrade.MISSED, result.optionStates[2])
        assertEquals("", result.userAnswerText)
    }

    // ── 填空 ────────────────────────────────────────────────────────────────

    @Test
    fun `填空任一可接受答案命中即判对且做规范化`() {
        val q = question(
            type = QuestionType.FILL,
            answerTextJson = """["1,000","Beijing"]""",
        )
        val fullWidth = QuestionGrader.grade(q, QuizResponse.Fill(" １，０００。 "))
        assertTrue("全角数字/逗号/句号应归一后命中", fullWidth.correct)
        assertFalse(fullWidth.needsSelfJudge)

        val caseAndSpace = QuestionGrader.grade(q, QuizResponse.Fill("  beijing   "))
        assertTrue("大小写与首尾空白不应影响匹配", caseAndSpace.correct)
        assertEquals("1,000 / Beijing", caseAndSpace.correctAnswerText)
    }

    @Test
    fun `填空末尾问号感叹号分号与连续空白被忽略`() {
        val q = question(type = QuestionType.FILL, answerTextJson = """["hello world"]""")
        val result = QuestionGrader.grade(q, QuizResponse.Fill("  HELLO   World！？; "))
        assertTrue(result.correct)
    }

    @Test
    fun `填空全不命中默认判错但标记需要自评`() {
        val q = question(type = QuestionType.FILL, answerTextJson = """["北京","北平"]""")
        val result = QuestionGrader.grade(q, QuizResponse.Fill("南京"))
        assertFalse(result.correct)
        assertTrue(result.needsSelfJudge)
        assertEquals("北京 / 北平", result.correctAnswerText)
        assertEquals("南京", result.userAnswerText)
    }

    @Test
    fun `填空空作答不算对`() {
        val q = question(type = QuestionType.FILL, answerTextJson = """["北京"]""")
        val result = QuestionGrader.grade(q, QuizResponse.Fill("   "))
        assertFalse(result.correct)
        assertTrue(result.needsSelfJudge)
    }

    // ── 判断 ────────────────────────────────────────────────────────────────

    @Test
    fun `判断题用选项原文判定而不是字母`() {
        val q = question(
            answerIndex = 0,
            type = QuestionType.TRUE_FALSE,
            options = """["正确","错误"]""",
        )
        val right = QuestionGrader.grade(q, QuizResponse.Single(0))
        assertTrue(right.correct)
        assertEquals("正确", right.userAnswerText)
        assertEquals("正确", right.correctAnswerText)

        val wrong = QuestionGrader.grade(q, QuizResponse.Single(1))
        assertFalse(wrong.correct)
        assertEquals("错误", wrong.userAnswerText)
        assertEquals("正确", wrong.correctAnswerText)
    }

    // ── 计算 ────────────────────────────────────────────────────────────────

    @Test
    fun `计算题按最终答案规范化比对`() {
        val q = question(
            type = QuestionType.CALCULATION,
            answerTextJson = """["5/6","0.8333"]""",
        )
        assertTrue(QuestionGrader.grade(q, QuizResponse.Fill(" 5/6。 ")).correct)
        assertTrue(QuestionGrader.grade(q, QuizResponse.Fill("0.8333")).correct)
        val miss = QuestionGrader.grade(q, QuizResponse.Fill("0.83"))
        assertFalse(miss.correct)
        assertTrue(miss.needsSelfJudge)
    }

    // ── 简答/主观/材料：绝不自动判 ───────────────────────────────────────

    @Test
    fun `简答题永远要求自评并展示参考答案`() {
        val q = question(
            type = QuestionType.SHORT_ANSWER,
            answerTextJson = """["实践是认识的基础"]""",
        )
        val result = QuestionGrader.grade(q, QuizResponse.SelfAssess("我写了实践"))
        assertFalse("主观题不许自动判对", result.correct)
        assertTrue(result.needsSelfJudge)
        assertEquals(GradingMode.SELF_RUBRIC, result.gradingMode)
        assertEquals("实践是认识的基础", result.correctAnswerText)
        assertEquals("我写了实践", result.userAnswerText)
        assertTrue(result.optionStates.isEmpty())
    }

    @Test
    fun `主观题即使答案文本与参考答案相同也不自动判对`() {
        val q = question(
            type = QuestionType.ESSAY,
            answerTextJson = """["参考答案"]""",
        )
        val result = QuestionGrader.grade(q, QuizResponse.SelfAssess("参考答案"))
        assertFalse(result.correct)
        assertTrue(result.needsSelfJudge)
        assertEquals(GradingMode.SELF_RUBRIC, result.gradingMode)
    }
}
