package com.studykit.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "excerpts",
    foreignKeys = [
        ForeignKey(
            entity = Book::class,
            parentColumns = ["id"],
            childColumns = ["book_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("book_id")],
)
data class Excerpt(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String,
    @ColumnInfo(name = "syncStatus", defaultValue = "0") val syncStatus: Int = 0,
    @ColumnInfo(name = "book_id") val bookId: Long,
    val content: String,
    @ColumnInfo(name = "page_no") val pageNo: Int? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    /** 书摘进入间隔复习队列的时刻，0 表示尚未入队 */
    @ColumnInfo(name = "next_review_at", defaultValue = "0") val nextReviewAt: Long = 0L,
    /** 被合书回忆/自测过的次数，取代替「书摘总数」的展示指标 */
    @ColumnInfo(name = "recall_count", defaultValue = "0") val recallCount: Int = 0,
    @ColumnInfo(name = "stability", defaultValue = "0") val stability: Double = 0.0,
    @ColumnInfo(name = "difficulty", defaultValue = "5") val difficulty: Double = 5.0,
    @ColumnInfo(name = "reps", defaultValue = "0") val reps: Int = 0,
    @ColumnInfo(name = "lapses", defaultValue = "0") val lapses: Int = 0,
    @ColumnInfo(name = "last_review_at", defaultValue = "0") val lastReviewAt: Long = 0L,
)
