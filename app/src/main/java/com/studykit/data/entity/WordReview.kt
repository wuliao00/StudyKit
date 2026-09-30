package com.studykit.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "word_reviews",
    foreignKeys = [
        ForeignKey(
            entity = Word::class,
            parentColumns = ["id"],
            childColumns = ["word_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("word_id")],
)
data class WordReview(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String,
    @ColumnInfo(name = "syncStatus", defaultValue = "0") val syncStatus: Int = 0,
    @ColumnInfo(name = "word_id") val wordId: Long,
    val correct: Boolean,
    @ColumnInfo(name = "reviewed_at") val reviewedAt: Long = System.currentTimeMillis(),
    /** FSRS 评分 1..4（Again/Hard/Good/Easy），旧数据 3 表示 Good */
    @ColumnInfo(name = "rating", defaultValue = "3") val rating: Int = 3,
    /** 作答前自评信心 0..2（瞎猜/有点印象/非常确定），-1 表示未评级 */
    @ColumnInfo(name = "confidence", defaultValue = "-1") val confidence: Int = -1,
    /** 该次复习后的稳定性快照，用于画记忆曲线 */
    @ColumnInfo(name = "stability_after", defaultValue = "0") val stabilityAfter: Double = 0.0,
)
