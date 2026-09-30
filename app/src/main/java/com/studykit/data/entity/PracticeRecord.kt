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
    /** 作答前自评信心 0..2（瞎猜/有点印象/非常确定），-1 未评级 */
    @ColumnInfo(name = "confidence", defaultValue = "-1") val confidence: Int = -1,
    /** 本次用到了几档提示（挤牙膏深度），0 表示直接作答 */
    @ColumnInfo(name = "hint_level", defaultValue = "0") val hintLevel: Int = 0,
)
