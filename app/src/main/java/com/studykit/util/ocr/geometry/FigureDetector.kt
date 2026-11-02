package com.studykit.util.ocr.geometry

import android.graphics.Bitmap
import com.studykit.util.ocr.GrayImage
import com.studykit.util.ocr.OcrBox
import com.studykit.util.ocr.grayImageOf
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 图形/表格区域检测（v3.9）：OCR 只认识文字，几何图形、函数图像、表格框线在文本层里是空的。
 *
 * 做法：在 480 长边灰度图上按 16px 网格统计边缘密度，剔掉已覆盖 OCR 文本框的格子，
 * 再把高密度格子连通成区域。只用于**保留裁片/提示用户看原图**，不声称理解图形内容。
 *
 * 宁少勿多：区域面积、长宽比、格子数都设下限，页面大片空白/纸纹/阴影不会触发。
 */
object FigureDetector {

    private const val ANALYSIS_MAX_SIDE = 480
    private const val CELL = 16
    private const val EDGE_THRESHOLD = 42f
    private const val EDGE_DENSITY = 0.10f
    private const val MIN_CELLS = 6
    private const val MAX_PAGE_RATIO = 0.35f
    private const val MIN_AREA_RATIO = 0.006f

    fun detect(bitmap: Bitmap, textBoxes: List<OcrBox>): List<OcrBox> {
        if (bitmap.width < 64 || bitmap.height < 64) return emptyList()
        val gray = grayImageOf(bitmap, ANALYSIS_MAX_SIDE)
        val regions = detectFromGray(gray, textBoxes.map { scaleBox(it, bitmap.width, bitmap.height, gray.width, gray.height) })
        val sx = bitmap.width.toFloat() / gray.width
        val sy = bitmap.height.toFloat() / gray.height
        return regions.map {
            OcrBox(
                x0 = (it.x0 * sx).roundToInt(),
                y0 = (it.y0 * sy).roundToInt(),
                x1 = (it.x1 * sx).roundToInt(),
                y1 = (it.y1 * sy).roundToInt(),
            )
        }
    }

    private fun scaleBox(box: OcrBox, sw: Int, sh: Int, tw: Int, th: Int): OcrBox {
        val fx = tw.toFloat() / sw
        val fy = th.toFloat() / sh
        return OcrBox(
            (box.x0 * fx).roundToInt(),
            (box.y0 * fy).roundToInt(),
            (box.x1 * fx).roundToInt(),
            (box.y1 * fy).roundToInt(),
        )
    }

    internal fun detectFromGray(gray: GrayImage, textBoxes: List<OcrBox>): List<OcrBox> {
        val w = gray.width
        val h = gray.height
        val cols = (w + CELL - 1) / CELL
        val rows = (h + CELL - 1) / CELL
        if (cols < 4 || rows < 4) return emptyList()

        // 文本覆盖：框外扩 2px，避免文字边缘漏进图形
        val covered = BooleanArray(cols * rows)
        for (box in textBoxes) {
            val c0 = ((box.x0 - 2).coerceAtLeast(0)) / CELL
            val c1 = ((box.x1 + 2).coerceAtMost(w - 1)) / CELL
            val r0 = ((box.y0 - 2).coerceAtLeast(0)) / CELL
            val r1 = ((box.y1 + 2).coerceAtMost(h - 1)) / CELL
            for (r in r0..r1) {
                for (c in c0..c1) covered[r * cols + c] = true
            }
        }

        val edge = FloatArray(w * h)
        for (y in 1 until h - 1) {
            var idx = y * w + 1
            for (x in 1 until w - 1) {
                val dx = gray.pixels[idx + 1] - gray.pixels[idx - 1]
                val dy = gray.pixels[idx + w] - gray.pixels[idx - w]
                edge[idx] = kotlin.math.sqrt(dx * dx + dy * dy)
                idx++
            }
        }

        val marked = BooleanArray(cols * rows)
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                if (covered[r * cols + c]) continue
                var count = 0
                val x0 = c * CELL
                val y0 = r * CELL
                val x1 = min(w, x0 + CELL)
                val y1 = min(h, y0 + CELL)
                for (y in y0 until y1) {
                    var idx = y * w + x0
                    for (x in x0 until x1) {
                        if (edge[idx] > EDGE_THRESHOLD) count++
                        idx++
                    }
                }
                val cells = (x1 - x0) * (y1 - y0)
                if (cells > 0 && count.toFloat() / cells >= EDGE_DENSITY) {
                    marked[r * cols + c] = true
                }
            }
        }

        // 8 邻域连通
        val visited = BooleanArray(cols * rows)
        val queue = IntArray(cols * rows)
        val out = ArrayList<OcrBox>()
        for (start in 0 until marked.size) {
            if (!marked[start] || visited[start]) continue
            var head = 0
            var tail = 0
            queue[tail++] = start
            visited[start] = true
            var minC = cols; var maxC = -1; var minR = rows; var maxR = -1
            var cells = 0
            while (head < tail) {
                val idx = queue[head++]
                cells++
                val c = idx % cols
                val r = idx / cols
                if (c < minC) minC = c
                if (c > maxC) maxC = c
                if (r < minR) minR = r
                if (r > maxR) maxR = r
                for (dr in -1..1) {
                    for (dc in -1..1) {
                        if (dr == 0 && dc == 0) continue
                        val nr = r + dr
                        val nc = c + dc
                        if (nr !in 0 until rows || nc !in 0 until cols) continue
                        val n = nr * cols + nc
                        if (marked[n] && !visited[n]) {
                            visited[n] = true
                            queue[tail++] = n
                        }
                    }
                }
            }
            if (cells < MIN_CELLS) continue
            val x0 = minC * CELL
            val y0 = minR * CELL
            val x1 = min(w, (maxC + 1) * CELL)
            val y1 = min(h, (maxR + 1) * CELL)
            val boxW = x1 - x0
            val boxH = y1 - y0
            val areaRatio = (boxW * boxH).toFloat() / (w * h)
            if (areaRatio < MIN_AREA_RATIO || areaRatio > MAX_PAGE_RATIO) continue
            val aspect = boxW.toFloat() / max(1, boxH)
            if (aspect < 0.12f || aspect > 8f) continue
            if (boxW < CELL * 2 || boxH < CELL * 2) continue
            out.add(OcrBox(x0, y0, x1, y1))
        }
        return out.sortedByDescending { (it.width * it.height) }.take(4)
    }
}
