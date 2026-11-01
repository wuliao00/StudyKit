package com.studykit.util.ocr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 质量门控的阈值红线：每一类问题都用一张"这类照片的极端代表"钉住，
 * 并且专门为两类**常见误报**写了反例 ——
 * 1. 深色主题截图（均值低但对比度高）不能被判成"画面偏暗"；
 * 2. 黑底白字的细笔画不能被判成"有反光"（亮像素占比不低，但连通块都很小）。
 */
class PhotoQualityAnalyzerTest {

    private fun uniform(w: Int = 320, h: Int = 480, value: Float): GrayImage =
        GrayImage(w, h, FloatArray(w * h) { value })

    private fun stripes(
        w: Int = 320,
        h: Int = 480,
        ink: Float = 30f,
        paper: Float = 235f,
        period: Int = 30,
        thickness: Int = 8,
    ): GrayImage {
        val px = FloatArray(w * h) { paper }
        for (y in 0 until h) {
            val phase = y % period
            if (phase < thickness) {
                for (x in 0 until w) px[y * w + x] = ink
            }
        }
        return GrayImage(w, h, px)
    }

    private fun glyphBlocks(
        w: Int = 320,
        h: Int = 480,
        background: Float = 15f,
        ink: Float = 240f,
        cell: Int = 20,
        glyph: Int = 8,
    ): GrayImage {
        val px = FloatArray(w * h) { background }
        for (y in 0 until h) {
            for (x in 0 until w) {
                val gx = x % cell
                val gy = y % cell
                if (gx < glyph && gy < glyph) px[y * w + x] = ink
            }
        }
        return GrayImage(w, h, px)
    }

    /** 5x5 盒式模糊，模拟失焦/手抖；做两遍让拉普拉斯方差确实掉到阈值下。 */
    private fun boxBlur(image: GrayImage, passes: Int = 2): GrayImage {
        var cur = image
        repeat(passes) {
            val w = cur.width
            val h = cur.height
            val out = FloatArray(w * h)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    var sum = 0f
                    var count = 0
                    for (dy in -2..2) {
                        for (dx in -2..2) {
                            val yy = y + dy
                            val xx = x + dx
                            if (yy in 0 until h && xx in 0 until w) {
                                sum += cur.pixels[yy * w + xx]
                                count++
                            }
                        }
                    }
                    out[y * w + x] = sum / count
                }
            }
            cur = GrayImage(w, h, out)
        }
        return cur
    }

    @Test
    fun sharpTextPagePassesGate() {
        val quality = PhotoQualityAnalyzer.analyzeGray(stripes(), tiltDegrees = 0f)
        assertFalse("sharp page flagged as " + quality.issueText, quality.needsRetake)
        assertTrue("sharpness should be high: " + quality.sharpness, quality.sharpness > PhotoQualityAnalyzer.BLUR_SHARPNESS)
    }

    @Test
    fun blurredPageIsFlaggedBlurry() {
        val quality = PhotoQualityAnalyzer.analyzeGray(boxBlur(stripes(), passes = 4), tiltDegrees = 0f)
        assertTrue("expected BLURRY, got " + quality.issueText, quality.issues.contains(PhotoIssue.BLURRY))
    }

    @Test
    fun darkLowContrastPageIsFlaggedDark() {
        val quality = PhotoQualityAnalyzer.analyzeGray(uniform(value = 35f), tiltDegrees = 0f)
        assertTrue("expected TOO_DARK, got " + quality.issueText, quality.issues.contains(PhotoIssue.TOO_DARK))
    }

    @Test
    fun darkThemeScreenshotIsNotFlaggedDark() {
        // 黑底浅字：均值低，但对比度高 —— 正是深色主题截图，不能提示"偏暗"
        val quality = PhotoQualityAnalyzer.analyzeGray(glyphBlocks(), tiltDegrees = 0f)
        assertFalse("dark screenshot should pass, got " + quality.issueText, quality.needsRetake)
    }

    @Test
    fun blankWhitePageIsFlaggedOverexposed() {
        val quality = PhotoQualityAnalyzer.analyzeGray(uniform(value = 252f), tiltDegrees = 0f)
        assertTrue("expected TOO_BRIGHT, got " + quality.issueText, quality.issues.contains(PhotoIssue.TOO_BRIGHT))
    }

    @Test
    fun specularBlobIsFlaggedGlare() {
        val w = 320
        val h = 240
        val px = FloatArray(w * h) { 90f }
        for (y in 60 until 180) {
            for (x in 100 until 220) px[y * w + x] = 255f
        }
        val quality = PhotoQualityAnalyzer.analyzeGray(GrayImage(w, h, px), tiltDegrees = 0f)
        assertTrue("expected GLARE, got " + quality.issueText, quality.issues.contains(PhotoIssue.GLARE))
        assertTrue(
            "largest bright component should be big: " + quality.glareRatio,
            PhotoQualityAnalyzer.largestBrightComponentRatio(GrayImage(w, h, px)) > 0.1f,
        )
    }

    @Test
    fun thinBrightGlyphsAreNotGlare() {
        // 默认 glyphBlocks 的亮像素占比约 16%，但每个连通块只有 8x8：不能被判成反光
        val image = glyphBlocks()
        val quality = PhotoQualityAnalyzer.analyzeGray(image, tiltDegrees = 0f)
        assertFalse("thin glyphs must not be glare: " + quality.issueText, quality.issues.contains(PhotoIssue.GLARE))
    }

    @Test
    fun extremeTiltIsFlagged() {
        val quality = PhotoQualityAnalyzer.analyzeGray(stripes(), tiltDegrees = 11.5f)
        assertTrue("expected TILTED, got " + quality.issueText, quality.issues.contains(PhotoIssue.TILTED))
    }

    @Test
    fun smallTiltIsAutoCorrectedNotRetaken() {
        val quality = PhotoQualityAnalyzer.analyzeGray(stripes(), tiltDegrees = 5f)
        assertFalse("5 degree tilt is fixable, got " + quality.issueText, quality.issues.contains(PhotoIssue.TILTED))
    }
}
