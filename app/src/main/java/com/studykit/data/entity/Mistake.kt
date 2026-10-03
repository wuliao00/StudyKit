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
    /** 计划复习时刻；自 v2.7 起 null=未手动覆盖、由算法排期 */
    @ColumnInfo(name = "review_at") val reviewAt: Long? = null,
    val mastered: Boolean = false,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    /** v2.7（spec §6）：排期接管——FSRS 三件套 + 计数；review_at 语义改为"手动覆盖，可空" */
    @ColumnInfo(name = "fsrs_stability") val fsrsStability: Double? = null,
    @ColumnInfo(name = "fsrs_difficulty") val fsrsDifficulty: Double? = null,
    @ColumnInfo(name = "fsrs_state", defaultValue = "1") val fsrsState: Int = 1,
    /** 最近连续判对次数（自动掌握判定的持久侧，见 ui/mistake/MistakeMastery） */
    @ColumnInfo(name = "correct_streak", defaultValue = "0") val correctStreak: Int = 0,
    @ColumnInfo(name = "review_count", defaultValue = "0") val reviewCount: Int = 0,
    /** 1=高置信错题（超纠正置顶，spec §2.3）；0=普通 */
    @ColumnInfo(name = "priority", defaultValue = "0") val priority: Int = 0,
) {
    companion object {
        const val SOURCE_WORD = "word"
        const val SOURCE_PRACTICE = "practice"
        const val SOURCE_PHOTO = "photo"
    }
}
