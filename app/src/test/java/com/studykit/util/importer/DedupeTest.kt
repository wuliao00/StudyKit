package com.studykit.util.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [dedupeWords] / [dedupeStrings] 纯逻辑单元测试。
 *
 * 钉两条契约：一是「比较用归一化键、返回一律原文」——入库的必须是用户写的内容，
 * 不能是归一化后的残次品；二是批内互重也要挡（一次贴进两份相同清单很常见），
 * 因为 `words.word` 没有唯一索引，SQLite 不会替我们兜。
 */
class DedupeTest {

    private fun word(line: Int, text: String) = ImportItem.Word(line, text, text, "释义")

    @Test
    fun `与库内已有词重复的跳过并回报被跳过的词面`() {
        val result = dedupeWords(listOf(word(1, "apple"), word(2, "banana")), setOf("apple"))
        assertEquals(listOf("banana"), result.kept.map { it.word })
        assertEquals(listOf("apple"), result.skipped)
    }

    @Test
    fun `批内互重只保留第一条`() {
        val result = dedupeWords(listOf(word(1, "apple"), word(2, "apple"), word(3, "cherry")), emptySet())
        assertEquals(listOf(1, 3), result.kept.map { it.sourceLine })
        assertEquals(listOf("apple"), result.skipped)
    }

    @Test
    fun `大小写与首尾空白视为同一个词`() {
        val result = dedupeWords(listOf(word(1, "Apple"), word(2, "  apple ")), setOf("APPLE"))
        assertTrue(result.kept.isEmpty())
        assertEquals(listOf("Apple", "  apple "), result.skipped)
    }

    @Test
    fun `库内为空时全部保留且保持原顺序`() {
        val items = listOf(word(1, "a"), word(2, "b"), word(3, "c"))
        assertEquals(items, dedupeWords(items, emptySet()).kept)
    }

    @Test
    fun `normalizeKey 折叠空白并转小写`() {
        assertEquals("abc", normalizeKey(" A B C "))
    }

    @Test
    fun `dedupeStrings 对任意键列表同样工作`() {
        val result = dedupeStrings(listOf("x", "y", "x", " z "))
        // kept 必须是原文：归一化只用于比较，拿它入库会把内容改脏
        assertEquals(listOf("x", "y", " z "), result.kept)
        assertEquals(listOf("x"), result.skipped)
    }

    @Test
    fun `dedupeStrings 比较归一化键但返回原文`() {
        val result = dedupeStrings(listOf("题干 A", "题干A", "题干 b"))
        assertEquals(listOf("题干 A", "题干 b"), result.kept)
        assertEquals(listOf("题干A"), result.skipped)
    }
}
