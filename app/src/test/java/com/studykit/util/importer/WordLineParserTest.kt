package com.studykit.util.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WordLineParserTest {

    private fun ok(lineNo: Int, raw: String): ImportItem.Word =
        (WordLineParser.parseLine(lineNo, raw) as LineResult.Ok).item as ImportItem.Word

    @Test
    fun `制表符分三列：词、释义、例句`() {
        val item = ok(1, "abandon\tv. 放弃\tHe had to abandon the plan.")
        assertEquals("abandon", item.word)
        assertEquals("v. 放弃", item.meaning)
        assertEquals("He had to abandon the plan.", item.example)
    }

    @Test
    fun `制表符分两列时例句为空串而非 null`() {
        val item = ok(1, "apple\tn. 苹果")
        assertEquals("", item.example)
    }

    @Test
    fun `连续两个以上空格优先于逗号分列`() {
        val item = ok(1, "benefit    n. 利益    双空格分列的例句")
        assertEquals("benefit", item.word)
        assertEquals("n. 利益", item.meaning)
        assertEquals("双空格分列的例句", item.example)
    }

    @Test
    fun `逗号分列上限三列，释义内含逗号不被切碎`() {
        val item = ok(1, "effect,n. 效果；作用,This had no effect on me.")
        assertEquals("effect", item.word)
        assertEquals("n. 效果；作用", item.meaning)
        assertEquals("This had no effect on me.", item.example)
    }

    @Test
    fun `全角逗号同样可分列`() {
        val item = ok(1, "UK，英国")
        assertEquals("UK", item.word)
        assertEquals("英国", item.meaning)
    }

    @Test
    fun `单空格兜底只切第一刀，其余全归释义`() {
        val item = ok(1, "abandon v. 放弃；抛弃")
        assertEquals("abandon", item.word)
        assertEquals("v. 放弃；抛弃", item.meaning)
        assertEquals("", item.example)
    }

    @Test
    fun `词头里的重音字母归一为 ASCII`() {
        val item = ok(1, "café\tn. 咖啡馆")
        assertEquals("cafe", item.word)
    }

    @Test
    fun `只有词没有释义时进待修正而不是丢弃`() {
        val result = WordLineParser.parseLine(7, "lonely")
        assertTrue(result is LineResult.Bad)
        val bad = result as LineResult.Bad
        assertEquals(7, bad.rejected.sourceLine)
        assertEquals(RejectReason.TOO_FEW_COLUMNS, bad.rejected.reason)
        assertEquals("lonely", bad.rejected.raw)
    }

    @Test
    fun `制表符后为空也算缺释义`() {
        assertTrue(WordLineParser.parseLine(1, "apple\t") is LineResult.Bad)
    }

    @Test
    fun `空行返回 Blank 且不计入任何统计`() {
        assertEquals(LineResult.Blank, WordLineParser.parseLine(1, ""))
        assertEquals(LineResult.Blank, WordLineParser.parseLine(1, "   "))
    }

    @Test
    fun `parseAll 保留原行号，空行只占号不产项`() {
        val plan = WordLineParser.parseAll("apple\t苹果\n\n\nbanana\t香蕉")
        assertEquals(2, plan.items.size)
        assertEquals(listOf(1, 4), plan.items.map { it.sourceLine })
        assertEquals(2, plan.blankCount)
        assertEquals(0, plan.rejected.size)
    }

    @Test
    fun `parseAll 同时收可用行与待修正行`() {
        val plan = WordLineParser.parseAll("apple\t苹果\nbadline\ncherry\tn. 樱桃")
        assertEquals(2, plan.items.size)
        assertEquals(1, plan.rejected.size)
        assertEquals(2, plan.rejected[0].sourceLine)
        assertEquals(3, plan.totalMeaningful)
    }
}
