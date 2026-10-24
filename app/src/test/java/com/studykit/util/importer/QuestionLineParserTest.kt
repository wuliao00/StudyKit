package com.studykit.util.importer

import com.studykit.data.question.ExamStage
import com.studykit.data.question.QuestionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuestionLineParserTest {

    private fun ok(raw: String): ImportItem.Question =
        (QuestionLineParser.parseLine(1, raw) as LineResult.Ok).item as ImportItem.Question

    @Test
    fun `四选项与字母答案`() {
        val item = ok("1/2 + 1/3 = ?|5/6|1/6|1|2/5|A")
        assertEquals("1/2 + 1/3 = ?", item.stem)
        assertEquals(listOf("5/6", "1/6", "1", "2/5"), item.options)
        assertEquals(0, item.answerIndex)
        assertEquals("", item.explanation)
    }

    @Test
    fun `答案写成选项原文也能定位下标`() {
        val item = ok("下面哪个是过去式？|went|go|goed|going|went")
        assertEquals(0, item.answerIndex)
    }

    @Test
    fun `多出一列时按「解析」处理，答案仍是倒数第二段可解析项`() {
        val item = ok("题干？|选项一|选项二|B|应该选第二项")
        assertEquals(1, item.answerIndex)
        assertEquals("应该选第二项", item.explanation)
    }

    @Test
    fun `小写与带空格的答案都能识别`() {
        assertEquals(2, ok("Q|a|b|c|d| c ").answerIndex)
    }

    @Test
    fun `答案越界进待修正`() {
        val result = QuestionLineParser.parseLine(3, "Q|a|b|E")
        assertTrue(result is LineResult.Bad)
        assertEquals(RejectReason.ANSWER_NOT_FOUND, (result as LineResult.Bad).rejected.reason)
        assertEquals(3, result.rejected.sourceLine)
    }

    @Test
    fun `答案写成不存在的选项原文进待修正`() {
        assertTrue(
            QuestionLineParser.parseLine(1, "Q|a|b|z") is LineResult.Bad,
        )
    }

    @Test
    fun `少于两个选项时判为列数不足`() {
        val result = QuestionLineParser.parseLine(2, "只有题干|A")
        assertTrue(result is LineResult.Bad)
        assertEquals(RejectReason.TOO_FEW_COLUMNS, (result as LineResult.Bad).rejected.reason)
    }

    @Test
    fun `空行返回 Blank`() {
        assertEquals(LineResult.Blank, QuestionLineParser.parseLine(1, "  "))
    }

    @Test
    fun `parseAll 汇总可用与待修正`() {
        val plan = QuestionLineParser.parseAll("Q|a|b|A\n坏行|只有一列")
        assertEquals(1, plan.items.size)
        assertEquals(1, plan.rejected.size)
        assertEquals(2, plan.rejected[0].sourceLine)
    }

    // ── v10 题型 ─────────────────────────────────────────────────────────────

    @Test
    fun `无前缀旧格式完全不变且默认单选`() {
        val item = ok("题干|A|B|C|D|B")
        assertEquals(QuestionType.SINGLE, item.type)
        assertEquals(listOf(1), item.answerIndices)
        assertTrue(item.acceptedAnswers.isEmpty())
        assertEquals("题干", item.stem)
    }

    @Test
    fun `单选前缀被剥掉且不进题干`() {
        val item = ok("[单选] 题干|A|B|C|D|A")
        assertEquals("题干", item.stem)
        assertEquals(QuestionType.SINGLE, item.type)
        assertEquals(0, item.answerIndex)
    }

    @Test
    fun `多选四种答案写法都识别`() {
        listOf("AC", "A,C", "A、C", "A C").forEach { answer ->
            val item = ok("[多选] 题干|甲|乙|丙|丁|$answer")
            assertEquals("答案写法：$answer", QuestionType.MULTIPLE, item.type)
            assertEquals("答案写法：$answer", listOf(0, 2), item.answerIndices)
            assertEquals(0, item.answerIndex)
            assertEquals(listOf("甲", "乙", "丙", "丁"), item.options)
        }
    }

    @Test
    fun `多选带解析时倒数第二列当答案`() {
        val item = ok("[多选] 题干|甲|乙|丙|丁|A,C|两个正确项")
        assertEquals(QuestionType.MULTIPLE, item.type)
        assertEquals(listOf(0, 2), item.answerIndices)
        assertEquals("两个正确项", item.explanation)
        assertEquals(listOf("甲", "乙", "丙", "丁"), item.options)
    }

    @Test
    fun `多选只给一个正确项被拒`() {
        val result = QuestionLineParser.parseLine(1, "[多选] 题干|甲|乙|丙|丁|A")
        assertTrue(result is LineResult.Bad)
        assertEquals(RejectReason.ANSWER_NOT_FOUND, (result as LineResult.Bad).rejected.reason)
    }

    @Test
    fun `填空单个答案`() {
        val item = ok("[填空] 2 + 2 = ?|4")
        assertEquals(QuestionType.FILL, item.type)
        assertEquals("2 + 2 = ?", item.stem)
        assertEquals(listOf("4"), item.acceptedAnswers)
        assertEquals(0, item.answerIndex)
        assertTrue(item.options.isEmpty())
        assertEquals("", item.explanation)
    }

    @Test
    fun `填空多个答案支持半角与全角分号`() {
        val item = ok("[填空] 首都是哪里？|北京;Beijing；北平")
        assertEquals(listOf("北京", "Beijing", "北平"), item.acceptedAnswers)
    }

    @Test
    fun `填空带解析`() {
        val item = ok("[填空] 首都是哪里？|北京;Beijing|两种写法都算对")
        assertEquals(listOf("北京", "Beijing"), item.acceptedAnswers)
        assertEquals("两种写法都算对", item.explanation)
    }

    @Test
    fun `填空没有非空答案被拒`() {
        val result = QuestionLineParser.parseLine(1, "[填空] 题干|;；")
        assertTrue(result is LineResult.Bad)
        assertEquals(RejectReason.ANSWER_NOT_FOUND, (result as LineResult.Bad).rejected.reason)
    }

    @Test
    fun `前缀不会留在题干里`() {
        assertEquals("题干", ok("[填空] 题干|答案").stem)
        assertEquals("题干", ok("[多选] 题干|甲|乙|丙|AC").stem)
    }

    // ── v3.3 阶段 × 题型 ───────────────────────────────────────────────────

    @Test
    fun `三段前缀解析阶段学科题型`() {
        val item = ok("[高中·数学·单选] 题干|A|B|C|D|B")
        assertEquals(ExamStage.SENIOR, item.stage)
        assertEquals("数学", item.subject)
        assertEquals(QuestionType.SINGLE, item.type)
        assertEquals("题干", item.stem)
    }

    @Test
    fun `两段前缀解析阶段加题型`() {
        val item = ok("[初中·多选] 题干|甲|乙|丙|AC")
        assertEquals(ExamStage.JUNIOR, item.stage)
        assertEquals("", item.subject)
        assertEquals(QuestionType.MULTIPLE, item.type)
    }

    @Test
    fun `判断题答案正确与错误归一化`() {
        val wrong = ok("[初中·物理·判断] 地球是平的|错误|解析")
        assertEquals(QuestionType.TRUE_FALSE, wrong.type)
        assertEquals(listOf("正确", "错误"), wrong.options)
        assertEquals(1, wrong.answerIndex)
        assertEquals("解析", wrong.explanation)

        val right = ok("[高中·化学·判断] 水是化合物|正确")
        assertEquals(0, right.answerIndex)
    }

    @Test
    fun `计算题按最终答案入库`() {
        val item = ok("[考研·数学一·计算] 1/2+1/3=?|5/6;0.8333|解析")
        assertEquals(QuestionType.CALCULATION, item.type)
        assertEquals(ExamStage.POSTGRAD, item.stage)
        assertEquals("数学一", item.subject)
        assertEquals(listOf("5/6", "0.8333"), item.acceptedAnswers)
        assertEquals("解析", item.explanation)
    }

    @Test
    fun `简答题解析参考答案与评分要点`() {
        val item = ok("[考研·政治·简答] 简述实践与认识的关系|实践是认识的基础|评分要点|补充")
        assertEquals(QuestionType.SHORT_ANSWER, item.type)
        assertEquals(listOf("实践是认识的基础"), item.acceptedAnswers)
        assertEquals("评分要点|补充", item.explanation)
    }

    @Test
    fun `主观题不自动判且保留材料题干`() {
        val item = ok("[考公·申论·主观] 根据材料写一段对策|参考答案|采分点")
        assertEquals(QuestionType.ESSAY, item.type)
        assertEquals(ExamStage.CIVIL_SERVICE, item.stage)
        assertEquals("申论", item.subject)
        assertEquals("根据材料写一段对策", item.stem)
        assertEquals(listOf("参考答案"), item.acceptedAnswers)
        assertEquals("采分点", item.explanation)
    }

    @Test
    fun `未知阶段前缀进待修正且不污染题干`() {
        val result = QuestionLineParser.parseLine(1, "[火星·数学·单选] 题干|A|B|C|D|A")
        assertTrue(result is LineResult.Bad)
        assertEquals(RejectReason.UNKNOWN_PREFIX, (result as LineResult.Bad).rejected.reason)
    }
}
