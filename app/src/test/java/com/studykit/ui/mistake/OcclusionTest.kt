package com.studykit.ui.mistake

import com.studykit.data.entity.Mistake
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 遮挡块的模型与序列化（v3.0-B T13）。
 *
 * ⛔ 文档 L1890 两条：序列化往返、旧卡 `kind=0` 的迁移默认值。
 * 坐标一律归一化 0..1（文档 L1884）：照片的像素尺寸随设备与压缩而变，
 * 只有比例能在「手机画的」和「平板看的」之间成立。
 */
class OcclusionTest {

    private fun mistake(kind: Int = Mistake.KIND_BASIC, extra: String = "{}") = Mistake(
        uuid = "u", source = Mistake.SOURCE_PHOTO, subject = "数学", title = "t",
        imagePath = "x.jpg", content = "题干", kind = kind, extra = extra,
    )

    @Test
    fun `硬闸 三种形状序列化往返`() {
        val shapes = listOf(
            OcclusionShape.Rect(0.1, 0.2, 0.5, 0.6),
            OcclusionShape.Ellipse(0.5, 0.5, 0.2, 0.1),
            OcclusionShape.Polygon(listOf(NormalizedPoint(0.1, 0.1), NormalizedPoint(0.4, 0.2), NormalizedPoint(0.3, 0.5))),
        )
        assertEquals(shapes, shapesFromJson(shapesToJson(shapes)))
    }

    @Test
    fun `空表写出的是空对象形状而不是空串`() {
        assertEquals("{}", "{}")            // 迁移默认值就是这四个字符，别改
        assertEquals(emptyList<OcclusionShape>(), shapesFromJson("{}"))
        assertEquals(emptyList<OcclusionShape>(), shapesFromJson(""))
        assertEquals(0, shapesFromJson(shapesToJson(emptyList())).size)
    }

    @Test
    fun `脏串整条退回空表不抛`() {
        listOf("not json", "[]", "{\"shapes\":3}", "{\"shapes\":[3]}", "null").forEach {
            assertEquals(it, emptyList<OcclusionShape>(), shapesFromJson(it))
        }
    }

    @Test
    fun `坐标越界夹回 0 到 1`() {
        val one = shapesFromJson("""{"shapes":[{"t":"rect","l":-0.2,"t2":0.1,"r":1.7,"b":0.9}]}""")
        assertEquals(listOf(OcclusionShape.Rect(0.0, 0.1, 1.0, 0.9)), one)
    }

    @Test
    fun `边角反接的矩形自动纠正`() {
        val one = shapesFromJson("""{"shapes":[{"t":"rect","l":0.8,"t2":0.7,"r":0.2,"b":0.3}]}""")
        assertEquals(listOf(OcclusionShape.Rect(0.2, 0.3, 0.8, 0.7)), one)
    }

    @Test
    fun `小于半percent的块丢掉（多半是误触）`() {
        assertTrue(shapesFromJson("""{"shapes":[{"t":"rect","l":0.5,"t2":0.5,"r":0.5001,"b":0.5002}]}""").isEmpty())
    }

    @Test
    fun `椭圆半径负值与零丢，多边形点数不足丢`() {
        assertTrue(shapesFromJson("""{"shapes":[{"t":"ellipse","cx":0.5,"cy":0.5,"rx":0,"ry":0.1}]}""").isEmpty())
        assertTrue(shapesFromJson("""{"shapes":[{"t":"ellipse","cx":0.5,"cy":0.5,"rx":-1,"ry":0.1}]}""").isEmpty())
        assertTrue(shapesFromJson("""{"shapes":[{"t":"poly","pts":[{"x":0.1,"y":0.1}]}]}""").isEmpty())
    }

    @Test
    fun `不认识的类型跳过那条，别把整表丢掉`() {
        val raw = """{"shapes":[{"t":"svg-path","d":"M0 0"},{"t":"rect","l":0.1,"t2":0.1,"r":0.5,"b":0.5}]}"""
        assertEquals(listOf(OcclusionShape.Rect(0.1, 0.1, 0.5, 0.5)), shapesFromJson(raw))
    }

    @Test
    fun `硬闸 旧卡 kind 为 0 读出来没有遮挡`() {
        assertEquals(emptyList<OcclusionShape>(), occlusionShapes(mistake()))
        assertTrue(occlusionShapes(mistake()).isEmpty())
    }

    @Test
    fun `kind 为 0 但 extra 里有形状也不算遮挡（kind 是唯一开关）`() {
        val raw = """{"shapes":[{"t":"rect","l":0.1,"t2":0.1,"r":0.5,"b":0.5}]}"""
        assertTrue(occlusionShapes(mistake(kind = Mistake.KIND_BASIC, extra = raw)).isEmpty())
    }

    @Test
    fun `kind 为 1 且 extra 有形状才算遮挡`() {
        val raw = shapesToJson(listOf(OcclusionShape.Rect(0.0, 0.0, 0.5, 0.5)))
        assertEquals(1, occlusionShapes(mistake(kind = Mistake.KIND_OCCLUSION, extra = raw)).size)
    }

    @Test
    fun `kind 为 1 但 extra 脏：算普通卡而不是崩`() {
        assertTrue(occlusionShapes(mistake(kind = Mistake.KIND_OCCLUSION, extra = "{{{")).isEmpty())
    }
}
