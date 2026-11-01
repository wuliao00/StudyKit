package com.studykit.util.ocr

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.sqrt

/** 拍照质量门控能提出的问题。文案直接给用户看，所以放在枚举里只写一份。 */
enum class PhotoIssue(val label: String) {
    BLURRY("图像偏模糊"),
    TOO_DARK("画面偏暗"),
    TOO_BRIGHT("曝光过度"),
    GLARE("有反光"),
    TILTED("画面明显倾斜"),
}

/**
 * 一张照片的质量体检结果。全部指标来自 [PhotoQualityAnalyzer] 的 640 长边灰度缩略图，
 * 值域固定：sharpness 是拉普拉斯方差（越大越锐），brightness/contrast 是 0..255 的均值/标准差，
 * glareRatio 是接近纯白的像素占比，tiltDegrees 是内容倾斜角（同 [Deskew] 的符号约定）。
 *
 * **只提示、不拦截**：一张糊图也可能正好是老师发的截图、或用户手边只有这一张。
 * [needsRetake] 只决定要不要先弹「要不要重拍」，用户永远可以选择仍然识别。
 */
data class PhotoQuality(
    val sharpness: Float,
    val brightness: Float,
    val contrast: Float,
    val glareRatio: Float,
    val tiltDegrees: Float,
    val issues: List<PhotoIssue>,
) {
    val needsRetake: Boolean get() = issues.isNotEmpty()

    /** 用户可读的问题清单（顿号分隔），空串表示没问题。 */
    val issueText: String get() = issues.joinToString("、") { it.label }

    fun summary(): String = if (needsRetake) "照片可能有问题：$issueText" else "照片质量良好"
}

/**
 * 拍照/相册输入的质量体检（v3.7）。
 *
 * 离线 OCR 的上限主要卡在输入质量，而用户拍完才知道糊了；这里在**跑模型之前**用纯 CPU 统计
 * 把「糊、暗、过曝、反光、明显倾斜」先挑出来，给一次重拍机会。算法只依赖灰度统计，无模型、
 * 无 OpenCV，单张 640 长边的开销在个位数毫秒级。
 *
 * 阈值的口径（都写成常量便于真机回归时改一处）：
 * - **糊**：拉普拉斯方差 < 42。文字页在 640 长边下通常 100+；失焦/手抖照片常掉到 10~30；
 * - **暗**：均值 < 55 且对比度 < 45。深色主题截图均值也低，但对比度高（白字），不误报；
 * - **过曝**：均值 > 215 且对比度 < 40。纯白纸/白墙会中，正是「拍了个白板」的典型；
 * - **反光**：≥250 的像素占比 > 6% 且整体均值 < 205。白纸均值本来就 >215，走不到这条；
 * - **倾斜**：|tilt| ≥ 10°（1~10° 由 [Deskew] 自动摆正，不算不合格）。
 */
object PhotoQualityAnalyzer {

    internal const val ANALYSIS_MAX_SIDE = 640
    internal const val BLUR_SHARPNESS = 42f
    internal const val DARK_BRIGHTNESS = 55f
    internal const val DARK_MAX_CONTRAST = 45f
    internal const val BRIGHT_BRIGHTNESS = 225f
    internal const val BRIGHT_MAX_CONTRAST = 20f
    internal const val GLARE_LUMA = 250f
    internal const val GLARE_RATIO = 0.10f
    internal const val GLARE_MAX_BRIGHTNESS = 205f
    internal const val GLARE_MIN_COMPONENT = 0.015f
    internal const val TILT_RETAKE_DEGREES = 10f

    /** 体检一张已解码（且已按 EXIF 转正）的图。 */
    fun analyze(bitmap: Bitmap): PhotoQuality {
        val gray = grayImageOf(bitmap, ANALYSIS_MAX_SIDE)
        // 倾斜角第一次算出来就顺手喂给质量报告，避免门控与摆正各估一次。
        return analyzeGray(gray, Deskew.estimateFromGray(gray))
    }

    /**
     * 最大连通亮块占整图的比例。反光是大面积高光，而深色主题里的白字是一个个独立小笔画：
     * 只看亮像素占比会把「黑底白字的截图」误判成反光，加上连通块面积这一条才分得开。
     * 4 邻域泛洪，640x480 最坏 30 万像素，单张开销 < 5ms。
     */
    internal fun largestBrightComponentRatio(image: GrayImage): Float {
        val w = image.width
        val h = image.height
        val total = w * h
        val visited = BooleanArray(total)
        val stack = IntArray(total)
        var best = 0
        for (start in 0 until total) {
            if (visited[start] || image.pixels[start] < GLARE_LUMA) continue
            var top = 0
            stack[top++] = start
            visited[start] = true
            var count = 0
            while (top > 0) {
                val idx = stack[--top]
                count++
                val x = idx % w
                val y = idx / w
                if (x > 0) {
                    val n = idx - 1
                    if (!visited[n] && image.pixels[n] >= GLARE_LUMA) {
                        visited[n] = true
                        stack[top++] = n
                    }
                }
                if (x + 1 < w) {
                    val n = idx + 1
                    if (!visited[n] && image.pixels[n] >= GLARE_LUMA) {
                        visited[n] = true
                        stack[top++] = n
                    }
                }
                if (y > 0) {
                    val n = idx - w
                    if (!visited[n] && image.pixels[n] >= GLARE_LUMA) {
                        visited[n] = true
                        stack[top++] = n
                    }
                }
                if (y + 1 < h) {
                    val n = idx + w
                    if (!visited[n] && image.pixels[n] >= GLARE_LUMA) {
                        visited[n] = true
                        stack[top++] = n
                    }
                }
            }
            if (count > best) best = count
        }
        return if (total == 0) 0f else best.toFloat() / total
    }

    /** 纯统计入口：单测用合成灰度图跑阈值边界。 */
    internal fun analyzeGray(image: GrayImage, tiltDegrees: Float): PhotoQuality {
        val w = image.width
        val h = image.height
        val n = (w * h).toDouble()
        var sum = 0.0
        var sumSq = 0.0
        var glarePixels = 0
        for (v in image.pixels) {
            sum += v
            sumSq += v.toDouble() * v
            if (v >= GLARE_LUMA) glarePixels++
        }
        val brightness = (sum / n).toFloat()
        val variance = (sumSq / n - (sum / n) * (sum / n)).coerceAtLeast(0.0)
        val contrast = sqrt(variance).toFloat()
        val glareRatio = (glarePixels / n).toFloat()

        // 4 邻域拉普拉斯方差：只统计内部像素，避免把边界当边缘
        var lapSum = 0.0
        var lapSq = 0.0
        var lapCount = 0
        for (y in 1 until h - 1) {
            var idx = y * w + 1
            for (x in 1 until w - 1) {
                val c = image.pixels[idx]
                val lap = 4f * c - image.pixels[idx - 1] - image.pixels[idx + 1] -
                    image.pixels[idx - w] - image.pixels[idx + w]
                lapSum += lap
                lapSq += lap.toDouble() * lap
                lapCount++
                idx++
            }
        }
        val sharpness = if (lapCount == 0) 0f else {
            val mean = lapSum / lapCount
            (lapSq / lapCount - mean * mean).coerceAtLeast(0.0).toFloat()
        }

        val issues = buildList {
            if (sharpness < BLUR_SHARPNESS) add(PhotoIssue.BLURRY)
            if (brightness < DARK_BRIGHTNESS && contrast < DARK_MAX_CONTRAST) add(PhotoIssue.TOO_DARK)
            if (brightness > BRIGHT_BRIGHTNESS && contrast < BRIGHT_MAX_CONTRAST) add(PhotoIssue.TOO_BRIGHT)
            if (glareRatio > GLARE_RATIO && brightness < GLARE_MAX_BRIGHTNESS &&
                largestBrightComponentRatio(image) > GLARE_MIN_COMPONENT
            ) {
                add(PhotoIssue.GLARE)
            }
            if (abs(tiltDegrees) >= TILT_RETAKE_DEGREES) add(PhotoIssue.TILTED)
        }
        return PhotoQuality(
            sharpness = sharpness,
            brightness = brightness,
            contrast = contrast,
            glareRatio = glareRatio,
            tiltDegrees = tiltDegrees,
            issues = issues,
        )
    }
}
