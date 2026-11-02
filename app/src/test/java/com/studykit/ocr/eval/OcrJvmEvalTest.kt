package com.studykit.ocr.eval

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.studykit.util.OcrResult
import com.studykit.util.OcrTextExtractor
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 真模型 OCR 的 JVM 批量评测（v3.8）。
 *
 * 默认不跑：需要显式传数据集目录，否则 assume 跳过，常规单测不受影响：
 *
 *   gradlew.bat :app:testDebugUnitTest --tests "com.studykit.ocr.eval.OcrJvmEvalTest"  *       -PocrEvalDir=E:\项目\studykit\tools\ocr_eval\dataset  *       -PocrEvalOut=E:\项目\studykit\tools\ocr_eval\out\ocr_output.json
 *
 * 走的是宿主 JVM 的桌面版 onnxruntime（testImplementation onnxruntime，见 app/build.gradle.kts），
 * 不是 Android AAR；模型从 app assets 读，和真机同一份权重与字典。
 * 输出 JSON 交给 tools/ocr_eval/evaluate.py 算 CER/分类统计。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OcrJvmEvalTest {

    @Test
    fun runEvaluationWhenDatasetProvided() {
        val dirPath = System.getProperty("ocr.eval.dir").orEmpty()
        assumeTrue("未传 -PocrEvalDir，跳过真模型评测", dirPath.isNotBlank())
        val root = File(dirPath)
        val groundTruth = File(root, "ground_truth.json")
        assumeTrue("数据集缺少 ground_truth.json: " + groundTruth, groundTruth.isFile)

        val array = JSONArray(groundTruth.readText(Charsets.UTF_8))
        val context = ApplicationProvider.getApplicationContext<Context>()
        val results = JSONArray()
        var processed = 0

        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            val image = File(root, item.optString("image"))
            if (!image.isFile) continue
            val id = item.optString("id")
            val category = item.optString("category")
            val started = System.currentTimeMillis()
            val output = runBlocking { OcrTextExtractor.recognize(context, image) }
            val elapsed = System.currentTimeMillis() - started
            val text = (output as? OcrResult.Text)?.value.orEmpty()
            val report = (output as? OcrResult.Text)?.report
            val row = JSONObject()
                .put("id", id)
                .put("category", category)
                .put("text", text)
                .put("elapsedMs", elapsed)
            if (report != null) {
                row.put("confidence", report.meanScore.toDouble())
                row.put("deskewDegrees", report.deskewDegrees.toDouble())
                row.put("retriedLines", report.retriedLines)
                row.put("lines", JSONArray(report.lines.map { it.text }))
            }
            results.put(row)
            processed++
            if (processed % 25 == 0 || processed == array.length()) {
                println("ocr eval progress " + processed + "/" + array.length())
            }
        }

        val outPath = System.getProperty("ocr.eval.out").orEmpty()
        val out = if (outPath.isNotBlank()) File(outPath) else File(root, "ocr_output.json")
        out.parentFile?.mkdirs()
        out.writeText(JSONObject().put("results", results).toString(2), Charsets.UTF_8)
        println("ocr eval written: " + out.absolutePath + " count=" + results.length())
        assertTrue("至少应产出一条结果", results.length() > 0)
    }
}
