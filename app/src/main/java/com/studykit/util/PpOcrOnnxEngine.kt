package com.studykit.util

import android.content.Context
import android.graphics.Bitmap
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * PP-OCRv6（small det + small rec）纯 ONNX Runtime Android 推理，全离线、无 OpenCV。
 *
 * 与 RapidOCR(Python) 的对应关系见 docs/evidence/ocr-ab-models.md。刻意复刻的口径：
 * - Det 预处理：limit_type=min、limit_side_len=736，resize 后取 32 倍数，BGR 通道，(x/255-0.5)/0.5；
 * - DB 后处理：thresh=0.3 → 2×2 膨胀 → 连通域 → 外接框 → box_score_fast(>0.5) → unclip(1.6) → 坐标回映 → 过滤 → sorted_boxes；
 * - Rec：H=48、按批次 max_wh_ratio 定宽、右侧 0 补齐、BGR、(x/255-0.5)/0.5；CTC 贪心（blank=idx0）。
 *
 * **偏离原实现的取舍（如实写进报告）**：
 * 1. 不做旋转框——用连通域的**轴对齐外接框**代替 cv2.minAreaRect。合成打印集是水平文本，足够；
 *    倾斜/竖排真实照片会漏检或框歪。
 * 2. 不做方向分类 cls（任务只要求 det→rec）。假设文本线水平，跳过 h/w≥1.5 的 90° 旋转。
 * 3. box_score_fast 对**外接矩形**求均值，而非旋转多边形；unclip 用矩形面积/周长近似 pyclipper 圆角外扩。
 * 4. findContours → 连通域标记（8-连通）近似。
 */
object PpOcrOnnxEngine {

    // ── 复刻自 rapidocr config.yaml（PP-OCRv6 默认）──
    private const val DET_LIMIT_SIDE_LEN = 736
    private const val DET_LIMIT_TYPE_MIN = true
    private const val DET_THRESH = 0.3f
    private const val DET_BOX_THRESH = 0.5f
    private const val DET_UNCLIP_RATIO = 1.6f
    private const val DET_USE_DILATION = true
    private const val DET_MIN_SIZE = 3
    private const val TEXT_SCORE = 0.5f
    private const val REC_IMG_H = 48
    private const val REC_IMG_W_REF = 320 // rec_img_shape=[3,48,320]，imgW/imgH 是批次基准 wh_ratio

    // 内存闸门：A/B 在真机跑，长边超过它就等比降采样，避免大图 OOM（合成卡 1440 不触发）。
    private const val MAX_DET_SIDE = 1600

    data class PpLine(val text: String, val score: Float)

    class LoadInfo(var detMs: Long = 0, var recMs: Long = 0, var dictSize: Int = 0)

    private val lock = Any()
    private var env: OrtEnvironment? = null
    private var detSession: OrtSession? = null
    private var recSession: OrtSession? = null
    private var detInput: String = ""
    private var detOutput: String = ""
    private var recInput: String = ""
    private var recOutput: String = ""
    private lateinit var chars: List<String> // index 0 = blank, 1..N = dict, N+1 = " "
    val loadInfo = LoadInfo()

    /** 惰性加载模型 + 字典。返回首次加载耗时（ms）。重复调用是幂等的。 */
    fun ensureLoaded(context: Context) {
        synchronized(lock) {
            if (detSession != null && recSession != null && ::chars.isInitialized) return
            val e = OrtEnvironment.getEnvironment()
            env = e
            loadInfo.detMs = time {
                val bytes = context.assets.open("models/det_ppocrv6_small.onnx").use { it.readBytes() }
                detSession = e.createSession(bytes, sessionOpts())
                detInput = detSession!!.inputNames.first()
                detOutput = detSession!!.outputNames.first()
            }
            loadInfo.recMs = time {
                val bytes = context.assets.open("models/rec_ppocrv6_small.onnx").use { it.readBytes() }
                recSession = e.createSession(bytes, sessionOpts())
                recInput = recSession!!.inputNames.first()
                recOutput = recSession!!.outputNames.first()
            }
            loadInfo.dictSize = timeDict {
                val raw = context.assets.open("models/ppocr_keys_v6.txt")
                    .bufferedReader(Charsets.UTF_8).use { it.readText() }
                val dictLines = raw.split("\n")
                chars = buildList {
                    add("blank")            // CTC blank -> index 0
                    addAll(dictLines)       // 真实字符 -> 1..N
                    add(" ")                // 空格 -> N+1
                }
                dictLines.size
            }
        }
    }

    private fun sessionOpts(): OrtSession.SessionOptions =
        OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(2)
            setInterOpNumThreads(1)
        }

    /**
     * bitmap（已按 EXIF 转正）→ List<PpLine>。det→rec，逐行返回识别文本。
     * 任一模型抛异常都向上抛（harness 记录为 engine_failure）。
     */
    fun recognize(bitmap: Bitmap): List<PpLine> {
        ensureLoadedContextless()
        val boxes = detect(bitmap)
        if (boxes.isEmpty()) return emptyList()
        val out = ArrayList<PpLine>(boxes.size)
        for (b in boxes) {
            val crop = cropRect(bitmap, b) ?: continue
            val (text, score) = recognizeLine(crop)
            crop.recycle()
            if (text.isNotEmpty() && score >= TEXT_SCORE) out.add(PpLine(text, score))
        }
        return out
    }

    private fun ensureLoadedContextless() {
        check(detSession != null && recSession != null && ::chars.isInitialized) {
            "PpOcrOnnxEngine 未加载：先调用 ensureLoaded(context)"
        }
    }

    // ── DET ────────────────────────────────────────────────────────────────

    /** 一个文本框（原图像素坐标，轴对齐）；points 供 sorted_boxes 用（tl）。 */
    data class DetBox(val x0: Int, val y0: Int, val x1: Int, val y1: Int, val score: Float)

    private fun detect(bitmap: Bitmap): List<DetBox> {
        val e = env!!
        val origW = bitmap.width
        val origH = bitmap.height
        // 1) resize（limit_type=min 736 + 32 倍数 + 内存闸门）
        val (rw, rh) = detResizeDims(origW, origH)
        val scaled = Bitmap.createScaledBitmap(bitmap, rw, rh, true)
        val tensor = FloatArray(3 * rh * rw)
        fillDetTensor(scaled, tensor, rw, rh)
        if (scaled !== bitmap) scaled.recycle()

        // 2) 推理
        val pred: Array<FloatArray>
        OnnxTensor.createTensor(e, FloatBuffer.wrap(tensor), longArrayOf(1, 3, rh.toLong(), rw.toLong())).use { input ->
            detSession!!.run(mapOf(detInput to input)).use { res ->
                val o = res.get(detOutput).get().getValue() as Array<Array<Array<FloatArray>>>
                val chan = o[0][0] // [h][w]（batch=1, chan=1）
                pred = Array(rh) { y -> chan[y] }
            }
        }

        // 3) DB 后处理
        val boxes = dbPostProcess(pred, rw, rh, origW, origH)
        return sortedBoxes(boxes)
    }

    /** limit_type=min + MAX_DET_SIDE 闸门 → 取 32 倍数后的 (w,h)。 */
    internal fun detResizeDims(origW: Int, origH: Int): Pair<Int, Int> {
        var w = origW.toDouble()
        var h = origH.toDouble()
        // 内存闸门：长边超过 MAX_DET_SIDE 先整体等比缩到闸门内
        val longSide = max(w, h)
        if (longSide > MAX_DET_SIDE) {
            val g = MAX_DET_SIDE / longSide
            w *= g; h *= g
        }
        var ratio = 1.0
        if (DET_LIMIT_TYPE_MIN) {
            val minSide = min(w, h)
            if (minSide < DET_LIMIT_SIDE_LEN) ratio = DET_LIMIT_SIDE_LEN / minSide
        }
        val rh = (Math.round(h * ratio / 32.0) * 32).toInt().coerceAtLeast(32)
        val rw = (Math.round(w * ratio / 32.0) * 32).toInt().coerceAtLeast(32)
        return rw to rh
    }

    /** scaled bitmap(ARGB) → BGR planar 归一化 float[B*3*C*H*W]，C 序 = B,G,R（对齐 cv2）。 */
    private fun fillDetTensor(src: Bitmap, out: FloatArray, w: Int, h: Int) {
        val row = IntArray(w)
        val plane = h * w
        for (y in 0 until h) {
            src.getPixels(row, 0, w, 0, y, w, 1)
            val rowBase = y * w
            for (x in 0 until w) {
                val px = row[x]
                val b = (px and 0xFF) / 255.0f
                val g = ((px shr 8) and 0xFF) / 255.0f
                val r = ((px shr 16) and 0xFF) / 255.0f
                val i = rowBase + x
                out[i] = (b - 0.5f) / 0.5f
                out[plane + i] = (g - 0.5f) / 0.5f
                out[2 * plane + i] = (r - 0.5f) / 0.5f
            }
        }
    }

    /**
     * DB 后处理核心（无 cv2）：阈值 → 2×2 膨胀 → 8-连通域 → 轴对齐框 → box_score_fast → unclip → 回映 → 过滤。
     * 返回原图坐标 DetBox 列表（未排序）。抽出为 internal 供单测在固定向量上跑 red/green。
     */
    internal fun dbPostProcess(
        pred: Array<FloatArray>,
        resizedW: Int,
        resizedH: Int,
        origW: Int,
        origH: Int,
    ): List<DetBox> {
        // 阈值化 + 膨胀
        val mask = BooleanArray(resizedH * resizedW)
        for (y in 0 until resizedH) {
            val pr = pred[y]
            val base = y * resizedW
            for (x in 0 until resizedW) mask[base + x] = pr[x] > DET_THRESH
        }
        if (DET_USE_DILATION) {
            val dilated = BooleanArray(mask.size)
            for (y in 0 until resizedH) {
                for (x in 0 until resizedW) {
                    // cv2.dilate, kernel 2×2, 默认 anchor=(1,1) → 取以 (y,x) 为右下角的 2×2 邻域最大值
                    var v = mask[y * resizedW + x]
                    if (!v && y > 0) v = mask[(y - 1) * resizedW + x]
                    if (!v && x > 0) v = mask[y * resizedW + (x - 1)]
                    if (!v && x > 0 && y > 0) v = mask[(y - 1) * resizedW + (x - 1)]
                    dilated[y * resizedW + x] = v
                }
            }
            mask.indices.forEach { mask[it] = dilated[it] }
        }

        val comps = connectedComponents(mask, resizedW, resizedH)
        val boxes = ArrayList<DetBox>()
        for (c in comps) {
            val wPx = c.maxX - c.minX
            val hPx = c.maxY - c.minY
            if (min(wPx, hPx) < DET_MIN_SIZE) continue // sside < 3
            // box_score_fast：外接区 pred 均值
            var sum = 0.0
            var cnt = 0
            for (y in c.minY..c.maxY) {
                val pr = pred[y]
                for (x in c.minX..c.maxX) { sum += pr[x]; cnt++ }
            }
            val score = if (cnt == 0) 0.0 else sum / cnt
            if (score < DET_BOX_THRESH) continue
            // unclip（近似）：distance = area*ratio/perim，矩形外扩
            val area = (wPx.toLong() * hPx.toLong()).toDouble()
            val perim = 2.0 * (wPx + hPx)
            val dist = if (perim <= 0) 0.0 else area * DET_UNCLIP_RATIO / perim
            val ex0 = c.minX - dist
            val ey0 = c.minY - dist
            val ex1 = c.maxX + dist
            val ey1 = c.maxY + dist
            if (min(ex1 - ex0, ey1 - ey0) < DET_MIN_SIZE + 2) continue
            // 回映到原图并裁剪（np.round 近似为四舍五入）
            val x0 = (ex0 / resizedW * origW).roundToInt().coerceIn(0, origW)
            val y0 = (ey0 / resizedH * origH).roundToInt().coerceIn(0, origH)
            val x1 = (ex1 / resizedW * origW).roundToInt().coerceIn(0, origW)
            val y1 = (ey1 / resizedH * origH).roundToInt().coerceIn(0, origH)
            if (abs(x1 - x0) <= 3 || abs(y1 - y0) <= 3) continue // rect_width/height <=3 丢弃
            boxes.add(DetBox(x0, y0, x1, y1, score.toFloat()))
        }
        return boxes
    }

    /** 8-连通域标记（two-pass union-find）。返回每域外接框。 */
    private data class Comp(val minX: Int, val minY: Int, val maxX: Int, val maxY: Int)

    private fun connectedComponents(mask: BooleanArray, w: Int, h: Int): List<Comp> {
        val labels = IntArray(mask.size)
        val parent = ArrayList<Int>(64).apply { add(0) } // index 0 = 背景占位
        fun find(a: Int): Int {
            var x = a
            while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x] }
            return x
        }
        fun union(a: Int, b: Int) {
            val ra = find(a); val rb = find(b)
            if (ra != rb) parent[max(ra, rb)] = min(ra, rb)
        }

        // 前向邻域（8-连通）：左、上、左上、右上
        for (y in 0 until h) {
            for (x in 0 until w) {
                val idx = y * w + x
                if (!mask[idx]) continue
                val left = if (x > 0) labels[idx - 1] else 0
                val up = if (y > 0) labels[idx - w] else 0
                val ul = if (x > 0 && y > 0) labels[idx - w - 1] else 0
                val ur = if (x + 1 < w && y > 0) labels[idx - w + 1] else 0
                val neigh = intArrayOf(left, up, ul, ur).filter { it != 0 }
                if (neigh.isEmpty()) {
                    parent.add(parent.size)
                    labels[idx] = parent.size - 1
                } else {
                    val m = neigh.min()
                    labels[idx] = m
                    neigh.forEach { union(m, it) }
                }
            }
        }
        // 二遍：归并到根，累计外接框
        val minXi = HashMap<Int, Int>()
        val minYi = HashMap<Int, Int>()
        val maxXi = HashMap<Int, Int>()
        val maxYi = HashMap<Int, Int>()
        for (y in 0 until h) {
            for (x in 0 until w) {
                val idx = y * w + x
                val l = labels[idx]
                if (l == 0) continue
                val root = find(l)
                minXi[root] = min(minXi[root] ?: Int.MAX_VALUE, x)
                minYi[root] = min(minYi[root] ?: Int.MAX_VALUE, y)
                maxXi[root] = max(maxXi[root] ?: Int.MIN_VALUE, x)
                maxYi[root] = max(maxYi[root] ?: Int.MIN_VALUE, y)
            }
        }
        return minXi.keys.map { Comp(minXi[it]!!, minYi[it]!!, maxXi[it]!!, maxYi[it]!!) }
    }

    /** sorted_boxes：先按左上 y 稳定排序，dy≥10 分行，行内按 x。抽出供单测。 */
    internal fun sortedBoxes(boxes: List<DetBox>): List<DetBox> {
        if (boxes.isEmpty()) return boxes
        val ySorted = boxes.sortedBy { it.y0 } // stable
        val lineIds = IntArray(ySorted.size)
        var line = 0
        for (i in ySorted.indices) {
            if (i > 0 && ySorted[i].y0 - ySorted[i - 1].y0 >= 10) line++
            lineIds[i] = line
        }
        // 行内按 x 升序，行间按 line 升序 —— lexsort((x, line))
        return ySorted.indices
            .sortedWith(compareBy({ lineIds[it] }, { ySorted[it].x0 }))
            .map { ySorted[it] }
    }

    // ── REC ────────────────────────────────────────────────────────────────

    private fun cropRect(src: Bitmap, b: DetBox): Bitmap? {
        val x = b.x0.coerceIn(0, src.width - 1)
        val y = b.y0.coerceIn(0, src.height - 1)
        val w = (b.x1 - b.x0).coerceAtLeast(1).coerceAtMost(src.width - x)
        val h = (b.y1 - b.y0).coerceAtLeast(1).coerceAtMost(src.height - y)
        if (w < 2 || h < 2) return null
        return try {
            Bitmap.createBitmap(src, x, y, w, h)
        } catch (_: Throwable) {
            null
        }
    }

    /** crop bitmap → (text, score)。resize 到 H=48，按 wh_ratio 定宽，右侧 0 补齐，BGR 归一化，CTC 贪心。 */
    private fun recognizeLine(crop: Bitmap): Pair<String, Float> {
        val e = env!!
        val cropH = crop.height
        val cropW = crop.width
        val ratio = cropW.toDouble() / cropH
        val maxWhRatio = max(REC_IMG_W_REF.toDouble() / REC_IMG_H, ratio)
        val imgW = (REC_IMG_H * maxWhRatio).toInt().coerceAtLeast(4)
        val resizedW = if (ceil(REC_IMG_H * ratio) > imgW) imgW else ceil(REC_IMG_H * ratio).toInt().coerceIn(1, imgW)

        val scaled = Bitmap.createScaledBitmap(crop, resizedW, REC_IMG_H, true)
        val plane = REC_IMG_H * imgW
        val tensor = FloatArray(3 * plane)
        val row = IntArray(resizedW)
        for (y in 0 until REC_IMG_H) {
            scaled.getPixels(row, 0, resizedW, 0, y, resizedW, 1)
            val rowBase = y * imgW
            for (x in 0 until resizedW) {
                val px = row[x]
                val b = (px and 0xFF) / 255.0f
                val g = ((px shr 8) and 0xFF) / 255.0f
                val r = ((px shr 16) and 0xFF) / 255.0f
                val i = rowBase + x
                tensor[i] = (b - 0.5f) / 0.5f
                tensor[plane + i] = (g - 0.5f) / 0.5f
                tensor[2 * plane + i] = (r - 0.5f) / 0.5f
            }
        }
        if (scaled !== crop) scaled.recycle()

        val classes: Array<FloatArray>
        OnnxTensor.createTensor(e, FloatBuffer.wrap(tensor), longArrayOf(1, 3, REC_IMG_H.toLong(), imgW.toLong())).use { input ->
            recSession!!.run(mapOf(recInput to input)).use { res ->
                val o = res.get(recOutput).get().getValue() as Array<Array<FloatArray>> // [batch][T][classes]
                classes = o[0]
            }
        }
        return ctcGreedyDecode(classes, chars)
    }

    /** CTC 贪心解码：逐帧 argmax → 去连续重复 → 去 blank(0)；置信度取被选帧 max 概率均值。抽出供单测。 */
    internal fun ctcGreedyDecode(classes: Array<FloatArray>, charList: List<String>): Pair<String, Float> {
        val sb = StringBuilder()
        var confSum = 0.0
        var confCnt = 0
        var prev = -1
        for (t in classes.indices) {
            val probs = classes[t]
            var best = 0
            var bestV = probs[0]
            for (i in 1 until probs.size) {
                if (probs[i] > bestV) { bestV = probs[i]; best = i }
            }
            if (best != prev && best != 0 && best < charList.size) {
                sb.append(charList[best])
                confSum += bestV
                confCnt++
            }
            prev = best
        }
        val score = if (confCnt == 0) 0f else (confSum / confCnt).toFloat()
        return sb.toString() to score
    }

    // ── 计时小工具（非 inline：需要在闭包里引用 private lateinit chars）──
    private fun time(block: () -> Unit): Long {
        val t = System.currentTimeMillis(); block(); return System.currentTimeMillis() - t
    }

    private fun timeDict(block: () -> Unit): Int {
        block(); return if (::chars.isInitialized) chars.size - 2 else 0
    }
}
