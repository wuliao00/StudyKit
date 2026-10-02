package com.studykit.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "practice_records",
    foreignKeys = [
        ForeignKey(
            entity = Question::class,
            parentColumns = ["id"],
            childColumns = ["question_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("question_id"), Index("at")],
)
data class PracticeRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String,
    @ColumnInfo(name = "syncStatus", defaultValue = "0") val syncStatus: Int = 0,
    @ColumnInfo(name = "question_id") val questionId: Long,
    val selected: Int,
    val correct: Boolean,
    val at: Long = System.currentTimeMillis(),
    /** v2.7：作答前信心 1=瞎猜 2=有点印象 3=非常确定；null=跳过（spec §2.3） */
    @ColumnInfo(name = "confidence") val confidence: Int? = null,
)
