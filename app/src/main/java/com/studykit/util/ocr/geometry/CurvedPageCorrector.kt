package com.studykit.util.ocr.geometry

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import com.studykit.util.ocr.GrayImage
import com.studykit.util.ocr.grayImageOf
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * v3.11：弯曲书页检测结果。
 *
 * [top]/[bottom] 是**降采样灰度图坐标**下每一列纸张的上下边界；[validFrom]..[validTo]
 * 是有效列范围。dewarp 时按原图/降采样图比例放大。
 */
data class PageCurves(
    val width: Int,
    val height: Int,
    val top: IntArray,
    val bottom: IntArray,
    val validFrom: Int,
    val validTo: Int,
    /** 上下边界最大弯曲量 / 页高；小于阈值说明是平面页，走透视校正即可 */
    val curvatureRatio: Float,
)

/**
 * 弯曲页面检测（v3.11）：找纸张上下边界的曲线。
 *
 * 与 [PageDetector] 的区别：
 * - PageDetector 把纸页当凸四边形，适合平面斜拍；
 * - 本类看上下边界是否「弯」：厚书摊开、书脊附近纸面会向上/向下拱，四边形的直边拟合不了；
 * - 只做**纵向展开**（按列把上边界拉齐、下边界拉齐），不做左右弯曲校正；
 * - 曲率不够就返回 null，交回透视校正，避免把正常平面页误展。
 *
 * 纯灰度统计 + 连通域，不引 OpenCV；检测在 480 长边缩略图上做，dewarp 才回原图。
 */
object CurvedPageDetector {

    private const val ANALYSIS_MAX_SIDE = 480
    private const val MIN_CURVATURE = 0.012f
    private const val MAX_CURVATURE = 0.12f
    private const val MIN_VALID_COLUMNS = 0.35f
    private const val BORDER_BRIGHT_RATIO = 0.6f
    private const val SMOOTH_RADIUS = 3

    fun detect(bitmap: Bitmap): PageCurves? {
        if (bitmap.width < 64 || bitmap.height < 64) return null
        val gray = grayImageOf(bitmap, ANALYSIS_MAX_SIDE)
        return detectFromGray(gray)
    }

    internal fun detectFromGray(gray: GrayImage): PageCurves? {
        val w = gray.width
        val h = gray.height
        val n = w * h
        if (n < 4096) return null

        // 铺满画面的截图/白底图不做弯曲校正
        var border = 0
        var borderBright = 0
        fun countBorder(x: Int, y: Int) {
            border++
            if (gray.at(x, y) >= 128f) borderBright++
        }
        for (x in 0 until w) { countBorder(x, 0); countBorder(x, h - 1) }
        for (y in 1 until h - 1) { countBorder(0, y); countBorder(w - 1, y) }
        if (border > 0 && borderBright.toFloat() / border > BORDER_BRIGHT_RATIO) {
            return null
        }

        val threshold = PageDetector.otsu(gray.pixels)
        if (threshold <= 0f || threshold >= 255f) return null
        val mask = BooleanArray(n)
        var bright = 0
        for (i in 0 until n) {
            if (gray.pixels[i] > threshold) {
                mask[i] = true
                bright++
            }
        }
        if (bright < n * 0.2f) return null

        // 最大亮区（纸页）
        val visited = BooleanArray(n)
        val queue = IntArray(n)
        var bestSize = 0
        var bestStart = -1
        var head = 0
        var tail = 0
        for (start in 0 until n) {
            if (!mask[start] || visited[start]) continue
            head = 0
            tail = 0
            queue[tail++] = start
            visited[start] = true
            var size = 0
            while (head < tail) {
                val idx = queue[head++]
                size++
                val x = idx % w
                val y = idx / w
                if (x > 0 && mask[idx - 1] && !visited[idx - 1]) {
                    visited[idx - 1] = true; queue[tail++] = idx - 1
                }
                if (x + 1 < w && mask[idx + 1] && !visited[idx + 1]) {
                    visited[idx + 1] = true; queue[tail++] = idx + 1
                }
                if (y > 0 && mask[idx - w] && !visited[idx - w]) {
                    visited[idx - w] = true; queue[tail++] = idx - w
                }
                if (y + 1 < h && mask[idx + w] && !visited[idx + w]) {
                    visited[idx + w] = true; queue[tail++] = idx + w
                }
            }
            if (size > bestSize) {
                bestSize = size
                bestStart = start
            }
        }
        if (bestStart < 0) return null
        val areaRatio = bestSize.toFloat() / n
        if (areaRatio < 0.2f || areaRatio > 0.98f) return null

        val page = BooleanArray(n)
        head = 0
        tail = 0
        queue[tail++] = bestStart
        page[bestStart] = true
        while (head < tail) {
            val idx = queue[head++]
            val x = idx % w
            val y = idx / w
            if (x > 0 && mask[idx - 1] && !page[idx - 1]) {
                page[idx - 1] = true; queue[tail++] = idx - 1
            }
            if (x + 1 < w && mask[idx + 1] && !page[idx + 1]) {
                page[idx + 1] = true; queue[tail++] = idx + 1
            }
            if (y > 0 && mask[idx - w] && !page[idx - w]) {
                page[idx - w] = true; queue[tail++] = idx - w
            }
            if (y + 1 < h && mask[idx + w] && !page[idx + w]) {
                page[idx + w] = true; queue[tail++] = idx + w
            }
        }

        val top = IntArray(w) { -1 }
        val bottom = IntArray(w) { -1 }
        for (x in 0 until w) {
            for (y in 0 until h) {
                if (page[y * w + x]) { top[x] = y; break }
            }
            for (y in h - 1 downTo 0) {
                if (page[y * w + x]) { bottom[x] = y; break }
            }
        }
        var from = w
        var to = -1
        for (x in 0 until w) {
            if (top[x] >= 0 && bottom[x] > top[x]) {
                if (x < from) from = x
                if (x > to) to = x
            }
        }
        if (to - from + 1 < (w * MIN_VALID_COLUMNS).roundToInt()) return null

        // 先算一次页高中位数，再把左右两端的「纸页尖角列」裁掉：
        // 透视四边形最边上几列可能只剩几个像素高的纸页，会把边界曲率算爆。
        val coarseSpans = ArrayList<Int>()
        for (x in from..to) {
            val span = bottom[x] - top[x]
            if (span > 4) coarseSpans.add(span)
        }
        if (coarseSpans.isEmpty()) return null
        coarseSpans.sort()
        val coarseMedian = coarseSpans[coarseSpans.size / 2].toFloat()
        while (from <= to && (bottom[from] - top[from]) < coarseMedian * 0.55f) from++
        while (to >= from && (bottom[to] - top[to]) < coarseMedian * 0.55f) to--
        if (to - from + 1 < (w * MIN_VALID_COLUMNS).roundToInt()) return null

        // 线性插值补缺失列（页边反光/被手指挡住时个别列没有边界）
        fillMissing(top, from, to)
        fillMissing(bottom, from, to)
        // 宽窗中值滤两遍：书页边缘被阴影/手指截掉一截时，极值角列会把曲率算爆；
        // 中值滤波能压掉这种「局部平台」，同时保留真正的弯曲趋势。
        medianSmooth(top, from, to, 9, 2)
        medianSmooth(bottom, from, to, 9, 2)
        smooth(top, from, to)
        smooth(bottom, from, to)

        var medianSpan = 0f
        val spans = ArrayList<Int>()
        for (x in from..to) {
            val span = bottom[x] - top[x]
            if (span > 4) spans.add(span)
        }
        if (spans.isEmpty()) return null
        spans.sort()
        medianSpan = spans[spans.size / 2].toFloat()
        if (medianSpan < h * 0.2f) return null

        val topResidual = straightLineResidual(top, from, to)
        val bottomResidual = straightLineResidual(bottom, from, to)
        val curvature = max(topResidual, bottomResidual) / medianSpan
        if (curvature < MIN_CURVATURE || curvature > MAX_CURVATURE) return null
        return PageCurves(w, h, top, bottom, from, to, curvature)
    }

    private fun fillMissing(values: IntArray, from: Int, to: Int) {
        var x = from
        while (x <= to) {
            if (values[x] >= 0) { x++; continue }
            val left = x - 1
            var right = x
            while (right <= to && values[right] < 0) right++
            if (left < from || right > to) { x = right; continue }
            val a = values[left]
            val b = values[right]
            for (k in x until right) {
                val t = (k - left).toFloat() / (right - left)
                values[k] = (a + (b - a) * t).roundToInt()
            }
            x = right + 1
        }
    }

    private fun smooth(values: IntArray, from: Int, to: Int) {
        val copy = values.copyOf()
        for (x in from..to) {
            var sum = 0
            var count = 0
            for (k in max(from, x - SMOOTH_RADIUS)..min(to, x + SMOOTH_RADIUS)) {
                sum += copy[k]
                count++
            }
            values[x] = (sum.toFloat() / count).roundToInt()
        }
    }

    private fun medianSmooth(values: IntArray, from: Int, to: Int, radius: Int, passes: Int) {
        repeat(passes) {
            val copy = values.copyOf()
            for (x in from..to) {
                val lo = max(from, x - radius)
                val hi = min(to, x + radius)
                val window = IntArray(hi - lo + 1) { copy[lo + it] }
                window.sort()
                values[x] = window[window.size / 2]
            }
        }
    }

    /** 过首尾两点的直线，返回中间列的最大残差（像素）。 */
    private fun straightLineResidual(values: IntArray, from: Int, to: Int): Float {
        val start = values[from]
        val end = values[to]
        var maxResidual = 0f
        for (x in from..to) {
            val t = (x - from).toFloat() / max(1, to - from)
            val line = start + (end - start) * t
            maxResidual = max(maxResidual, abs(values[x] - line))
        }
        return maxResidual
    }
}

/**
 * 去弯曲：按列把纸张上边界拉到第 0 行、下边界拉到第 H-1 行。
 *
 * 只做纵向重采样；左右弯曲/侧向透视仍交给后续 Deskew/OCR 容错。
 * 输出宽度 = 有效列数，高度 = 有效列页高中位数，长边封顶 2400。
 */
object CurvedPageDewarper {

    fun dewarp(bitmap: Bitmap, curves: PageCurves): Bitmap? {
        val validCols = curves.validTo - curves.validFrom + 1
        if (validCols < 32) return null
        val heights = ArrayList<Int>(validCols)
        for (x in curves.validFrom..curves.validTo) {
            val span = curves.bottom[x] - curves.top[x]
            if (span > 8) heights.add(span)
        }
        if (heights.isEmpty()) return null
        heights.sort()
        val medianSpan = heights[heights.size / 2].toFloat()

        // PageCurves 是 480 长边缩略图坐标；输出要回到原图分辨率，
        // 否则会把 1000x1300 的页面压成 ~300x400，OCR 直接读不出来。
        val sxRatio = bitmap.width.toFloat() / curves.width
        val syRatio = bitmap.height.toFloat() / curves.height
        var outW = (validCols * sxRatio).roundToInt().coerceAtLeast(64)
        var outH = (medianSpan * syRatio).roundToInt().coerceAtLeast(64)
        val longest = max(outW, outH)
        if (longest > 2400) {
            val k = 2400f / longest
            outW = (outW * k).roundToInt().coerceAtLeast(64)
            outH = (outH * k).roundToInt().coerceAtLeast(64)
        }

        // 用 Canvas 按列把源图竖条拉伸到输出列：
        // 1) 避免 Bitmap.getPixels 在 Robolectric 原生图形下返回异常数据（JVM 评测失真）；
        // 2) 每列上边界->0、下边界->H，天然完成纵向去弯曲；
        // 3) 真机上 Canvas 由 Skia 加速，比逐像素双线性快。
        val outBmp = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(outBmp)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        val srcRect = Rect()
        val dstRect = Rect()
        for (i in 0 until outW) {
            val gx0 = curves.validFrom + i.toFloat() / outW * validCols
            val gx1 = curves.validFrom + (i + 1f) / outW * validCols
            val sx0 = (gx0 * sxRatio).roundToInt().coerceIn(0, bitmap.width - 1)
            val sx1 = (gx1 * sxRatio).roundToInt().coerceIn(sx0 + 1, bitmap.width)
            val topY = ((sampleCurve(curves.top, gx0) + sampleCurve(curves.top, gx1)) / 2f * syRatio)
                .roundToInt().coerceIn(0, bitmap.height - 2)
            val bottomY = ((sampleCurve(curves.bottom, gx0) + sampleCurve(curves.bottom, gx1)) / 2f * syRatio)
                .roundToInt().coerceIn(topY + 1, bitmap.height)
            srcRect.set(sx0, topY, sx1, bottomY)
            dstRect.set(i, 0, i + 1, outH)
            canvas.drawBitmap(bitmap, srcRect, dstRect, paint)
        }
        return outBmp
    }


    private fun sampleCurve(values: IntArray, x: Float): Float {
        val x0 = x.toInt()
        val x1 = min(x0 + 1, values.lastIndex)
        val t = x - x0
        return values[x0] * (1 - t) + values[x1] * t
    }

}
