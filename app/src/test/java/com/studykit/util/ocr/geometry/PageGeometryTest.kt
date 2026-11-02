package com.studykit.util.ocr.geometry

import com.studykit.util.ocr.GrayImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 页面检测的纯逻辑红线（不碰 Android Bitmap）：
 * 深色桌面上的亮纸张要能找出四角；铺满画面的白底/截图返回 null；
 * 太小的亮块（不是一张纸）也返回 null。
 */
class PageGeometryTest {

    private fun grayWithPage(
        w: Int = 240,
        h: Int = 300,
        page: IntArray,
        background: Float = 30f,
        paper: Float = 235f,
    ): GrayImage {
        val px = FloatArray(w * h) { background }
        for (y in page[1] until page[3]) {
            for (x in page[0] until page[2]) {
                px[y * w + x] = paper
            }
        }
        return GrayImage(w, h, px)
    }

    @Test
    fun detectsBrightPageOnDarkDesk() {
        val gray = grayWithPage(page = intArrayOf(30, 25, 205, 270))
        val quad = PageDetector.detectFromGray(gray)
        assertNotNull(quad)
        val q = quad!!
        assertEquals(30f, q.topLeft.x, 8f)
        assertEquals(25f, q.topLeft.y, 8f)
        assertEquals(205f, q.bottomRight.x, 8f)
        assertEquals(270f, q.bottomRight.y, 8f)
    }

    @Test
    fun fullBrightImageHasNoPageBorder() {
        val gray = GrayImage(200, 260, FloatArray(200 * 260) { 240f })
        assertNull(PageDetector.detectFromGray(gray))
    }

    @Test
    fun tinyBrightBlobIsNotAPage() {
        val gray = grayWithPage(w = 240, h = 300, page = intArrayOf(100, 120, 130, 150))
        assertNull(PageDetector.detectFromGray(gray))
    }

    @Test
    fun otsuSplitsDarkAndBright() {
        val pixels = FloatArray(200) { if (it < 100) 20f else 230f }
        val t = PageDetector.otsu(pixels)
        assertTrue("threshold should separate: " + t, t in 1f..229f)
    }

    @Test
    fun targetSizePreservesPageAspect() {
        val quad = PageQuad(
            topLeft = PagePoint(10f, 20f),
            topRight = PagePoint(610f, 20f),
            bottomRight = PagePoint(610f, 820f),
            bottomLeft = PagePoint(10f, 820f),
        )
        val (w, h) = PerspectiveCorrector.targetSize(quad)!!
        assertEquals(600, w)
        assertEquals(800, h)
        assertTrue(abs(w.toFloat() / h - 0.75f) < 0.01f)
    }
}
