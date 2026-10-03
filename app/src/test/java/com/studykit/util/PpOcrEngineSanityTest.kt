package com.studykit.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PP-OCRv6 引擎的**纯逻辑 sanity**——只在 JVM 上跑固定向量，不碰 ONNX 原生库、不上真机。
 *
 * 目的：DB 框解码（[PpOcrOnnxEngine.dbPostProcess]）、分行排序（[PpOcrOnnxEngine.sortedBoxes]）、
 * CTC 贪心（[PpOcrOnnxEngine.ctcGreedyDecode]）这三处正确性，red/green 一目了然。它们都是无副作用
 * 的纯函数：只吃数组/列表，不调 `env`/`OrtEnvironment`，所以 object 初始化不会触发原生库加载，
 * JVM 单测能直接跑。（给定真实图片端到端识别出文字那一条，onnxruntime 的 .so 只在设备上有，
 * 归到真机阶段验证，不在这里伪造。）
 */
class PpOcrEngineSanityTest {

    // ── CTC 贪心 ──
    @Test
    fun ctcGreedy_collapsesRepeatsAndDropsBlank() {
        val chars = listOf("blank", "a", "b", "c", " ") // idx0=blank, 1..3=abc, 4=space
        // 每帧 one-hot：a a blank a b c c
        fun oh(i: Int) = FloatArray(chars.size).also { it[i] = 0.9f }
        val frames = arrayOf(oh(1), oh(1), oh(0), oh(1), oh(2), oh(3), oh(3))
        val (text, score) = PpOcrOnnxEngine.ctcGreedyDecode(frames, chars)
        // prev 跟踪"上一帧的 best 索引"，blank 帧 best=0，随后 a 帧 best=1≠0 会被保留 → "aabc"
        assertEquals("aabc", text)
        assertTrue(score > 0f)
    }

    @Test
    fun ctcGreedy_spaceIsRealChar() {
        val chars = listOf("blank", "a", "b", " ")
        fun oh(i: Int) = FloatArray(chars.size).also { it[i] = 0.8f }
        val frames = arrayOf(oh(1), oh(3), oh(2)) // a, space, b
        val (text, _) = PpOcrOnnxEngine.ctcGreedyDecode(frames, chars)
        assertEquals("a b", text)
    }

    @Test
    fun ctcGreedy_allBlankYieldsEmptyAndZero() {
        val chars = listOf("blank", "a", "b")
        fun oh(i: Int) = FloatArray(chars.size).also { it[i] = 0.95f }
        val frames = arrayOf(oh(0), oh(0), oh(0)) // 全 blank
        val (text, score) = PpOcrOnnxEngine.ctcGreedyDecode(frames, chars)
        assertEquals("", text)
        assertEquals(0f, score, 1e-6f)
    }

    // ── sorted_boxes ──
    @Test
    fun sortedBoxes_groupsLinesByTenPxAndOrdersByX() {
        val b1 = PpOcrOnnxEngine.DetBox(50, 0, 60, 8, 1f)  // 行1 右
        val b2 = PpOcrOnnxEngine.DetBox(0, 2, 10, 10, 1f)  // 行1 左
        val b3 = PpOcrOnnxEngine.DetBox(5, 40, 15, 48, 1f) // 行2
        val out = PpOcrOnnxEngine.sortedBoxes(listOf(b1, b2, b3))
        // 行内 x 升序：b2(0)→b1(50)，再换行 b3(y 差 40-2=38≥10)
        assertEquals(listOf("b2", "b1", "b3"), out.map { box ->
            when (box) {
                b2 -> "b2"; b1 -> "b1"; else -> "b3"
            }
        })
    }

    // ── DB 解码 ──
    @Test
    fun dbPostProcess_findsOneBoxForSolidBlock() {
        val w = 40
        val h = 20
        val pred = Array(h) { FloatArray(w) { 0.05f } }
        // 一块实心文本：x[8..16] y[5..10] 概率 0.9
        for (y in 5..10) for (x in 8..16) pred[y][x] = 0.9f
        val boxes = PpOcrOnnxEngine.dbPostProcess(pred, w, h, w, h)
        assertEquals(1, boxes.size)
        val b = boxes[0]
        // unclip 外扩后应把原块往四周撑开一点
        assertTrue("left should extend left of 8: ${b.x0}", b.x0 < 8)
        assertTrue("right should extend right of 16: ${b.x1}", b.x1 > 16)
        assertTrue("top should extend above 5: ${b.y0}", b.y0 < 5)
    }

    @Test
    fun dbPostProcess_ignoresWeakBlobBelowBoxThresh() {
        val w = 40
        val h = 20
        val pred = Array(h) { FloatArray(w) { 0.0f } }
        // 边缘概率 0.32：>thresh(0.3) 会成连通域，但外接区均值远低于 box_thresh(0.5)
        for (y in 5..10) for (x in 8..16) pred[y][x] = 0.32f
        val boxes = PpOcrOnnxEngine.dbPostProcess(pred, w, h, w, h)
        assertTrue("weak blob must be filtered out", boxes.isEmpty())
    }

    // ── det 输入尺寸归一：limit_type=min 736 + 32 倍数 + MAX_DET_SIDE 1600 闸门 ──
    @Test
    fun detResizeDims_upscalesSmallImageToMinSide736() {
        // 400×300：长边 400<1600 不触发闸门；min 边 300<736 → 放大到 736，再取 32 倍数
        val (rw, rh) = PpOcrOnnxEngine.detResizeDims(400, 300)
        assertEquals(0, rw % 32)
        assertEquals(0, rh % 32)
        // 放大后短边应被抬到 ≥736 附近（取 32 倍数后不低于 736）
        assertTrue("min side should reach >=736: $rw x $rh", minOf(rw, rh) >= 736)
    }

    @Test
    fun detResizeDims_capsHugeImageByMemoryGate() {
        // 长边 6000 > MAX_DET_SIDE(1600)：先整体缩到 1600，再按 32 倍数取整
        val (rw, rh) = PpOcrOnnxEngine.detResizeDims(6000, 3000)
        assertTrue("long side should be capped near 1600: $rw x $rh", maxOf(rw, rh) <= 1600 + 31)
        assertEquals(0, maxOf(rw, rh) % 32)
    }
}
