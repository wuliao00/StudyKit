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
