package com.studykit.domain.design

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `fields_json` 编解码的往返与兜底。
 *
 * 这两条要求来自真实场景：用户会在字段里敲中文、换行、引号；也会遇到旧版本写下的
 * JSON 或手改坏的一行。前者必须原样回来，后者必须安静地变成空表 —— 编辑页不能因为
 * 一条脏数据就打不开。
 */
class DesignPlanCodecTest {

    @Test
    fun encodeDecodeRoundTripKeepsChineseNewlinesAndQuotes() {
        val fields = mapOf(
            "concept" to "机会成本",
            "explanation" to "第一行：你选了 A\n第二行：就放弃了 B",
            "gaps" to "带\"引号\"与反斜杠 \\ 的文本",
            "selfTest" to "问题一？\n问题二？",
        )

        val json = DesignPlanCodec.encode(fields)
        assertEquals("往返必须一字不差", fields, DesignPlanCodec.decode(json))
    }

    @Test
    fun encodeIsStableAcrossMapIterationOrder() {
        val first = linkedMapOf("b" to "2", "a" to "1", "c" to "3")
        val second = linkedMapOf("c" to "3", "b" to "2", "a" to "1")

        assertEquals("键排序稳定：同一份表单必须得到同一串 JSON", DesignPlanCodec.encode(first), DesignPlanCodec.encode(second))
        assertEquals("""{"a":"1","b":"2","c":"3"}""", DesignPlanCodec.encode(first))
    }

    @Test
    fun encodeEmptyMapIsEmptyObject() {
        assertEquals("{}", DesignPlanCodec.encode(emptyMap()))
        assertTrue(DesignPlanCodec.decode("{}").isEmpty())
    }

    @Test
    fun decodeReturnsEmptyMapForBrokenJson() {
        val broken = listOf(
            "not json at all",
            "{",
            "{\"a\":}",
            "{\"a\":\"b\"",
            "[]",
            "null",
            "\u0000",
        )
        broken.forEach { raw ->
            assertTrue("坏 JSON 必须返回空 map 且不抛：$raw", DesignPlanCodec.decode(raw).isEmpty())
        }
    }

    @Test
    fun decodeTreatsNullBlankAndJsonNullAsEmpty() {
        assertTrue(DesignPlanCodec.decode(null).isEmpty())
        assertTrue(DesignPlanCodec.decode("").isEmpty())
        assertTrue(DesignPlanCodec.decode("   ").isEmpty())
        assertEquals(mapOf("a" to ""), DesignPlanCodec.decode("{\"a\":null}"))
    }

    @Test
    fun decodeSortsKeys() {
        val decoded = DesignPlanCodec.decode("""{"z":"1","a":"2","m":"3"}""")
        assertEquals(listOf("a", "m", "z"), decoded.keys.toList())
    }

    @Test
    fun isEmptySeesWhitespaceOnlyAsEmpty() {
        assertTrue(DesignPlanCodec.isEmpty(emptyMap()))
        assertTrue(DesignPlanCodec.isEmpty(mapOf("a" to "", "b" to "   ", "c" to "\n\t ")))
        assertFalse(DesignPlanCodec.isEmpty(mapOf("a" to "", "b" to "一句话")))
    }
}
