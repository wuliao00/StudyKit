package com.studykit.util.ocr.geometry

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 用渲染好的「深色桌面 + 透视纸张」照片验证检测与校正：
 * - 两张不同角度的透视页都能检测出四角；
 * - 校正后尺寸接近 A4 比例、四角是纸面亮度（没有被裁掉内容）；
 * - 铺满画面的清晰页/截图不触发校正。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PerspectiveBitmapTest {

    private fun bitmap(name: String): Bitmap {
        val stream = javaClass.classLoader!!.getResourceAsStream("ocr/" + name)
            ?: error("missing test resource: " + name)
        return BitmapFactory.decodeStream(stream) ?: error("decode failed: " + name)
    }

    private fun cornerBrightness(bitmap: Bitmap): Float {
        val patch = 24
        var sum = 0.0
        var count = 0
        val xs = intArrayOf(patch, bitmap.width - patch * 2)
        val ys = intArrayOf(patch, bitmap.height - patch * 2)
        for (y0 in ys) {
            for (x0 in xs) {
                for (y in y0 until y0 + patch) {
                    for (x in x0 until x0 + patch) {
                        val c = bitmap.getPixel(x, y)
                        sum += 0.299 * ((c shr 16) and 0xFF) +
                            0.587 * ((c shr 8) and 0xFF) +
                            0.114 * (c and 0xFF)
                        count++
                    }
                }
            }
        }
        return (sum / count).toFloat()
    }

    @Test
    fun perspectivePhotosAreDetectedAndCorrected() {
        for (name in listOf("perspective_page.png", "perspective_page2.png")) {
            val source = bitmap(name)
            val quad = PageDetector.detect(source)
            assertNotNull("should detect page corners in " + name, quad)
            val corrected = PerspectiveCorrector.correct(source, quad!!)
            assertNotNull("should correct " + name, corrected)
            val ratio = corrected!!.width.toFloat() / corrected.height
            assertTrue("A4-like aspect in " + name + ": " + ratio, ratio in 0.6f..0.95f)
            val brightness = cornerBrightness(corrected)
            assertTrue("corners should be paper-like in " + name + ": " + brightness, brightness > 140f)
        }
    }

    @Test
    fun fullPageScreenshotIsNotPerspectiveCorrected() {
        val source = bitmap("clear_page.png")
        assertNull("screenshot should not trigger page correction", PageDetector.detect(source))
    }
}
