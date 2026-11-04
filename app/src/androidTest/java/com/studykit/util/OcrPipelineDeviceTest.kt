package com.studykit.util

import androidx.test.platform.app.InstrumentationRegistry
import com.studykit.util.ocr.PhotoIssue
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v3.7 OCR 管线的真机验收（vivo V2156A / Android 11 上通过 connectedDebugAndroidTest 执行）。
 *
 * 守三件事：
 * 1. 清晰页在真机上确实能被 PP-OCRv6 读出文字（带 OcrReport，置信度不是 0）；
 * 2. 倾斜 6° 的页在真机上确实被摆正（report.deskewDegrees 落在 4.5~7.5 度）后仍能读出文字；
 * 3. 糊图 / 暗图在跑模型之前就被质量体检拦下（拍照页据此弹重拍提示）。
 *
 * 测试图是 PIL + 微软雅黑渲染的 700x900 页面，和 JVM 侧的 PhotoQualityRealImageTest 同一份资源。
 * 真机上的 OCR 推理需要 APK 内置模型（完整包）或设备上已下载模型；qemu 模拟器没有对应 ABI 的
 * onnxruntime，因此这条用例只对真机有意义。
 */
class OcrPipelineDeviceTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * 测试图在 **test APK** 的 assets 里，不在被测 App 的 assets 里。
     * 从 targetContext.assets 读会直接 FileNotFoundException，所以这里必须用 instrumentation.context。
     */
    private val testAssets get() = InstrumentationRegistry.getInstrumentation().context.assets

    private fun assetFile(name: String): File {
        val file = File(context.cacheDir, "ocr_device_" + name)
        if (!file.exists() || file.length() == 0L) {
            testAssets.open("ocr/" + name).use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return file
    }

    @Test
    fun clearPageIsRecognizedWithReport() = runBlocking {
        val result = OcrTextExtractor.recognize(context, assetFile("clear_page.png"))
        assertTrue("expected Text, got " + result, result is OcrResult.Text)
        val text = (result as OcrResult.Text)
        assertTrue("text should not be blank", text.value.isNotBlank())
        assertTrue("should contain StudyKit, got: " + text.value, text.value.contains("StudyKit", ignoreCase = true))
        val report = text.report
        assertNotNull("report must be present", report)
        assertTrue("mean score should be usable: " + report!!.meanScore, report.meanScore > 0.30f)
        assertTrue(
            "clear page should not be flagged blurry: " + report.quality?.issueText,
            report.quality?.issues?.contains(PhotoIssue.BLURRY) != true,
        )
    }

    @Test
    fun tiltedPageIsDeskewedBeforeRecognition() = runBlocking {
        val result = OcrTextExtractor.recognize(context, assetFile("tilted6_page.png"))
        assertTrue("expected Text, got " + result, result is OcrResult.Text)
        val report = (result as OcrResult.Text).report
        assertNotNull("report must be present", report)
        assertTrue(
            "deskew angle should be near 6 degrees, got " + report!!.deskewDegrees,
            abs(report.deskewDegrees) in 4.0f..8.0f,
        )
        assertTrue("deskewed page should still produce text", (result as OcrResult.Text).value.isNotBlank())
    }

    @Test
    fun blurryPageIsFlaggedBeforeOcr() = runBlocking {
        val quality = OcrTextExtractor.analyzeQuality(assetFile("blurry_page.png"))
        assertNotNull("quality report must be present", quality)
        assertTrue(
            "expected BLURRY, got " + quality!!.issueText,
            quality.issues.contains(PhotoIssue.BLURRY),
        )
    }

    @Test
    fun darkPageIsFlaggedBeforeOcr() = runBlocking {
        val quality = OcrTextExtractor.analyzeQuality(assetFile("dark_page.png"))
        assertNotNull("quality report must be present", quality)
        assertTrue(
            "expected TOO_DARK, got " + quality!!.issueText,
            quality.issues.contains(PhotoIssue.TOO_DARK),
        )
    }
    /** v3.9：深色桌面上的透视纸页，真机应检测到四角、校正后仍能读出 StudyKit。 */
    @Test
    fun perspectivePageIsCorrectedBeforeOcr() = runBlocking {
        val result = OcrTextExtractor.recognize(context, assetFile("perspective_page.png"))
        assertTrue("expected Text, got " + result, result is OcrResult.Text)
        val text = result as OcrResult.Text
        val report = text.report
        assertNotNull("report must be present", report)
        assertTrue("perspective should be corrected", report!!.perspectiveCorrected)
        assertTrue("should contain StudyKit after correction: " + text.value, text.value.contains("StudyKit", ignoreCase = true))
    }
}
