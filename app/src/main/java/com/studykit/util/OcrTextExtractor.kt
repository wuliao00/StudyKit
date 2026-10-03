package com.studykit.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** 识别结果两态：成功给拼接后的多行文本，失败给一句能直接显示给用户的话 */
sealed interface OcrResult {
    data class Text(val value: String) : OcrResult
    data class Failed(val reason: String) : OcrResult
}

/**
 * 采样解码的长边上限。超过就按 2 的幂降采样，控住中间 bitmap 的体积（ARGB_8888 下
 * 2048×~1536 见集约 12MB）。PP-OCR 的 det 会再把长边等比压到内部闸门 [PpOcrOnnxEngine] 的
 * `MAX_DET_SIDE`，这里只是"进内存前"的 OOM 保险，不是精度处理。
 */
private const val DECODE_MAX_LONG_EDGE = 2_048

/**
 * 端侧 OCR 的唯一门面：把 `File → OcrResult` 这条契约稳定地交给 [PpOcrOnnxEngine]（PP-OCRv6）。
 *
 * v2.8 起 **ML Kit 已下线**，PP-OCR 是唯一引擎——纯英文/拉丁场景也照走 PP-OCR，无回退。
 * 四个调用点（错题拍照、分享自动识别、题库拍照录入、截图取词）拿到的签名与语义一字未动，
 * 因此**调用方零改动**。四条实现纪律沿用换代前：
 * 1. 解码、模型加载、推理全在主线程外（读大图 + det/rec 推理本身可达数百毫秒到数秒）；
 * 2. 引擎**单例复用**：[PpOcrOnnxEngine] 是进程级 object，`ensureLoaded` 幂等，重复调用不重加载模型；
 * 3. **可取消**：全程 `withContext(Dispatchers.IO)`，解码后、推理前用 `ensureActive()` 设卡，
 *    取消时抛 [CancellationException] 而非吞成 `Failed`（调用方协程被取消后不会拿到结果）；
 * 4. 预处理**只做加法且失败即兜**：文件不存在 / 解不出图 / 推理抛异常，分别回一句能显示的话，
 *    绝不因内部环节让一次 OCR 崩在调用方手里。
 */
object OcrTextExtractor {

    suspend fun recognize(context: Context, file: File): OcrResult = withContext(Dispatchers.IO) {
        if (!file.exists() || file.length() == 0L) {
            return@withContext OcrResult.Failed("图片不存在或已损坏")
        }
        // 先解码（纯本地开销，也能就地挡掉坏文件），再加载 30MB 模型——坏输入不该触发模型加载。
        val bitmap = decodeUpright(file)
            ?: return@withContext OcrResult.Failed("无法读取这张图片")
        try {
            ensureActive()
            PpOcrOnnxEngine.ensureLoaded(context)
            val text = PpOcrOnnxEngine.recognize(bitmap).joinToString("\n") { it.text }
            if (text.isBlank()) OcrResult.Failed("没识别出文字，可手动输入")
            else OcrResult.Text(text)
        } catch (ce: CancellationException) {
            throw ce // 取消不是失败，交给结构化并发向上抛
        } catch (t: Throwable) {
            OcrResult.Failed("识别失败：${t.message ?: "未知原因"}")
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }
}

/**
 * 解码并把方向画进像素：先按 [DECODE_MAX_LONG_EDGE] 采样降一档解码，再照 EXIF 转正。
 *
 * [PpOcrOnnxEngine.recognize] 假设进来的是**已转正**的位图（det/rec 都不读 EXIF），所以这一步
 * 必须在门面里做掉。`BitmapFactory` 不读 EXIF，竖拍照片不转会把"字高"当成"行距"，整页漏检。
 */
private fun decodeUpright(file: File): Bitmap? {
    val path = file.absolutePath
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val sample = sampleSizeFor(maxOf(bounds.outWidth, bounds.outHeight), DECODE_MAX_LONG_EDGE)
    val decoded = BitmapFactory.decodeFile(
        path,
        BitmapFactory.Options().apply { inSampleSize = sample },
    ) ?: return null
    return rotateToUpright(decoded, exifRotationDegrees(file))
}

/** 采样解码的档位：取 2 的幂里"降下来仍不短于 target"的那一档（BitmapFactory 只认 2 的幂） */
private fun sampleSizeFor(longEdgePx: Int, targetLongEdge: Int): Int {
    if (longEdgePx <= targetLongEdge) return 1
    var sample = 1
    while (longEdgePx / sample > targetLongEdge.toLong() * 2) sample *= 2
    return sample
}

private fun rotateToUpright(src: Bitmap, degrees: Int): Bitmap {
    if (degrees % 360 == 0) return src
    val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
    val out = runCatching {
        Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
    }.getOrDefault(src)
    if (out !== src) src.recycle()
    return out
}

/** EXIF 方向 → 需要顺时针转的角度。读不到（WebP、无 EXIF 段）就按 0 处理 */
private fun exifRotationDegrees(file: File): Int {
    val orientation = runCatching {
        ExifInterface(file.absolutePath).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    return when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90
        ExifInterface.ORIENTATION_ROTATE_180 -> 180
        ExifInterface.ORIENTATION_ROTATE_270 -> 270
        else -> 0
    }
}
