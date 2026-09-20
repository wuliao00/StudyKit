package com.studykit.util.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DictBookParser] 的纯逻辑单元测试 —— 在线词库（kajweb/dict）两条解析路径。
 *
 * 钉的是实测来的三件事：词表文件整体**不是**合法 JSON（一行一个对象，坏一行只该坏一行）、
 * 释义缺失才算失败（例句缺失只留空，抽样里约 5% 的行本来就没有例句），
 * 以及目录里缺 `offlinedata` 的条目必须消失而不是变成一个点不动的行。
 */
class DictBookParserTest {

    private val canadaLine =
        """{"wordRank":2,"headWord":"Canada","content":{"word":{"wordHead":"Canada","wordId":"PEPXiaoXue3_2_2",""" +
            """"content":{"sentence":{"sentences":[{"sContent":"She was domiciliated in Canada.",""" +
            """"sCn":"她在加拿大定居。"}]},"usphone":"'kænədə","ukphone":"'kænədə",""" +
            """"phrase":{"phrases":[{"pContent":"air canada","pCn":"n. 加拿大航空公司"}]},""" +
            """"trans":[{"tranCn":"加拿大","descCn":"中释"}]}}},"bookId":"PEPXiaoXue3_2"}"""

    @Test
    fun `一条 NDJSON 解析出词与释义与例句`() {
        val item = (DictBookParser.parseEntry(canadaLine, 1) as LineResult.Ok).item as ImportItem.Word
        assertEquals("Canada", item.word)
        assertEquals("加拿大", item.meaning)
        assertEquals("She was domiciliated in Canada.", item.example)
    }

    @Test
    fun `多个词性的释义用分号拼接`() {
        val line = """{"headWord":"book","content":{"word":{"content":{"trans":[{"tranCn":"n. 书"},{"tranCn":"v. 预订"}]}}}}"""
        val item = (DictBookParser.parseEntry(line, 1) as LineResult.Ok).item as ImportItem.Word
        assertEquals("n. 书；v. 预订", item.meaning)
    }

    @Test
    fun `缺例句时例句为空串而不是失败`() {
        val line = """{"headWord":"UK","content":{"word":{"content":{"trans":[{"tranCn":"英国"}]}}}}"""
        val item = (DictBookParser.parseEntry(line, 3) as LineResult.Ok).item as ImportItem.Word
        assertEquals("", item.example)
    }

    @Test
    fun `缺释义进待修正而不是丢弃`() {
        val line = """{"headWord":"weird","content":{"word":{"content":{}}}}"""
        val result = DictBookParser.parseEntry(line, 9)
        assertTrue(result is LineResult.Bad)
        assertEquals(9, (result as LineResult.Bad).rejected.sourceLine)
        assertEquals(RejectReason.EMPTY_FIELDS, result.rejected.reason)
    }

    @Test
    fun `残缺或非法 JSON 行进待修正`() {
        assertTrue(DictBookParser.parseEntry("{不是 json", 2) is LineResult.Bad)
    }

    @Test
    fun `parseNdjson 逐行编号并汇总`() {
        val plan = DictBookParser.parseNdjson("$canadaLine\n\n$canadaLine")
        assertEquals(2, plan.items.size)
        assertEquals(listOf(1, 3), plan.items.map { it.sourceLine })
        assertEquals(1, plan.blankCount)
    }

    @Test
    fun `目录 JSON 解析出词库条目与下载地址`() {
        val json = """
            {"reason":"succ","code":200,"data":{"normalBooksInfo":[
              {"id":"CET4luan_1","title":"四级真题核心词","wordNum":1162,"size":788457,
               "introduce":"有道词频统计","cover":"https://x/y.jpg",
               "tags":[{"tagName":"四级"},{"tagName":"有道"}],
               "offlinedata":"http://ydschool-online.nos.netease.com/1_CET4luan_1.zip"}
            ]}}
        """.trimIndent()
        val books = DictBookParser.parseCatalogue(json)
        assertEquals(1, books.size)
        val book = books[0]
        assertEquals("CET4luan_1", book.id)
        assertEquals("四级真题核心词", book.title)
        assertEquals(1162, book.wordNum)
        assertEquals(listOf("四级", "有道"), book.tags)
        assertEquals("http://ydschool-online.nos.netease.com/1_CET4luan_1.zip", book.downloadUrl)
    }

    @Test
    fun `目录里缺 offlinedata 的条目被丢弃而非崩溃`() {
        val json = """{"data":{"normalBooksInfo":[{"id":"x","title":"y"},{"id":"a","title":"b","offlinedata":"http://h/a.zip"}]}}"""
        val books = DictBookParser.parseCatalogue(json)
        assertEquals(listOf("a"), books.map { it.id })
    }
}
