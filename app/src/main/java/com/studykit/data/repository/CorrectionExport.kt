package com.studykit.data.repository

import com.studykit.data.entity.OcrCorrection
import org.json.JSONArray
import org.json.JSONObject

/**
 * v3.11：纠错记录导出为 JSON（用户主动分享时使用）。
 *
 * 只导出本地已有的 before/after 文本与置信度，不含图片、不含题目 id 以外的信息；
 * 默认不自动上传，由系统分享面板交给用户选择去向。
 */
object CorrectionExport {

    fun toJson(rows: List<OcrCorrection>): String {
        val array = JSONArray()
        rows.forEach { row ->
            array.put(
                JSONObject()
                    .put("questionId", row.questionId)
                    .put("createdAt", row.createdAt)
                    .put("beforeStem", row.beforeStem)
                    .put("afterStem", row.afterStem)
                    .put("beforeAnswer", row.beforeAnswer)
                    .put("afterAnswer", row.afterAnswer)
                    .put("ocrConfidence", row.ocrConfidence.toDouble())
                    .put("sourcePage", row.sourcePage),
            )
        }
        return JSONObject().put("version", 1).put("corrections", array).toString(2)
    }
}
