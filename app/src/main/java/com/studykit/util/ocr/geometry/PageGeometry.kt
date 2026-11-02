package com.studykit.util.ocr.geometry

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import com.studykit.util.ocr.GrayImage
import com.studykit.util.ocr.grayImageOf
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** 页面四个角（原图像素坐标，顺序：左上、右上、右下、左下）。 */
data class PagePoint(val x: Float, val y: Float)

data class PageQuad(
    val topLeft: PagePoint,
    val topRight: PagePoint,
    val bottomRight: PagePoint,
    val bottomLeft: PagePoint,
) {
    fun points(): List<PagePoint> = listOf(topLeft, topRight, bottomRight, bottomLeft)
}

/**
 * 文档页检测（v3.9）：在拍照图上找纸张四角，供透视校正使用。
 *
 * 纯 Kotlin + 灰度统计，不引入 OpenCV：
 * 1. 缩到 480 长边灰度图，Otsu 二分出「纸（亮）/桌面（暗）」；
 * 2. 页面必须**没有铺满画面**（边框大部分是亮像素时直接放弃，截图/白底图不需要校正）；
 * 3. 亮区取最大连通域，用四个极值方向（x+y / x-y 的 min/max）取四个角 ——
 *    对凸四边形，这四个极值点恰好是四个顶点，比 minAreaRect 更稳；
 * 4. 校验凸性、边长、面积占比，不合格返回 null（不校正比校歪好）。
 *
 * 失败一律返回 null：透视校正只是增强，不能因为它把正常截图裁坏。
 */
object PageDetector {

    private const val ANALYSIS_MAX_SIDE = 480
    private const val MIN_PAGE_AREA_RATIO = 0.28f
    private const val MAX_PAGE_AREA_RATIO = 0.97f
    private const val MIN_SIDE_RATIO = 0.18f
    private const val BORDER_BRIGHT_RATIO = 0.6f

    fun detect(bitmap: Bitmap): PageQuad? {
        if (bitmap.width < 64 || bitmap.height < 64) return null
        val gray = grayImageOf(bitmap, ANALYSIS_MAX_SIDE)
        val quad = detectFromGray(gray)
        if (quad == null) return null
        val sx = bitmap.width.toFloat() / gray.width
        val sy = bitmap.height.toFloat() / gray.height
        return PageQuad(
            topLeft = PagePoint(quad.topLeft.x * sx, quad.topLeft.y * sy),
            topRight = PagePoint(quad.topRight.x * sx, quad.topRight.y * sy),
            bottomRight = PagePoint(quad.bottomRight.x * sx, quad.bottomRight.y * sy),
            bottomLeft = PagePoint(quad.bottomLeft.x * sx, quad.bottomLeft.y * sy),
        )
    }

    internal fun detectFromGray(gray: GrayImage): PageQuad? {
        val w = gray.width
        val h = gray.height
        val n = w * h
        if (n < 4096) return null

        // 边框亮像素占比：铺满画面的截图/白底图不校正
        var border = 0
        var borderBright = 0
        fun countBorder(x: Int, y: Int) {
            border++
            if (gray.at(x, y) >= 128f) borderBright++
        }
        for (x in 0 until w) { countBorder(x, 0); countBorder(x, h - 1) }
        for (y in 1 until h - 1) { countBorder(0, y); countBorder(w - 1, y) }
        if (border > 0 && borderBright.toFloat() / border > BORDER_BRIGHT_RATIO) return null

        val threshold = otsu(gray.pixels)
        if (threshold <= 0f || threshold >= 255f) return null

        val mask = BooleanArray(n)
        var brightCount = 0
        for (i in 0 until n) {
            if (gray.pixels[i] > threshold) {
                mask[i] = true
                brightCount++
            }
        }
        if (brightCount < n * 0.2f) return null

        // 最大连通域
        val visited = BooleanArray(n)
        val queue = IntArray(n)
        var bestSize = 0
        var bestStart = -1
        for (start in 0 until n) {
            if (!mask[start] || visited[start]) continue
            var head = 0
            var tail = 0
            queue[tail++] = start
            visited[start] = true
            var size = 0
            while (head < tail) {
                val idx = queue[head++]
                size++
                val x = idx % w
                val y = idx / w
                if (x > 0) push(mask, visited, queue, tail, idx - 1)?.let { tail = it }
                if (x + 1 < w) push(mask, visited, queue, tail, idx + 1)?.let { tail = it }
                if (y > 0) push(mask, visited, queue, tail, idx - w)?.let { tail = it }
                if (y + 1 < h) push(mask, visited, queue, tail, idx + w)?.let { tail = it }
            }
            if (size > bestSize) {
                bestSize = size
                bestStart = start
            }
        }
        if (bestStart < 0) return null
        val ratio = bestSize.toFloat() / n
        if (ratio < MIN_PAGE_AREA_RATIO || ratio > MAX_PAGE_AREA_RATIO) return null

        // 重新走一遍最大连通域收集坐标（只保留标记，避免存像素列表）
        val page = BooleanArray(n)
        run {
            var head = 0
            var tail = 0
            queue[tail++] = bestStart
            page[bestStart] = true
            while (head < tail) {
                val idx = queue[head++]
                val x = idx % w
                val y = idx / w
                if (x > 0) mark(mask, page, queue, tail, idx - 1)?.let { tail = it }
                if (x + 1 < w) mark(mask, page, queue, tail, idx + 1)?.let { tail = it }
                if (y > 0) mark(mask, page, queue, tail, idx - w)?.let { tail = it }
                if (y + 1 < h) mark(mask, page, queue, tail, idx + w)?.let { tail = it }
            }
        }

        var tlIdx = -1; var brIdx = -1; var trIdx = -1; var blIdx = -1
        var minSum = Int.MAX_VALUE; var maxSum = Int.MIN_VALUE
        var minDiff = Int.MAX_VALUE; var maxDiff = Int.MIN_VALUE
        for (idx in 0 until n) {
            if (!page[idx]) continue
            val x = idx % w
            val y = idx / w
            val sum = x + y
            val diff = x - y
            if (sum < minSum) { minSum = sum; tlIdx = idx }
            if (sum > maxSum) { maxSum = sum; brIdx = idx }
            if (diff > maxDiff) { maxDiff = diff; trIdx = idx }
            if (diff < minDiff) { minDiff = diff; blIdx = idx }
        }
        if (tlIdx < 0 || brIdx < 0 || trIdx < 0 || blIdx < 0) return null
        if (setOf(tlIdx, brIdx, trIdx, blIdx).size < 4) return null

        fun point(idx: Int) = PagePoint((idx % w).toFloat(), (idx / w).toFloat())
        val quad = PageQuad(point(tlIdx), point(trIdx), point(brIdx), point(blIdx))
        return quad.takeIf { validate(quad, w, h, ratio) }
    }

    private fun push(mask: BooleanArray, visited: BooleanArray, queue: IntArray, tail: Int, idx: Int): Int? {
        if (mask[idx] && !visited[idx]) {
            visited[idx] = true
            queue[tail] = idx
            return tail + 1
        }
        return null
    }

    private fun mark(mask: BooleanArray, page: BooleanArray, queue: IntArray, tail: Int, idx: Int): Int? {
        if (mask[idx] && !page[idx]) {
            page[idx] = true
            queue[tail] = idx
            return tail + 1
        }
        return null
    }

    private fun validate(quad: PageQuad, w: Int, h: Int, areaRatio: Float): Boolean {
        val pts = quad.points()
        // 凸性：相邻边叉积同号
        var sign = 0
        for (i in 0 until 4) {
            val a = pts[i]
            val b = pts[(i + 1) % 4]
            val c = pts[(i + 2) % 4]
            val cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
            if (abs(cross) < 1e-3) return false
            val s = if (cross > 0) 1 else -1
            if (sign == 0) sign = s else if (s != sign) return false
        }
        val diag = sqrt((w * w + h * h).toFloat())
        for (i in 0 until 4) {
            val a = pts[i]
            val b = pts[(i + 1) % 4]
            val len = sqrt((a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y))
            if (len < diag * MIN_SIDE_RATIO) return false
        }
        if (areaRatio < MIN_PAGE_AREA_RATIO || areaRatio > MAX_PAGE_AREA_RATIO) return false
        // 四角都要在画面内
        return pts.all { it.x >= 0f && it.y >= 0f && it.x <= w && it.y <= h }
    }

    /** Otsu 阈值；直方图单一峰（纯色）时返回 -1。 */
    internal fun otsu(pixels: FloatArray): Float {
        val hist = IntArray(256)
        for (v in pixels) {
            val i = v.toInt().coerceIn(0, 255)
            hist[i]++
        }
        val total = pixels.size
        var sum = 0.0
        for (i in 0 until 256) sum += i.toDouble() * hist[i]
        var sumB = 0.0
        var wB = 0
        var best = 0.0
        var threshold = -1
        for (i in 0 until 256) {
            wB += hist[i]
            if (wB == 0) continue
            val wF = total - wB
            if (wF == 0) break
            sumB += i.toDouble() * hist[i]
            val mB = sumB / wB
            val mF = (sum - sumB) / wF
            val between = wB.toDouble() * wF * (mB - mF) * (mB - mF)
            if (between > best) {
                best = between
                threshold = i
            }
        }
        return if (threshold < 0) -1f else threshold.toFloat()
    }
}

/**
 * 透视校正：把检测到的四边形映射成矩形。
 *
 * 用 android.graphics.Matrix.setPolyToPoly + Canvas 做一次双线性重采样（硬件/软件都能跑），
 * 不手写 8x8 求解；输出尺寸按四边形对边长度均值取整，最多 2400 长边，避免大图 OOM。
 */
object PerspectiveCorrector {

    fun correct(bitmap: Bitmap, quad: PageQuad): Bitmap? {
        val size = targetSize(quad) ?: return null
        val (outW, outH) = size
        if (outW < 64 || outH < 64) return null
        return runCatching {
            val src = floatArrayOf(
                quad.topLeft.x, quad.topLeft.y,
                quad.topRight.x, quad.topRight.y,
                quad.bottomRight.x, quad.bottomRight.y,
                quad.bottomLeft.x, quad.bottomLeft.y,
            )
            val dst = floatArrayOf(
                0f, 0f,
                outW.toFloat(), 0f,
                outW.toFloat(), outH.toFloat(),
                0f, outH.toFloat(),
            )
            val matrix = Matrix()
            if (!matrix.setPolyToPoly(src, 0, dst, 0, 4)) return null
            val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            canvas.drawColor(0xFFFFFFFF.toInt())
            canvas.drawBitmap(bitmap, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
            out
        }.getOrNull()
    }

    internal fun targetSize(quad: PageQuad): Pair<Int, Int>? {
        fun dist(a: PagePoint, b: PagePoint): Float =
            sqrt((a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y))
        val top = dist(quad.topLeft, quad.topRight)
        val bottom = dist(quad.bottomLeft, quad.bottomRight)
        val left = dist(quad.topLeft, quad.bottomLeft)
        val right = dist(quad.topRight, quad.bottomRight)
        val avgW = (top + bottom) / 2f
        val avgH = (left + right) / 2f
        if (avgW < 32f || avgH < 32f) return null
        val long = max(avgW, avgH)
        val scale = if (long > 2400f) 2400f / long else 1f
        return (avgW * scale).roundToInt().coerceAtLeast(32) to
            (avgH * scale).roundToInt().coerceAtLeast(32)
    }
}
