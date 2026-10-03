package com.studykit.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.io.File
import kotlin.coroutines.resume
import kotlin.math.sqrt
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** 识别结果两态：成功给拼接后的多行文本，失败给一句能直接显示给用户的话 */
sealed interface OcrResult {
    data class Text(val value: String) : OcrResult
    data class Failed(val reason: String) : OcrResult
}

// ── 输入预处理的**纯参数层**（口径由 `OcrPreprocessTest` 钉住）────────────────
//
// 这几个数字不是本仓自创，是 Google ML Kit 文字识别文档「输入图片规范」的原文口径：
// **每个字符理想至少 16×16 像素，大于 24×24 之后精度不再上涨**；文档给整页扫描的尺寸参照是
// 720×1280（Letter 纸）—— 也就是说"让字撑大、让画面别虚胖"才是它想要的输入形态。
// 所以这里的策略只有两件事：**等比放大 + 宽松裁切**，不二值化、不锐化（那是重活，
// 阈值一错就把笔画连同细节一起抹掉，属于"做坏了整片丢字"的那类改动）。

/** 字高低于它就放大；不低于它就一字不动（放大不涨精度，只涨内存） */
const val OCR_MIN_GLYPH_PX = 16

/** 放大到此字高即停 —— 文档明说超过 24×24 不再涨精度 */
const val OCR_TARGET_GLYPH_PX = 24

/** 喂给识别器的图单边上限。再大不涨精度，先涨内存（ARGB_8888 下 2400px 见集约 23MB） */
internal const val OCR_MAX_EDGE_PX = 2_400

private const val OCR_MAX_SCALE = 4f

/**
 * 该把图放大多少倍：字太小就按 [OCR_TARGET_GLYPH_PX] 补到位，否则不动。
 *
 * - 恒返回 ≥1，**永远不缩小**（缩小只会让字更认不出）；
 * - 封顶 [OCR_MAX_SCALE]：4 倍之后双线性插值只是糊，不是细节；
 * - [imgHeightPx] 那一路是内存闸门：图本身已经很高（如 12000px）时再乘 3 就是三万六千像素高，
 *   精度不涨、先 OOM，于是压回 1（等于不预处理，走原路径）；
 * - 尺寸或字高是 0 / 负数 / 极大值这类说不清的情况一律退回 1 而不抛 ——
 *   预处理失败不该让一次 OCR 整体失败（调用方还有一层兜回原路径的 `runCatching`）。
 */
fun ocrScaleFactor(imgHeightPx: Int, glyphHeightPx: Int): Float {
    if (imgHeightPx <= 0 || glyphHeightPx <= 0) return 1f
    if (glyphHeightPx >= OCR_MIN_GLYPH_PX) return 1f
    val wanted = (OCR_TARGET_GLYPH_PX.toFloat() / glyphHeightPx).coerceIn(1f, OCR_MAX_SCALE)
    val allowed = OCR_MAX_EDGE_PX.toFloat() / imgHeightPx
    return if (wanted > allowed) allowed.coerceAtLeast(1f) else wanted
}

/**
 * 逐行墨迹计数 → 字高（单位：行）。
 *
 * 取**中位数**而不是平均：图里混着插图或表格时，那个几十行的长游程会把平均值整体抬高，
 * 于是本该放大的图被判成"字已经够大"。中位数被少数离群段拖不动。
 * 相邻两段之间只空 1 行算同一段 —— 汉字的横画之间本来就有空白行。
 */
internal fun medianGlyphRowRun(rowInk: IntArray, inkThreshold: Int): Int {
    val runs = inkRowRuns(rowInk, inkThreshold)
    if (runs.isEmpty()) return 0
    val sorted = runs.sorted()
    return sorted[sorted.size / 2]
}

/**
 * 文字区的行范围，上下各留 [padRows] 行余量；没有墨迹返回 null（= 不裁）。
 *
 * 刻意只做**行**方向：列方向裁错一次就把整行字腰斩，而行方向的留白只是白边，
 * 对 ML Kit 无害。返回满幅（`first == 0 && last == 最后一行`）时调用方据此判定"没得裁"。
 */
internal fun generousTextRowRange(rowInk: IntArray, inkThreshold: Int, padRows: Int): IntRange? {
    if (rowInk.isEmpty()) return null
    val first = rowInk.indexOfFirst { it >= inkThreshold }
    if (first < 0) return null
    val last = rowInk.indexOfLast { it >= inkThreshold }
    val lo = (first - padRows).coerceAtLeast(0)
    val hi = (last + padRows).coerceAtMost(rowInk.lastIndex)
    return lo..hi
}

/** 把逐行墨迹计数切成游程长度（空隙 ≤1 行并成一段） */
private fun inkRowRuns(rowInk: IntArray, inkThreshold: Int): List<Int> {
    val runs = mutableListOf<Int>()
    var start = -1
    var end = -1
    for (i in rowInk.indices) {
        if (rowInk[i] < inkThreshold) continue
        when {
            start < 0 -> { start = i; end = i }
            i - end <= 2 -> end = i
            else -> { runs += end - start + 1; start = i; end = i }
        }
    }
    if (start >= 0) runs += end - start + 1
    return runs
}

/**
 * ML Kit 中文文字识别（bundled 模型，全离线，vivo 无 GMS 也能用）。
 *
 * 四条实现约束：
 * 1. 解 bitmap、缩放与识别都在主线程外（读大图 + 缩放本身可达数百毫秒）；
 * 2. 识别器**单例复用**：每次新建都要重加载模型，实测明显卡顿；
 * 3. ML Kit 的回调是一次性的 `addOnSuccessListener/onFailure`，用 `suspendCancellableCoroutine` 桥接，
 *    并见 [resumeIfStillWaiting] —— 这条回调路径没有可取消的句柄；
 * 4. 预处理**只做加法**：估不出字高、判不出文字区、缩放抛异常，任一条都兜回
 *    `InputImage.fromFilePath`（v2.7 的既有路径），绝不让一次 OCR 因为预处理而失败。
 *    对外的 `recognize(context, file)` 契约与上面三条语义一字未动。
 */
object OcrTextExtractor {

    private val recognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    suspend fun recognize(context: Context, file: File): OcrResult = withContext(Dispatchers.IO) {
        if (!file.exists() || file.length() == 0L) {
            return@withContext OcrResult.Failed("图片不存在或已损坏")
        }
        val image = preparedOrFallback(context, file)
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
 * 交给识别器的那张图。
 *
 * 为什么两条路都要留着：`fromFilePath` 由 ML Kit 自己解码，会照 EXIF 转正，也会把大图按它
 * 内部的尺度重采 —— 我们**看不见**它把字缩成了多大，小字认不准正是这一环。
 * `fromBitmap` 让我们决定最终交给它的像素，但**不再**自动读 EXIF，所以方向得自己转正。
 * 只有"预处理真能改变点什么"（要放大，或文字区只占一小截、裁掉能省下重采）时才走 bitmap 那条，
 * 其余情况原样交给 `fromFilePath`，不动它任何行为。
 */
private fun preparedOrFallback(context: Context, file: File): InputImage? =
    runCatching { prepareInputImage(file) }.getOrNull()
        // ML Kit 这一版只有 `fromFilePath(Context, Uri)`：单串路径的重载已被摘掉
        // （CI 两次报错各证一半：先 "No value passed for parameter 'p1'"，补了 context 又要求 Uri）
        ?: runCatching { InputImage.fromFilePath(context, Uri.fromFile(file)) }.getOrNull()

/** 只做估算用的小图长边：够看清"有没有一行行的墨迹"就行，扫像素的是它而不是原图 */
private const val PROBE_LONG_EDGE = 720

/** 放大前解码的源图长边上限。超过就按 2 的幂采样解码，控住中间 bitmap 的体积 */
private const val SOURCE_LONG_EDGE = 2_000

private fun prepareInputImage(file: File): InputImage? {
    val path = file.absolutePath
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val sourceLong = maxOf(bounds.outWidth, bounds.outHeight)

    // ── ① 探针：小图上数一遍"哪些行有墨"，估出字高与文字区（都是探针坐标，稍后折算）──
    val probeSample = sampleSizeFor(sourceLong, PROBE_LONG_EDGE)
    val probe = BitmapFactory.decodeFile(path, sampleOptions(probeSample)) ?: return null
    // BitmapFactory 不读 EXIF。先转正再统计，否则竖拍照片会把"字高"估成"行距"。
    val degrees = exifRotationDegrees(file)
    val uprightProbe = rotate(probe, degrees, recycleSource = true)
    val inkFloor = (uprightProbe.width / 240).coerceAtLeast(2)
    val rowInk = inkRowProfile(uprightProbe)
    val glyphRows = medianGlyphRowRun(rowInk, inkFloor)
    val range = generousTextRowRange(rowInk, inkFloor, padRows = glyphRows.coerceAtLeast(1))
    val probeHeight = uprightProbe.height
    uprightProbe.recycle()
    // 判不出字高（纯噪点、全白、全黑）就不动原路径：预处理的前提是我们知道字有多大
    if (glyphRows <= 0) return null

    // ── ② 该解码到哪一档、该放大多少倍（此后一律用"工作图坐标"这一个参照系）──
    val workSample = sampleSizeFor(sourceLong, SOURCE_LONG_EDGE)
    val glyphWorkPx = (glyphRows * probeSample / workSample).coerceAtLeast(1)
    val uprightWidth = if (degrees % 180 == 0) bounds.outWidth else bounds.outHeight
    val uprightHeight = if (degrees % 180 == 0) bounds.outHeight else bounds.outWidth
    val heightWorkPx = (uprightHeight / workSample).coerceAtLeast(1)
    val widthWorkPx = (uprightWidth / workSample).coerceAtLeast(1)
    // 宽度也拦一道：横构图（宽 > 高）时 heightWorkPx 拦不住放大后的总尺寸
    val scale = minOf(
        ocrScaleFactor(heightWorkPx, glyphWorkPx),
        OCR_MAX_EDGE_PX.toFloat() / widthWorkPx,
    ).coerceAtLeast(1f)
    // 探针坐标 → 工作图坐标（两个 sample 都是 2 的幂，探针档不低于工作档）
    val ratio = probeSample.toFloat() / workSample
    val cropTop = if (range == null) 0 else (range.first * ratio).toInt().coerceAtLeast(0)
    val cropBottom = if (range == null || range.last >= probeHeight - 1) {
        heightWorkPx
    } else {
        ((range.last + 1) * ratio).toInt().coerceAtMost(heightWorkPx)
    }
    val cropHeight = (cropBottom - cropTop).coerceAtLeast(1)
    val needsScale = scale > 1.001f
    // 只裁掉不到两成高度的白边不值得多解一次图（多花的解码时间比省下的重采更多）
    val needsCrop = cropHeight < heightWorkPx * 0.8f
    if (!needsScale && !needsCrop) return null

    // ── ③ 解码工作图 → 转正 → 裁文字区 → 等比放大，一次矩阵搞定后两步 ──
    val source = BitmapFactory.decodeFile(path, sampleOptions(workSample)) ?: return null
    val upright = rotate(source, degrees, recycleSource = true)
    // 解码实际尺寸可能与估算差一两个像素（BitmapFactory 的取整口径），以实际为准再收一次
    val safeTop = cropTop.coerceAtMost((upright.height - 1).coerceAtLeast(0))
    val safeHeight = (cropHeight.coerceAtMost(upright.height - safeTop)).coerceAtLeast(1)
    val outWidth = (upright.width * scale).toInt().coerceAtLeast(1)
    val outHeight = (safeHeight * scale).toInt().coerceAtLeast(1)
    // 太小的输入 ML Kit 直接认不出（文档：单边至少 25px），这种情况交给原路径更稳
    if (outWidth < 32 || outHeight < 32 || outWidth.toLong() * outHeight > 12_000_000L) {
        recycleAll(upright, source)
        return null
    }
    val matrix = Matrix().apply { setScale(scale, scale) }
    val prepared = runCatching {
        Bitmap.createBitmap(upright, 0, safeTop, upright.width, safeHeight, matrix, true)
    }.getOrNull()
    if (prepared == null) {
        recycleAll(upright, source)
        return null
    }
    recycleAll(upright, source, keep = prepared)
    // 方向已经画进像素里了，这里必须报 0，否则等于转两次
    return InputImage.fromBitmap(prepared, 0)
}

private fun sampleOptions(sample: Int): BitmapFactory.Options =
    BitmapFactory.Options().apply { inSampleSize = sample }

/** 采样解码的档位：取 2 的幂里"降下来仍不短于 target"的那一档（BitmapFactory 只认 2 的幂） */
private fun sampleSizeFor(longEdgePx: Int, targetLongEdge: Int): Int {
    if (longEdgePx <= targetLongEdge) return 1
    var sample = 1
    while (longEdgePx / sample > targetLongEdge * 2L) sample *= 2
    return sample
}

/** 转正之后没被 `createBitmap` 复用的中间图一律回收，缩放一次 OCR 不该留下几十兆垃圾等 GC */
private fun recycleAll(vararg bitmaps: Bitmap, keep: Bitmap? = null) {
    bitmaps.distinctBy { it }.forEach { if (it !== keep && !it.isRecycled) it.recycle() }
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

private fun rotate(src: Bitmap, degrees: Int, recycleSource: Boolean): Bitmap {
    if (degrees % 360 == 0) return src
    val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
    val out = runCatching {
        Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
    }.getOrDefault(src)
    if (recycleSource && out !== src) src.recycle()
    return out
}

/**
 * 逐行暗点计数。**只用来定位文字，不改写交给识别器的图** —— 阈值化输出会把笔画细节一起抹掉，
 * 那正是文档"做坏了整片丢字"的一类，这里一步都不做。
 *
 * 阈值是自适应的（均值 - 一倍标准差）：扫描件、屏幕截图、灯下纸面的亮度分布差得很远，
 * 写死一个 128 会在其中某类上直接判成"全黑"或"全白"。
 */
private fun inkRowProfile(src: Bitmap): IntArray {
    val width = src.width
    val height = src.height
    val rowInk = IntArray(height)
    val pixels = IntArray(width)
    var sum = 0.0
    var sumSquares = 0.0
    var counted = 0L
    var y = 0
    while (y < height) {
        src.getPixels(pixels, 0, width, 0, y, width, 1)
        var x = 0
        while (x < width) {
            val level = luma(pixels[x])
            sum += level
            sumSquares += level * level
            counted++
            x += 4
        }
        y += 4
    }
    if (counted == 0L) return rowInk
    val mean = sum / counted
    val variance = (sumSquares / counted - mean * mean).coerceAtLeast(0.0)
    val threshold = (mean - sqrt(variance)).coerceIn(0.0, 255.0)
    for (row in 0 until height) {
        src.getPixels(pixels, 0, width, 0, row, width, 1)
        var dark = 0
        for (x in 0 until width) {
            if (luma(pixels[x]) < threshold) dark++
        }
        rowInk[row] = dark
    }
    return rowInk
}

private fun luma(argb: Int): Double {
    val r = (argb shr 16) and 0xFF
    val g = (argb shr 8) and 0xFF
    val b = argb and 0xFF
    return 0.299 * r + 0.587 * g + 0.114 * b
}

/**
 * 识别器的回调没有可取消的句柄（`TextRecognizer` 只实现 `Closeable`），
 * 所以协程取消之后回调照样会来一次。对已取消的续体再 `resume` 会抛 `IllegalStateException`，
 * 这里直接丢弃。
 */
private fun <T> CancellableContinuation<T>.resumeIfStillWaiting(value: T) {
    if (isActive) resume(value)
}
