package com.studykit.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "words")
data class Word(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String,
    @ColumnInfo(name = "syncStatus", defaultValue = "0") val syncStatus: Int = 0,
    val word: String,
    val meaning: String,
    val example: String,
    val status: String = STATUS_NEW,
    @ColumnInfo(name = "next_review_at") val nextReviewAt: Long = 0L,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    /** FSRS 稳定性（天）：保留率降到 90% 所需天数，0 表示尚未进入调度 */
    @ColumnInfo(name = "stability", defaultValue = "0") val stability: Double = 0.0,
    /** FSRS 难度 1..10 */
    @ColumnInfo(name = "difficulty", defaultValue = "5") val difficulty: Double = 5.0,
    /** 累计复习次数（reps=0 即新词） */
    @ColumnInfo(name = "reps", defaultValue = "0") val reps: Int = 0,
    /** 累计遗忘（Again）次数 */
    @ColumnInfo(name = "lapses", defaultValue = "0") val lapses: Int = 0,
    /** 上一次复习时刻，用于计算当前预测保留率 */
    @ColumnInfo(name = "last_review_at", defaultValue = "0") val lastReviewAt: Long = 0L,
    /** 最近一次作答前的自评信心（Confidence.ordinal），用于元认知校准展示 */
    @ColumnInfo(name = "last_confidence", defaultValue = "-1") val lastConfidence: Int = -1,
) {
    /** 是否已进入 FSRS 调度（旧数据只有 next_review_at） */
    val isScheduled: Boolean get() = stability > 0.0

    companion object {
        const val STATUS_NEW = "NEW"
        const val STATUS_LEARNING = "LEARNING"
        const val STATUS_MASTERED = "MASTERED"

        /** 稳定性达到该天数即视为长时掌握（约三周，仅作展示分类用） */
        const val MASTER_STABILITY_DAYS = 21.0

        /** 由 FSRS 状态推导展示用的三档状态，取代手写状态机 */
        fun statusFor(reps: Int, stability: Double): String = when {
            reps <= 0 -> STATUS_NEW
            stability >= MASTER_STABILITY_DAYS -> STATUS_MASTERED
            else -> STATUS_LEARNING
        }
    }
}
