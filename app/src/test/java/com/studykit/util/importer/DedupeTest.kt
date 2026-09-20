package com.studykit.util.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [dedupeWords] / [dedupeBy] 纯逻辑单元测试。
 *
 * 钉三条契约：一是「比较用归一化键、返回一律原文」——入库的必须是用户写的内容，
 * 不能是归一化后的残次品；二是批内互重也要挡（一次贴进两份相同清单很常见），
 * 因为 `words.word` 没有唯一索引，SQLite 不会替我们兜；
 * 三是去重必须**按下标回指原条目**，用「保留键做成 Set 再过滤」的写法会把重复的第二条放回去。
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
    fun `dedupeBy 按下标回指，批内重复的第二条不会混进 kept`() {
        // 「把保留的键做成 Set 再用 in 过滤」是这里最容易写错的地方：
        // 重复的第二条其键也在 Set 里，于是照样入选 —— 一次贴两份相同清单就会写双份。
        val items = listOf("x|a|b", "x|a|b", "y|a|b")
        val result = dedupeBy(items, keyOf = { it.substringBefore('|') }, displayOf = { it })
        assertEquals(listOf("x|a|b", "y|a|b"), result.kept)
        assertEquals(listOf("x|a|b"), result.skipped)
    }

    @Test
    fun `dedupeBy 的 skipped 用 displayOf 而不是 key`() {
        val result = dedupeBy(
            listOf(3 to "apple", 9 to "Apple"),
            keyOf = { normalizeKey(it.second) },
            displayOf = { "第 ${it.first} 行" },
        )
        assertEquals(listOf(3 to "apple"), result.kept)
        assertEquals(listOf("第 9 行"), result.skipped)
    }

    @Test
    fun `dedupeBy 用已归一化的 existingKeys 判库内重复`() {
        val result = dedupeBy(
            listOf("A", "b"),
            keyOf = { normalizeKey(it) },
            displayOf = { it },
            existingKeys = setOf("a"),
        )
        assertEquals(listOf("b"), result.kept)
        assertEquals(listOf("A"), result.skipped)
    }

    @Test
    fun `dedupeBy 不传 existingKeys 时只做批内去重`() {
        val result = dedupeBy(listOf("x", "X", "y"), keyOf = { normalizeKey(it) }, displayOf = { it })
        // kept 装原文，归一化只用于比较，否则入库内容会被改脏
        assertEquals(listOf("x", "y"), result.kept)
        assertEquals(listOf("X"), result.skipped)
    }
}
