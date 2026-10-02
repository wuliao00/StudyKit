package com.studykit.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** 章节过关小测的一道题及其结果（spec §3.2） */
@Entity(
    tableName = "chapter_tests",
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
data class ChapterTest(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String,
    @ColumnInfo(name = "syncStatus", defaultValue = "0") val syncStatus: Int = 0,
    @ColumnInfo(name = "book_id") val bookId: Long,
    @ColumnInfo(name = "chapter_label") val chapterLabel: String,
    val question: String,
    @ColumnInfo(name = "expected_answer") val expectedAnswer: String,
    val passed: Boolean,
    @ColumnInfo(name = "tested_at") val testedAt: Long = System.currentTimeMillis(),
)
