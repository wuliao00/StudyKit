package com.studykit.ui.mistake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 拖拽 -> 遮挡块的换算与建卡落库那一对列（v3.0-B T16）。
 *
 * 换算单独钉：它是「用户的手指轨迹」与「落库的那份 JSON」之间唯一的算术，
 * 出错的方式是静默的（块画歪了半格、或误触留了一个 0 尺寸的块），事后从库里看不出区别。
 * 手指像素坐标先由 composable 除以视图宽高归一化，这里只吃 0..1（纯函数never见像素）。
 *
 * 后半组钉的是 `kind` 与 `extra` 必须**成对**决定：`kind` 是 T13 那条唯一的开关，
 * 写了块却留 `kind=0` 的卡会静默没有遮挡（页面上那张照片一格都不盖，用户以为盖住了）。
 * 这里的期望一律写字面量 0 / 1，不写 `Mistake.KIND_*` —— 那两个常量的值本身就在被测。
 */
class OcclusionEditorTest {

    @Test
    fun `正向拖拽出矩形`() {
        assertEquals(OcclusionShape.Rect(0.1, 0.2, 0.5, 0.6), dragToShape(0.1, 0.2, 0.5, 0.6, EditorShape.RECT))
    }

    @Test
    fun `反向拖拽自动纠正边角`() {
        assertEquals(OcclusionShape.Rect(0.2, 0.3, 0.8, 0.7), dragToShape(0.8, 0.7, 0.2, 0.3, EditorShape.RECT))
    }

    @Test
    fun `越界坐标夹回图内`() {
        val s = dragToShape(-0.2, 1.4, 1.9, 0.5, EditorShape.RECT) as OcclusionShape.Rect
        assertEquals(0.0, s.left, 1e-9)
        assertEquals(1.0, s.right, 1e-9)
        assertEquals(0.5, s.top, 1e-9)     // top/bottom 互换后仍夹在 0..1
        assertEquals(1.0, s.bottom, 1e-9)
    }

    @Test
    fun `硬闸 手指没挪动的误触不成块`() {
        assertNull(dragToShape(0.3, 0.3, 0.3, 0.3, EditorShape.RECT))
        assertNull(dragToShape(0.3, 0.3, 0.301, 0.301, EditorShape.RECT))
    }

    @Test
    fun `椭圆由拖拽包围盒导出`() {
        val s = dragToShape(0.2, 0.4, 0.6, 0.8, EditorShape.ELLIPSE) as OcclusionShape.Ellipse
        assertEquals(0.4, s.centerX, 1e-9)
        assertEquals(0.6, s.centerY, 1e-9)
        assertEquals(0.2, s.radiusX, 1e-9)
        assertEquals(0.2, s.radiusY, 1e-9)
    }

    @Test
    fun `扁到只有横条的拖拽给不了椭圆`() {
        assertNull(dragToShape(0.1, 0.5, 0.9, 0.501, EditorShape.ELLIPSE))
    }

    @Test
    fun `画出来的块能原样进 JSON 再回来`() {
        val s = dragToShape(0.1, 0.1, 0.4, 0.5, EditorShape.RECT)!!
        assertEquals(listOf(s), shapesFromJson(shapesToJson(listOf(s))))
        assertTrue(shapesToJson(listOf(s)).contains("\"shapes\""))
    }

    // ── 建卡流的那两句文案与两个态 ────────────────────────────────────────

    @Test
    fun `遮挡建卡那句说明照文档原文`() {
        assertEquals("拖拽添加遮挡；复习时先回忆、点按再揭。", imageEditorHint())
    }

    @Test
    fun `块数那句说法两种各一句`() {
        assertEquals("还没有遮挡块", editorShapeCountLabel(0))
        assertEquals("已加 1 处遮挡", editorShapeCountLabel(1))
        assertEquals("已加 3 处遮挡", editorShapeCountLabel(3))
    }

    @Test
    fun `存档里的工具名读不懂就回到矩形`() {
        // 与 decodeRedoPhase 同一条纪律：进程重建后读到认不出的串就回默认档，
        // 别拿"最后一个认识的档"猜——猜错的人是无辜的下一次建卡。
        assertEquals(EditorShape.RECT, decodeEditorShape("RECT"))
        assertEquals(EditorShape.ELLIPSE, decodeEditorShape("ELLIPSE"))
        assertEquals(EditorShape.RECT, decodeEditorShape(null))
        assertEquals(EditorShape.RECT, decodeEditorShape(""))
        assertEquals(EditorShape.RECT, decodeEditorShape("rect"))
        assertEquals(EditorShape.RECT, decodeEditorShape("POLYGON"))
    }

    // ── ⛔ kind 与 extra 成对决定 ─────────────────────────────────────────

    @Test
    fun `硬闸 画了块才同时写 kind 与 extra`() {
        val drawn = shapesToJson(listOf(OcclusionShape.Rect(0.1, 0.1, 0.4, 0.5)))
        val hit = occlusionColumns(drawn)
        assertEquals(1, hit.kind)
        assertEquals(drawn, hit.extra)
    }

    @Test
    fun `硬闸 没画块的卡留 kind 零与空对象`() {
        val empty = occlusionColumns("{}")
        assertEquals(0, empty.kind)
        assertEquals("{}", empty.extra)

        val emptyList = occlusionColumns(shapesToJson(emptyList()))
        assertEquals(0, emptyList.kind)
        assertEquals("{}", emptyList.extra)
    }

    @Test
    fun `硬闸 脏串退化成普通卡而不是空遮挡卡`() {
        // 留一个 kind=1、extra 读不出块的行是最坏结果：页面按 kind 承认遮挡、
        // 读到零块，于是照片一格都不盖，还附赠一句"已遮 0 处"。
        val junk = occlusionColumns("{{{")
        assertEquals(0, junk.kind)
        assertEquals("{}", junk.extra)
    }

    @Test
    fun `落库那份是重新编码的，不是原样转手`() {
        // 手写的脏 JSON（键名对但边角反接、越界）经读侧修正后再写回去，
        // 库里留下的就是读侧本来就会读成的那份，不留两份真值。
        val dirty = """{"shapes":[{"t":"rect","l":0.8,"t2":0.7,"r":0.2,"b":0.3}]}"""
        val columns = occlusionColumns(dirty)
        assertEquals(1, columns.kind)
        assertEquals(listOf(OcclusionShape.Rect(0.2, 0.3, 0.8, 0.7)), shapesFromJson(columns.extra))
        assertEquals(columns.extra, shapesToJson(shapesFromJson(columns.extra)))
    }
}
