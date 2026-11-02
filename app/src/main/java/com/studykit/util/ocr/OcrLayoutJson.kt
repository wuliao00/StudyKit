package com.studykit.util.ocr

import org.json.JSONArray
import org.json.JSONObject

/**
 * Question.ocrLayoutJson 的编解码（v3.8）。
 *
 * 只存校对页需要展示/解释的最小集：来源页、页尺寸、疑似公式原文、警告、题号区间。
 * 不存整页 OCR 原文（体积大、含题目内容，没必要再复制一份）。
 */
object OcrLayoutJson {

    const val VERSION = 1

    data class LayoutMeta(
        val version: Int = VERSION,
        val pagePath: String = "",
        val pageWidth: Int = 0,
        val pageHeight: Int = 0,
        val formulaHints: List<String> = emptyList(),
        val warnings: List<String> = emptyList(),
        val sourceLines: List<Int> = emptyList(),
    )

    fun encode(meta: LayoutMeta): String {
        val json = JSONObject()
        json.put("version", meta.version)
        json.put("pagePath", meta.pagePath)
        json.put("pageWidth", meta.pageWidth)
        json.put("pageHeight", meta.pageHeight)
        json.put("formulaHints", JSONArray(meta.formulaHints))
        json.put("warnings", JSONArray(meta.warnings))
        json.put("sourceLines", JSONArray(meta.sourceLines))
        return json.toString()
    }

    fun parse(raw: String): LayoutMeta {
        if (raw.isBlank()) return LayoutMeta()
        return runCatching {
            val json = JSONObject(raw)
            LayoutMeta(
                version = json.optInt("version", VERSION),
                pagePath = json.optString("pagePath", ""),
                pageWidth = json.optInt("pageWidth", 0),
                pageHeight = json.optInt("pageHeight", 0),
                formulaHints = json.optJSONArray("formulaHints").toStringList(),
                warnings = json.optJSONArray("warnings").toStringList(),
                sourceLines = json.optJSONArray("sourceLines").toIntList(),
            )
        }.getOrDefault(LayoutMeta())
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        val out = ArrayList<String>(length())
        for (i in 0 until length()) {
            val v = optString(i, "")
            if (v.isNotEmpty()) out.add(v)
        }
        return out
    }

    private fun JSONArray?.toIntList(): List<Int> {
        if (this == null) return emptyList()
        val out = ArrayList<Int>(length())
        for (i in 0 until length()) {
            val v = optInt(i, Int.MIN_VALUE)
            if (v != Int.MIN_VALUE) out.add(v)
        }
        return out
    }
}
