package com.studykit.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 统计磁贴大数字的字号阶梯。
 *
 * 这个 bug 是**静默**的：1177 词显示成「117」，数值本身在库里、在 a11y 树里都是对的，
 * 只有像素上少一位 —— 单测查不出、用户只会以为数据丢了。2026-09-24 真机走查撞见两处
 * （学习首页「单词总数」1177→117、习惯日历「1250 ml」→1250）。
 * 这里钉的是"长值必须让位"这条规则，以及"今天显示正常的短值一个都不许变小"。
 */
class StatTileTest {

    private val base = 34f

    // ── 短值不许退化 ────────────────────────────────────────────

    @Test
    fun `values up to three chars keep the base size`() {
        // 现在显示正常的那些：19、5、1、"6 天"、"0 天" —— 修这个 bug 不许把它们改小
        assertEquals(base, statValueFontSizeSp("1", base), 0.001f)
        assertEquals(base, statValueFontSizeSp("19", base), 0.001f)
        assertEquals(base, statValueFontSizeSp("6 天", base), 0.001f)
        assertEquals(base, statValueFontSizeSp("999", base), 0.001f)
    }

    // ── 长值必须缩档 ────────────────────────────────────────────

    @Test
    fun `four digits shrink because that is where 34sp starts clipping`() {
        // 1177 是实际被裁成 117 的那个值
        assertTrue("4 位数必须小于基准，否则还是会被裁", statValueFontSizeSp("1177", base) < base)
    }

    @Test
    fun `shrink is monotonic as the string grows`() {
        val ladder = (1..9).map { statValueFontSizeSp("1".repeat(it), base) }
        ladder.zipWithNext().forEachIndexed { i, (smaller, bigger) ->
            assertTrue("长度 ${i + 1}→${i + 2} 出现字号变大，阶梯不单调", smaller >= bigger)
        }
    }

    @Test
    fun `every rung is strictly smaller once past three chars`() {
        // 单调不增还不够 —— 平推意味着 7 字符和 4 字符一样大，仍然会裁
        val four = statValueFontSizeSp("1".repeat(4), base)
        val seven = statValueFontSizeSp("1".repeat(7), base)
        assertTrue("越长却不越缩等于没缩", seven < four)
    }

    @Test
    fun `unit-suffixed values land on the same rung as their length`() {
        // "1250 ml" 是第二处被裁的实际值（7 字符）
        assertEquals(
            statValueFontSizeSp("1".repeat(7), base),
            statValueFontSizeSp("1250 ml", base),
            0.001f,
        )
    }
}
