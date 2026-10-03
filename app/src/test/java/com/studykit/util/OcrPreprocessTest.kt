package com.studykit.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OCR 输入预处理的**纯函数**层（`OcrTextExtractor.kt` 顶部）。
 *
 * 为什么这三条值得单独钉：它们决定"要不要放大、放大多少、裁哪一段"，
 * 而 ML Kit 的识别质量对这些参数是**非线性**的（字高 <16px 明显掉、>24px 不再涨）。
 * 真机上"这张照片认得好不好"没法机检，能机检的就是这三个数字口径。
 */
class OcrPreprocessTest {

    // ── ocrScaleFactor：ML Kit 文档口径 ≥16×16 才认得清，>24×24 不再涨 ──────
    @Test
    fun 小字八像素放大到约二十四像素() {
        assertEquals(3f, ocrScaleFactor(imgHeightPx = 800, glyphHeightPx = 8), 0.001f)
    }

    @Test
    fun 大字三十像素不再放大() {
        assertEquals(1f, ocrScaleFactor(imgHeightPx = 800, glyphHeightPx = 30), 0.001f)
    }

    @Test
    fun 十六像素是免放大的下界() {
        assertEquals(1f, ocrScaleFactor(imgHeightPx = 800, glyphHeightPx = 16), 0.001f)
        assertTrue(ocrScaleFactor(imgHeightPx = 800, glyphHeightPx = 15) > 1f)
    }

    @Test
    fun 放大倍率封顶四倍() {
        // 4px 字按目标 24px 本该 6 倍，必须被 4f 的上限压住（图高 600px ⇒ 不碰内存闸门）
        assertEquals(4f, ocrScaleFactor(imgHeightPx = 600, glyphHeightPx = 4), 0.001f)
        assertEquals(4f, ocrScaleFactor(imgHeightPx = 600, glyphHeightPx = 1), 0.001f)
    }

    @Test
    fun 荒谬输入退化成不放大且不抛() {
        assertEquals(1f, ocrScaleFactor(imgHeightPx = 0, glyphHeightPx = 0), 0.001f)
        assertEquals(1f, ocrScaleFactor(imgHeightPx = -1, glyphHeightPx = 8), 0.001f)
        assertEquals(1f, ocrScaleFactor(imgHeightPx = 800, glyphHeightPx = -3), 0.001f)
        assertEquals(1f, ocrScaleFactor(imgHeightPx = 800, glyphHeightPx = 0), 0.001f)
        assertEquals(1f, ocrScaleFactor(imgHeightPx = Int.MAX_VALUE, glyphHeightPx = Int.MAX_VALUE), 0.001f)
    }

    @Test
    fun 超高图不为了小字把整张乘上去() {
        // 12000px 高再乘 3 就是 36000px（远超识别有用的尺寸，先炸内存）⇒ 压回 1
        assertEquals(1f, ocrScaleFactor(imgHeightPx = 12_000, glyphHeightPx = 8), 0.001f)
    }

    // ── medianGlyphRowRun：逐行墨迹游程 → 字高（行）估计 ────────────────────
    @Test
    fun 三段三像素高的文字估成三() {
        // 1=墨行、0=空行：三段游程长度都是 3，取中位数仍是 3
        val rows = intArrayOf(0, 0, 1, 1, 1, 0, 0, 1, 1, 1, 0, 0, 1, 1, 1)
        assertEquals(3, medianGlyphRowRun(rows, inkThreshold = 1))
    }

    @Test
    fun 长短不一时取中位数而不是平均() {
        // 游程 2 / 2 / 9（那条 9 行多半是表格竖线或一张插图），中位数 2 才代表字高
        val rows = intArrayOf(1, 1, 0, 0, 1, 1, 0, 0) + IntArray(9) { 1 }
        assertEquals(2, medianGlyphRowRun(rows, inkThreshold = 1))
    }

    @Test
    fun 单行空隙不切断同一个字() {
        // 汉字的横画之间常有 1 行空隙：3 行 + 空 1 行 + 1 行 应并成一段 5 行
        val rows = intArrayOf(1, 1, 1, 0, 1, 0, 0, 0)
        assertEquals(5, medianGlyphRowRun(rows, inkThreshold = 1))
    }

    @Test
    fun 墨迹门槛以下算空行() {
        // 每行只有 1 个暗点（扫描件噪点）时，门槛设 3 就该判"没有文字"
        val rows = intArrayOf(1, 2, 1, 2, 1)
        assertEquals(0, medianGlyphRowRun(rows, inkThreshold = 3))
        assertTrue(medianGlyphRowRun(rows, inkThreshold = 1) > 0)
    }

    @Test
    fun 全空没有文字估成零() {
        assertEquals(0, medianGlyphRowRun(IntArray(20), inkThreshold = 1))
    }

    // ── generousTextRowRange：只在文字区明显占小部分时才裁，且留足余量 ──────
    @Test
    fun 文字区上下各留一行余量() {
        val rows = intArrayOf(0, 0, 0, 1, 1, 1, 0, 0, 0, 0)
        assertEquals(2..6, generousTextRowRange(rows, inkThreshold = 1, padRows = 1))
    }

    @Test
    fun 余量越界时贴着边界不溢出() {
        // 墨迹占 1..3，余量 3 行 ⇒ 上边界只能到 0、下边界只能到最后一行（4）
        val rows = intArrayOf(0, 1, 1, 1, 1)
        assertEquals(0..4, generousTextRowRange(rows, inkThreshold = 1, padRows = 3))
    }

    @Test
    fun 满幅文字就是整段范围调用方据此不裁() {
        val rows = intArrayOf(1, 1, 1, 1)
        assertEquals(0..3, generousTextRowRange(rows, inkThreshold = 1, padRows = 0))
    }

    @Test
    fun 没有墨迹返回空表示不裁() {
        assertNull(generousTextRowRange(IntArray(10), inkThreshold = 1, padRows = 2))
    }
}
