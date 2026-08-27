package com.studykit.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "mistakes",
    indices = [Index(value = ["subject", "created_at"])],
)
data class Mistake(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String,
    @ColumnInfo(name = "syncStatus", defaultValue = "0") val syncStatus: Int = 0,
    val source: String,
    val subject: String,
    val title: String,
    @ColumnInfo(name = "image_path") val imagePath: String? = null,
    val content: String,
    val note: String = "",
    @ColumnInfo(name = "review_at") val reviewAt: Long? = null,
    val mastered: Boolean = false,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
) {
    companion object {
        const val SOURCE_WORD = "word"
        const val SOURCE_PRACTICE = "practice"
        const val SOURCE_PHOTO = "photo"
    }
}
