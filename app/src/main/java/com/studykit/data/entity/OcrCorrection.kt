package com.studykit.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * v3.9：OCR 校对纠错记录（本地 append-only）。
 *
 * 用户在校对页把 OCR 题干/答案改对时，记一条 before/after，用于后续分析「哪类页面/哪个模型
 * 容易错」；默认只留在本机，不参与备份导出，也不联网。
 *
 * 只存文本与置信度，不存图片；原始来源页仍由 questions.ocr_page_path 指向。
 */
@Entity(
    tableName = "ocr_corrections",
    indices = [Index("question_id"), Index("created_at")],
)
data class OcrCorrection(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "question_id") val questionId: Long,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "before_stem") val beforeStem: String,
    @ColumnInfo(name = "after_stem") val afterStem: String,
    @ColumnInfo(name = "before_answer") val beforeAnswer: String,
    @ColumnInfo(name = "after_answer") val afterAnswer: String,
    @ColumnInfo(name = "ocr_confidence") val ocrConfidence: Float,
    @ColumnInfo(name = "source_page") val sourcePage: String,
)
