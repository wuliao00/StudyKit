package com.studykit.util.importer

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
}
