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
    /** v2.7（spec §7.2）：书摘入复习队列。next_review_at=0 表示未启用 */
    @ColumnInfo(name = "next_review_at", defaultValue = "0") val nextReviewAt: Long = 0L,
    @ColumnInfo(name = "review_count", defaultValue = "0") val reviewCount: Int = 0,
    @ColumnInfo(name = "stability") val stability: Double? = null,
)
