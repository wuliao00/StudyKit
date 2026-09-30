package com.studykit.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "mistakes",
    indices = [Index(value = ["subject", "created_at"])],
)
data class Mistake(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String,
    @ColumnInfo(name = "syncStatus", defaultValue = "0") val syncStatus: Int = 0,
    val source: String,
    val subject: String,
    val title: String,
    @ColumnInfo(name = "image_path") val imagePath: String? = null,
    val content: String,
    val note: String = "",
    @ColumnInfo(name = "review_at") val reviewAt: Long? = null,
    val mastered: Boolean = false,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    /** 错因标签（MistakeMastery.CAUSES 的 label），空表示未归因 */
    @ColumnInfo(name = "cause", defaultValue = "''") val cause: String = "",
    /** 跨间隔连续重做正确次数，达阈才转已掌握 */
    @ColumnInfo(name = "correct_streak", defaultValue = "0") val correctStreak: Int = 0,
    /** 本次与上次重做的间隔天数，同天连对不算跨间隔 */
    @ColumnInfo(name = "last_gap_days", defaultValue = "0") val lastGapDays: Int = 0,
    /** 用户手动钉住复习日后为 1，算法不再覆盖 */
    @ColumnInfo(name = "pinned", defaultValue = "0") val pinned: Boolean = false,
    @ColumnInfo(name = "stability", defaultValue = "0") val stability: Double = 0.0,
    @ColumnInfo(name = "difficulty", defaultValue = "5") val difficulty: Double = 5.0,
    @ColumnInfo(name = "reps", defaultValue = "0") val reps: Int = 0,
    @ColumnInfo(name = "lapses", defaultValue = "0") val lapses: Int = 0,
    @ColumnInfo(name = "last_review_at", defaultValue = "0") val lastReviewAt: Long = 0L,
    /** 高置信答错标记（驱动紧排期与超纠正提示） */
    @ColumnInfo(name = "high_confidence_error", defaultValue = "0") val highConfidenceError: Boolean = false,
) {
    /** 已进入 FSRS 调度的错题才谈得上「算法排期」 */
    val isScheduled: Boolean get() = stability > 0.0

    companion object {
        const val SOURCE_WORD = "word"
        const val SOURCE_PRACTICE = "practice"
        const val SOURCE_PHOTO = "photo"
    }
}
