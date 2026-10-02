package com.studykit.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** 一次错题重做的完整轨迹（spec §3.2；RedoFlow 注释自认缺的那张表） */
@Entity(
    tableName = "mistake_redos",
    foreignKeys = [
        ForeignKey(
            entity = Mistake::class,
            parentColumns = ["id"],
            childColumns = ["mistake_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("mistake_id")],
)
data class MistakeRedo(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String,
    @ColumnInfo(name = "syncStatus", defaultValue = "0") val syncStatus: Int = 0,
    @ColumnInfo(name = "mistake_id") val mistakeId: Long,
    @ColumnInfo(name = "redone_at") val redoneAt: Long = System.currentTimeMillis(),
    val correct: Boolean,
    @ColumnInfo(name = "hints_used") val hintsUsed: Int = 0,
    @ColumnInfo(name = "had_note_rebuild") val hadNoteRebuild: Boolean = false,
)
