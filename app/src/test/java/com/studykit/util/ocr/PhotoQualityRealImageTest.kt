package com.studykit.util.ocr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlin.math.abs
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 用真实渲染出来的测试页（PIL + 微软雅黑，700x900）校准阈值：
 * 合成灰度条只能证明算符方向，证明不了「真文字页面会不会被误判」。
 *
 * 这四张图与真机 connectedDebugAndroidTest 用的是同一组资源
 * （app/src/test/resources/ocr 与 app/src/androidTest/assets/ocr 同一份），
 * JVM 侧钉阈值，设备侧再钉「真跑 OCR 能读出来 + 摆正生效」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhotoQualityRealImageTest {

    private fun bitmap(name: String): Bitmap {
        val stream = javaClass.classLoader!!.getResourceAsStream("ocr/" + name)
            ?: error("missing test resource: " + name)
        return BitmapFactory.decodeStream(stream) ?: error("failed to decode: " + name)
    }

    @Test
    fun clearPagePassesQualityGate() {
        val quality = PhotoQualityAnalyzer.analyze(bitmap("clear_page.png"))
        assertFalse("clear page should pass, got " + quality.issueText, quality.needsRetake)
        assertTrue("sharpness too low: " + quality.sharpness, quality.sharpness > PhotoQualityAnalyzer.BLUR_SHARPNESS)
    }

    @Test
    fun blurryPageIsFlagged() {
        val quality = PhotoQualityAnalyzer.analyze(bitmap("blurry_page.png"))
        assertTrue("expected BLURRY, got " + quality.issueText, quality.issues.contains(PhotoIssue.BLURRY))
    }

    @Test
    fun darkPageIsFlagged() {
        val quality = PhotoQualityAnalyzer.analyze(bitmap("dark_page.png"))
        assertTrue("expected TOO_DARK, got " + quality.issueText, quality.issues.contains(PhotoIssue.TOO_DARK))
    }

    @Test
    fun blankWhitePageIsFlaggedBright() {
        val quality = PhotoQualityAnalyzer.analyze(bitmap("blank_white.png"))
        assertTrue("expected TOO_BRIGHT, got " + quality.issueText, quality.issues.contains(PhotoIssue.TOO_BRIGHT))
    }

    @Test
    fun tiltedPageAngleIsEstimated() {
        val three = Deskew.estimateDegrees(bitmap("tilted3_page.png"))
        val six = Deskew.estimateDegrees(bitmap("tilted6_page.png"))
        assertTrue("3 degree estimate off: " + three, abs(three) in 1.5f..4.5f)
        assertTrue("6 degree estimate off: " + six, abs(six) in 4.5f..7.5f)
    }

    @Test
    fun clearPageIsTreatedAsHorizontal() {
        val angle = Deskew.estimateDegrees(bitmap("clear_page.png"))
        assertTrue("clear page should be 0, got " + angle, abs(angle) <= 1.0f)
    }
}
