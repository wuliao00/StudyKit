package com.studykit.util.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteLineParserTest {

    private fun ok(raw: String): ImportItem.Excerpt =
        (NoteLineParser.parseLine(1, raw) as LineResult.Ok).item as ImportItem.Excerpt

    @Test
    fun `三段齐全`() {
        val item = ok("人类简史|农业革命是史上最大的骗局|可对照《枪炮病菌与钢铁》")
        assertEquals("人类简史", item.book)
        assertEquals("农业革命是史上最大的骗局", item.excerpt)
        assertEquals("可对照《枪炮病菌与钢铁》", item.thought)
    }

    @Test
    fun `缺感想时为空串`() {
        assertEquals("", ok("人类简史|摘录内容").thought)
    }

    @Test
    fun `竖线出现在摘录里时只按第一个与最后一个切分`() {
        val item = ok("书名|摘录|内含|竖线|感想")
        assertEquals("书名", item.book)
        assertEquals("摘录|内含|竖线", item.excerpt)
        assertEquals("感想", item.thought)
    }

    @Test
    fun `只有书名判为列数不足`() {
        val result = NoteLineParser.parseLine(4, "只有书名")
        assertTrue(result is LineResult.Bad)
        assertEquals(RejectReason.TOO_FEW_COLUMNS, (result as LineResult.Bad).rejected.reason)
        assertEquals(4, result.rejected.sourceLine)
    }

    @Test
    fun `结构在但书名为空时报空字段而非列数不足`() {
        val result = NoteLineParser.parseLine(1, "|摘录|感想")
        assertTrue(result is LineResult.Bad)
        assertEquals(RejectReason.EMPTY_FIELDS, (result as LineResult.Bad).rejected.reason)
    }

    @Test
    fun `结构在但摘录为空时同样报空字段`() {
        val result = NoteLineParser.parseLine(1, "书名||感想")
        assertEquals(RejectReason.EMPTY_FIELDS, (result as LineResult.Bad).rejected.reason)
    }

    @Test
    fun `空行返回 Blank 且 parseAll 保留行号`() {
        assertEquals(LineResult.Blank, NoteLineParser.parseLine(1, ""))
        val plan = NoteLineParser.parseAll("a|b\n\nc|d")
        assertEquals(listOf(1, 3), plan.items.map { it.sourceLine })
        assertEquals(1, plan.blankCount)
    }
}
