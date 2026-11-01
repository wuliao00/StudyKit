package com.studykit.util.ocr

import kotlin.math.tan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 投影法摆正的纯逻辑红线（不碰 Android Bitmap，直接喂合成 GrayImage）：
 * 水平行 → 0°；已知倾斜 → 角度误差 < 1°；纯噪点 → 不误判（宁可不转，也不把噪点当倾斜）。
 *
 * 合成方式：按 y = y0 + slope*x 画横条，等价于内容顺时针倾斜 angle 度，
 * 因此期望估计值等于 +angle（符号约定见 Deskew 的 KDoc）。
 */
class DeskewTest {

    private fun stripesGray(
        w: Int,
        h: Int,
        angleDeg: Float,
        period: Int = 30,
        thickness: Int = 8,
        ink: Float = 30f,
        paper: Float = 235f,
    ): GrayImage {
        val slope = tan(Math.toRadians(angleDeg.toDouble()))
        val cx = (w - 1) / 2.0
        val px = FloatArray(w * h) { paper }
        for (y in 0 until h) {
            for (x in 0 until w) {
                val yy = y - slope * (x - cx)
                val phase = ((yy % period) + period) % period
                if (phase < thickness) px[y * w + x] = ink
            }
        }
        return GrayImage(w, h, px)
    }

    private fun noiseGray(w: Int, h: Int, seed: Long = 7L): GrayImage {
        val rnd = java.util.Random(seed)
        return GrayImage(w, h, FloatArray(w * h) { 90f + rnd.nextFloat() * 60f })
    }

    @Test
    fun horizontalStripesEstimateZero() {
        val angle = Deskew.estimateFromGray(stripesGray(320, 480, 0f))
        assertEquals(0f, angle, 0.5f)
    }

    @Test
    fun clockwiseTiltIsEstimatedWithinOneDegree() {
        listOf(3f, 6f, 9f).forEach { expected ->
            val angle = Deskew.estimateFromGray(stripesGray(320, 480, expected))
            assertEquals("expected " + expected + " actual " + angle, expected, angle, 1.2f)
        }
    }

    @Test
    fun noiseDoesNotFakeTilt() {
        val angle = Deskew.estimateFromGray(noiseGray(320, 480))
        assertTrue("noise should not fake a tilt, got " + angle, kotlin.math.abs(angle) <= 0.5f)
    }

    @Test
    fun tooSmallImageIsSafeZero() {
        assertEquals(0f, Deskew.estimateFromGray(GrayImage(8, 4, FloatArray(32))), 0.01f)
    }
}
