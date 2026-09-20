package com.studykit.util

import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.io.File
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** 识别结果两态：成功给拼接后的多行文本，失败给一句能直接显示给用户的话 */
sealed interface OcrResult {
    data class Text(val value: String) : OcrResult
    data class Failed(val reason: String) : OcrResult
}

/**
 * ML Kit 中文文字识别（bundled 模型，全离线，vivo 无 GMS 也能用）。
 *
 * 三条实现约束：
 * 1. 解 bitmap 与识别都在主线程外（`InputImage.fromFilePath` 读大图可达数百毫秒）；
 * 2. 识别器**单例复用**：每次新建都要重加载模型，实测明显卡顿；
 * 3. ML Kit 的回调是一次性的 `addOnSuccessListener/onFailure`，用 `suspendCancellableCoroutine` 桥接，
 *    并见 [resumeIfStillWaiting] —— 这条回调路径没有可取消的句柄。
 */
object OcrTextExtractor {

    private val recognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    suspend fun recognize(file: File): OcrResult = withContext(Dispatchers.IO) {
        if (!file.exists() || file.length() == 0L) {
            return@withContext OcrResult.Failed("图片不存在或已损坏")
        }
        val image = runCatching { InputImage.fromFilePath(file.absolutePath) }.getOrNull()
            ?: return@withContext OcrResult.Failed("无法读取这张图片")
        suspendCancellableCoroutine<OcrResult> { continuation ->
            recognizer.process(image)
                .addOnSuccessListener { result ->
                    val text = result.textBlocks.joinToString("\n") { block ->
                        block.lines.joinToString("\n") { it.text }
                    }
                    continuation.resumeIfStillWaiting(
                        if (text.isBlank()) OcrResult.Failed("没识别出文字，可手动输入")
                        else OcrResult.Text(text),
                    )
                }
                .addOnFailureListener { error ->
                    continuation.resumeIfStillWaiting(
                        OcrResult.Failed("识别失败：${error.message ?: "未知原因"}"),
                    )
                }
        }
    }
}

/**
 * ML Kit 这边拿不到可取消的任务句柄（`TextRecognizer` 只实现 `Closeable`，`Task` 也不公开 `cancel()`），
 * 所以协程取消之后回调照样会来一次。对已取消的续体再 `resume` 会抛 `IllegalStateException`，
 * 这里直接丢弃。
 */
private fun <T> CancellableContinuation<T>.resumeIfStillWaiting(value: T) {
    if (isActive) resume(value)
}
