package com.studykit.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "word_reviews",
    foreignKeys = [
        ForeignKey(
            entity = Word::class,
            parentColumns = ["id"],
            childColumns = ["word_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("word_id")],
)
data class WordReview(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String,
    @ColumnInfo(name = "syncStatus", defaultValue = "0") val syncStatus: Int = 0,
    @ColumnInfo(name = "word_id") val wordId: Long,
    val correct: Boolean,
    @ColumnInfo(name = "reviewed_at") val reviewedAt: Long = System.currentTimeMillis(),
    /**
     * 以下五列是**校准的本钱**：没有"当时排了多久、当时预测多少"，
     * 事后就永远无法知道模型给这个人的这条记忆估得准不准（只能靠猜参数）。
     * 全部可空 —— v4 之前的历史行没有这些，回放时用正向推演补齐。
     */
    /** 本次复习距上次多少天（模型自变量 Δt） */
    @ColumnInfo(name = "gap_days") val gapDays: Double? = null,
    /** 复习那一刻模型预测的回忆概率 p */
    @ColumnInfo(name = "p_at_review") val pAtReview: Double? = null,
    /** 0=认识 1=模糊 2=忘记；-1 = 旧数据未记录（迁移时按 correct 回填） */
    @ColumnInfo(name = "grade", defaultValue = "-1") val grade: Int = GRADE_UNKNOWN,
    @ColumnInfo(name = "h_before") val hBefore: Double? = null,
    @ColumnInfo(name = "h_after") val hAfter: Double? = null,
    /** 从看到词条到按下评分的毫秒数；只作参考，不进模型（自我评分的时长不等于提取时长） */
    @ColumnInfo(name = "reaction_ms") val reactionMs: Long? = null,
) {
    companion object {
        const val GRADE_RECALL = 0
        const val GRADE_VAGUE = 1
        const val GRADE_FORGET = 2
        const val GRADE_UNKNOWN = -1
    }
}
