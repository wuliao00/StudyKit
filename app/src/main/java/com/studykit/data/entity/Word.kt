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
    /**
     * 半衰期（天）—— 这个记忆单元回忆概率掉到 50% 要多久。
     *
     * 调度只看这一个数字（`MemoryModel`），不再看 [status]。
     * 默认 0.5 天 = 新词起步，见 `MemoryState.NEW`。
     */
    @ColumnInfo(name = "half_life_days", defaultValue = "0.5") val halfLifeDays: Double = 0.5,
    /** 难度（≥1，封顶 10）。只涨不跌：一次"模糊/忘记"就 +0.1 */
    @ColumnInfo(name = "difficulty", defaultValue = "1.0") val difficulty: Double = 1.0,
    /** 上次复习的时刻；算本次间隔 Δt 的锚点，null = 还没复习过 */
    @ColumnInfo(name = "last_review_at") val lastReviewAt: Long? = null,
    @ColumnInfo(name = "total_reviews", defaultValue = "0") val totalReviews: Int = 0,
    /** 被忘记的次数（墨墨把这类词叫"顽固"，筛选器直接用它） */
    @ColumnInfo(name = "lapses", defaultValue = "0") val lapses: Int = 0,
    /** 来自哪本在线词库（`word_lists.id`）；手工/粘贴/文件导入为 null，不参与整表删除 */
    @ColumnInfo(name = "source_list_id") val sourceListId: Long? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
) {
    companion object {
        const val STATUS_NEW = "NEW"
        const val STATUS_LEARNING = "LEARNING"
        const val STATUS_MASTERED = "MASTERED"
    }
}
