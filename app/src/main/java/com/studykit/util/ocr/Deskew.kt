package com.studykit.util.ocr

import android.graphics.Bitmap
import android.graphics.Matrix
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.tan

/**
 * 文档摆正：用投影法估计文字行相对水平的夹角，再按反方向旋转整页。
 *
 * 为什么不用霍夫直线/轮廓四边形：拍照页里同时有纸张边框、表格线、手写字与阴影，
 * 霍夫挑哪条线都不稳；而「文字行投影后行间空白最分明」这一条对多行文本稳定成立，
 * 也正好是 PP-OCR 的 det/rec 需要的口径（det 假设文本线水平，rec 按行切片）。
 *
 * 实现刻意保持纯 CPU + 采样：在 480 长边的灰度缩略图上做剪切采样求和，不建旋转中间图。
 * 粗搜（2° 步）→ 细搜（0.5° 步）→ 只有当最优投影方差比 0° 高 3% 以上才认，
 * 否则返回 0：把噪点当倾斜、整页重采样一次，反而会掉清晰度与识别率。
 *
 * **角度约定**：返回值是「画面内容相对水平的倾斜角」，正 = 内容向右下倾（顺时针倾斜）。
 * 校正时调用 [rotate] 传负值。
 */
object Deskew {

    /** 分析用的长边上限：A4 竖页约 340×480，细笔画仍保留，单角度开销不到 1ms。 */
    internal const val ANALYSIS_MAX_SIDE = 480

    /** 小于这个角度不旋转：±1° 以内重采样掉的清晰度比摆正收益更大。 */
    const val MIN_APPLY_DEGREES = 1.0f

    /** 大于这个角度更像是侧拍/非文档，不旋转，留给质量门控提示重拍。 */
    const val MAX_APPLY_DEGREES = 12f

    private const val COARSE_STEP = 2f
    private const val FINE_STEP = 0.5f

    /** 最优角相对 0° 的投影方差增益下限，低于它视作水平。 */
    private const val MIN_SCORE_GAIN = 1.03

    /**
     * 投影方差的绝对下限（0..255 亮度量纲）。
     * 纯噪声的行均值方差只有个位数，光看"相对 0° 的增益"会把随机起伏当成倾斜（实测 -4°）；
     * 文字页的行间空白会把方差拉到几百上千，这条绝对门槛是噪声与文本的分界线。
     */
    private const val MIN_ROW_VARIANCE = 25.0

    /** 估计整页的倾斜角（度）。给不出可信角度时返回 0。 */
    fun estimateDegrees(bitmap: Bitmap): Float =
        estimateFromGray(grayImageOf(bitmap, ANALYSIS_MAX_SIDE))

    /** 纯逻辑入口：单测直接用合成灰度图跑，不需要 Android Bitmap 也能量化误差。 */
    internal fun estimateFromGray(image: GrayImage): Float {
        if (image.width < 16 || image.height < 8) return 0f

        var coarseAngle = 0f
        var coarseScore = Double.NEGATIVE_INFINITY
        var angle = -MAX_APPLY_DEGREES
        while (angle <= MAX_APPLY_DEGREES + 0.001f) {
            val score = rowVariance(image, angle)
            if (score > coarseScore) {
                coarseScore = score
                coarseAngle = angle
            }
            angle += COARSE_STEP
        }

        var bestAngle = 0f
        var bestScore = Double.NEGATIVE_INFINITY
        var fine = (coarseAngle - COARSE_STEP).coerceAtLeast(-MAX_APPLY_DEGREES)
        val fineEnd = (coarseAngle + COARSE_STEP).coerceAtMost(MAX_APPLY_DEGREES)
        while (fine <= fineEnd + 0.001f) {
            val score = rowVariance(image, fine)
            if (score > bestScore) {
                bestScore = score
                bestAngle = fine
            }
            fine += FINE_STEP
        }

        val flatScore = rowVariance(image, 0f)
        if (flatScore <= 0.0 || bestScore < flatScore * MIN_SCORE_GAIN) return 0f
        if (bestScore < MIN_ROW_VARIANCE) return 0f
        return (bestAngle * 2f).roundToInt() / 2f
    }

    /**
     * 按 [degrees] 旋转整页（正 = 顺时针），返回新 bitmap。
     * 0° 或旋转失败（OOM/尺寸非法）时原样返回，绝不抛给调用方 —— 摆正只是增强，不是必需路径。
     */
    fun rotate(bitmap: Bitmap, degrees: Float): Bitmap {
        if (abs(degrees) < 0.1f) return bitmap
        val matrix = Matrix().apply { postRotate(degrees) }
        return runCatching {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }.getOrDefault(bitmap)
    }

    /**
     * 投影方差：把图像按倾角 [degrees] 剪切后，逐行求均值再算行均值的方差。
     * 文字行对齐时行间空白把方差拉高；错位时行均值趋于一致、方差变小。
     * 每行除以实际采样点数，边缘（剪切后落空的部分）不参与平均，避免边界行把方差带偏。
     */
    private fun rowVariance(image: GrayImage, degrees: Float): Double {
        val w = image.width
        val h = image.height
        val slope = tan(Math.toRadians(degrees.toDouble()))
        val center = (w - 1) / 2.0
        var sum = 0.0
        var sumSq = 0.0
        for (y in 0 until h) {
            var rowSum = 0.0
            var count = 0
            for (x in 0 until w) {
                val sy = y + slope * (x - center)
                val iy = sy.toInt()
                if (iy in 0 until h) {
                    rowSum += image.at(x, iy)
                    count++
                }
            }
            if (count > 0) {
                val mean = rowSum / count
                sum += mean
                sumSq += mean * mean
            }
        }
        val n = h.toDouble()
        return sumSq / n - (sum / n) * (sum / n)
    }
}
