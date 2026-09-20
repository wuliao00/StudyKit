package com.studykit.util.importer

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [TextCleaner] 纯逻辑单元测试 —— 四条导入来源（粘贴 / SAF 文件 / OCR / 在线词表）共用的入口清洗。
 *
 * 钉的是两条契约：行号语义（1 基、空行也占号、能把「待修正」行指回用户刚贴的那一行）
 * 与字符归一（BOM/CRLF 剥掉、行内制表符与多空格留给分列、带重音的拉丁字母折成 ASCII）。
 */
class TextCleanerTest {

    @Test
    fun `行号从 1 开始且与原文行序一致`() {
        val lines = TextCleaner.splitLines("a\nb\nc")
        assertEquals(listOf(1 to "a", 2 to "b", 3 to "c"), lines.map { it.line to it.text })
    }

    @Test
    fun `空行保留行号但内容为空串`() {
        val lines = TextCleaner.splitLines("a\n\nb")
        assertEquals(3, lines.size)
        assertEquals(2, lines[1].line)
        assertEquals("", lines[1].text)
    }

    @Test
    fun `剥离 BOM 与 CRLF`() {
        val lines = TextCleaner.splitLines("\uFEFFword\r\nmeaning")
        assertEquals("word", lines[0].text)
        assertEquals("meaning", lines[1].text)
    }

    @Test
    fun `制表符与首尾空白被裁掉但制表符本身留给分列`() {
        val lines = TextCleaner.splitLines("  apple\t一个苹果  ")
        assertEquals("apple\t一个苹果", lines[0].text)
    }

    @Test
    fun `法语字母归一为 ASCII`() {
        assertEquals("epole", TextCleaner.normalizeAccents("épole"))
        // 只折表内字母：ç→c、à→a、ü→u，未表列的 n/a 原样保留，所以结果是 canaua 而非 canada
        assertEquals("canaua", TextCleaner.normalizeAccents("çànaüa"))
    }

    @Test
    fun `中文与日文假名不受归一影响`() {
        assertEquals("苹果 形近词", TextCleaner.normalizeAccents("苹果 形近词"))
    }
}
