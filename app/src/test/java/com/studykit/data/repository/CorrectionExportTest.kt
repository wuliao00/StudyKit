package com.studykit.data.repository

import com.studykit.data.entity.OcrCorrection
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** v3.11 纠错导出 JSON：字段齐全、转义安全、空列表也能导出。 */
class CorrectionExportTest {

    @Test
    fun exportsAllFieldsAsJson() {
        val row = OcrCorrection(
            questionId = 88,
            createdAt = 1700000000000,
            beforeStem = "已知函数 f(x)=x^2+1",
            afterStem = "已知函数 f(x)=x^2+1，求 f(2)",
            beforeAnswer = "C",
            afterAnswer = "B",
            ocrConfidence = 0.55f,
            sourcePage = "ocr_pages/a.jpg",
        )
        val json = JSONObject(CorrectionExport.toJson(listOf(row)))
        assertEquals(1, json.getInt("version"))
        val item = json.getJSONArray("corrections").getJSONObject(0)
        assertEquals(88L, item.getLong("questionId"))
        assertEquals(1700000000000L, item.getLong("createdAt"))
        assertEquals("C", item.getString("beforeAnswer"))
        assertEquals("B", item.getString("afterAnswer"))
        assertEquals(0.55, item.getDouble("ocrConfidence"), 1e-6)
        assertTrue(item.getString("beforeStem").contains("x^2"))
    }

    @Test
    fun emptyListExportsEmptyArray() {
        val json = JSONObject(CorrectionExport.toJson(emptyList()))
        assertEquals(0, json.getJSONArray("corrections").length())
    }
}
