package com.studykit.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 一次「合书回忆」自测。
 *
 * 替代高亮/划线式笔记：读完一章先合上书问自己「作者为什么这么说」，
 * 写下回忆再对照原文。检索练习次数比书摘数量更能代表理解程度。
 */
@Entity(
    tableName = "book_recalls",
    foreignKeys = [
        ForeignKey(
            entity = Book::class,
            parentColumns = ["id"],
            childColumns = ["book_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("book_id"), Index("next_review_at")],
)
data class BookRecall(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String,
    @ColumnInfo(name = "syncStatus", defaultValue = "0") val syncStatus: Int = 0,
    @ColumnInfo(name = "book_id") val bookId: Long,
    @ColumnInfo(name = "page_no") val pageNo: Int? = null,
    /** 自测问题，默认由精加工提问模板生成 */
    val question: String,
    /** 合上书之后自己写下的答案 */
    val answer: String,
    /** 对照原文后的自评：0 没想起来 / 1 部分 / 2 完整 */
    @ColumnInfo(name = "self_score", defaultValue = "0") val selfScore: Int = 0,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    /** 下一次再检索的时刻（与书摘共用间隔调度） */
    @ColumnInfo(name = "next_review_at", defaultValue = "0") val nextReviewAt: Long = 0L,
)
