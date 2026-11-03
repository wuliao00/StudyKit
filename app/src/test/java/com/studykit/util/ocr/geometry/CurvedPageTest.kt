package com.studykit.util.ocr.geometry

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import android.graphics.BitmapFactory
import com.studykit.util.ocr.GrayImage
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import com.studykit.util.OcrResult
import com.studykit.util.PpOcrOnnxEngine
import com.studykit.util.OcrTextExtractor
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.sin

/**
 * v3.11 弯曲页检测/去弯曲：
 * - 合成灰度图里上下边界按正弦弯曲，应检测出曲率；
 * - 真实「深色桌面 + 弯纸 + 透视」测试图，检测/去弯曲后横线应被拉直；
 * - 铺满画面的清晰页/截图不该触发去弯曲。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CurvedPageTest {

    private fun curvedGray(w: Int = 320, h: Int = 420, amplitude: Float = 18f): GrayImage {
        val px = FloatArray(w * h) { 30f }
        for (x in 0 until w) {
            val offset = amplitude * sin(Math.PI * x / (w - 1)).toFloat()
            val top = (40 + offset).toInt()
            val bottom = (h - 50 + offset).toInt()
            for (y in top..bottom) {
                if (y in 0 until h) px[y * w + x] = 235f
            }
        }
        return GrayImage(w, h, px)
    }

    @Test
    fun detectsBowedPageBoundaries() {
        val curves = CurvedPageDetector.detectFromGray(curvedGray())
        assertNotNull("bowed page should be detected", curves)
        assertTrue("curvature should be significant: " + curves!!.curvatureRatio, curves.curvatureRatio > 0.012f)
    }

    @Test
    fun flatSyntheticPageIsNotDewarped() {
        assertNull(CurvedPageDetector.detectFromGray(curvedGray(amplitude = 0f)))
    }

    private fun bitmap(name: String): Bitmap {
        val stream = javaClass.classLoader!!.getResourceAsStream("ocr/" + name)
            ?: error("missing test resource: " + name)
        return BitmapFactory.decodeStream(stream) ?: error("decode failed: " + name)
    }

    @Test
    fun curvedPhotoIsDetectedDewarpedAndLineStraightened() {
        val source = bitmap("curved_page.png")
        val curves = CurvedPageDetector.detect(source)
        assertNotNull("curved photo should be detected", curves)
        val dewarped = CurvedPageDewarper.dewarp(source, curves!!)
        assertNotNull("dewarp should return a bitmap", dewarped)

        fun lineBow(bitmap: Bitmap): Int {
            // 找每一列在 y=70%..95% 区间内最暗的像素行，用峰谷差衡量横线弯曲
            val start = (bitmap.height * 0.70f).toInt()
            val end = (bitmap.height * 0.95f).toInt()
            var minRow = Int.MAX_VALUE
            var maxRow = -1
            var samples = 0
            var x = bitmap.width / 10
            while (x < bitmap.width * 9 / 10) {
                var bestRow = -1
                var bestLum = Int.MAX_VALUE
                for (y in start until end) {
                    val c = bitmap.getPixel(x, y)
                    val lum = (((c shr 16) and 0xFF) + ((c shr 8) and 0xFF) + (c and 0xFF)) / 3
                    if (lum < bestLum) {
                        bestLum = lum
                        bestRow = y
                    }
                }
                if (bestRow >= 0 && bestLum < 120) {
                    minRow = minOf(minRow, bestRow)
                    maxRow = maxOf(maxRow, bestRow)
                    samples++
                }
                x += 12
            }
            return if (samples > 5) maxRow - minRow else Int.MAX_VALUE
        }

        val before = lineBow(source)
        val after = lineBow(dewarped!!)
        assertTrue("line should exist before: " + before, before != Int.MAX_VALUE)
        assertTrue("line should exist after: " + after, after != Int.MAX_VALUE)
        // 不追求像素级完美直线：先钉住「明显改善」，避免把重采样噪声当成回归
        assertTrue("dewarp should straighten line: before=" + before + " after=" + after, after <= before * 0.8f)
    }

    @Test
    fun fullPageScreenshotIsNotDewarped() {
        assertNull(CurvedPageDetector.detect(bitmap("clear_page.png")))
    }

    @Test
    fun dewarpSyntheticMapping() {
        val bmp = android.graphics.Bitmap.createBitmap(200, 300, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        canvas.drawColor(android.graphics.Color.WHITE)
        val paint = android.graphics.Paint().apply { color = android.graphics.Color.RED }
        canvas.drawRect(0f, 100f, 200f, 104f, paint)
        canvas.drawRect(0f, 200f, 200f, 204f, paint)
        val top = IntArray(200) { 20 }
        val bottom = IntArray(200) { 280 }
        val curves = PageCurves(200, 300, top, bottom, 10, 189, 0.05f)
        val out = CurvedPageDewarper.dewarp(bmp, curves)
        assertNotNull(out)
        val expectedRow = (80f / 260f * out!!.height).toInt()
        val pixel = out.getPixel(out.width / 2, expectedRow)
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        assertTrue("red line should map to expected row: " + Integer.toHexString(pixel), r > 200 && g < 80)
    }





}
