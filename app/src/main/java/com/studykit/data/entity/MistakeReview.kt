package com.studykit.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 一次错题重做的记录。
 *
 * 错题的复习方式是「先遮着解析重做」（检索练习），而不是「再看一遍解析」；
 * 这些记录用于计算跨间隔连续答对、反馈分层与超纠正排期。
 */
@Entity(
    tableName = "mistake_reviews",
    foreignKeys = [
        ForeignKey(
            entity = Mistake::class,
            parentColumns = ["id"],
            childColumns = ["mistake_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("mistake_id"), Index("reviewed_at")],
)
data class MistakeReview(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String,
    @ColumnInfo(name = "syncStatus", defaultValue = "0") val syncStatus: Int = 0,
    @ColumnInfo(name = "mistake_id") val mistakeId: Long,
    val correct: Boolean,
    /** 重做前的自评信心 0..2，-1 表示未评级 */
    @ColumnInfo(name = "confidence", defaultValue = "-1") val confidence: Int = -1,
    /** 用了几档提示（0=直接做，3=已经看到方法方向） */
    @ColumnInfo(name = "hint_level", defaultValue = "0") val hintLevel: Int = 0,
    /** 与上次重做相隔天数，同天为 0（同天连对不算跨间隔掌握） */
    @ColumnInfo(name = "gap_days", defaultValue = "0") val gapDays: Int = 0,
    @ColumnInfo(name = "reviewed_at") val reviewedAt: Long = System.currentTimeMillis(),
)
