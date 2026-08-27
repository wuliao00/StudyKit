package com.studykit.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "books")
data class Book(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String,
    @ColumnInfo(name = "syncStatus", defaultValue = "0") val syncStatus: Int = 0,
    val title: String,
    val author: String,
    @ColumnInfo(name = "total_pages") val totalPages: Int,
    @ColumnInfo(name = "current_page") val currentPage: Int = 0,
    val status: String = STATUS_READING,
    @ColumnInfo(name = "started_at") val startedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "finished_at") val finishedAt: Long? = null,
) {
    companion object {
        const val STATUS_READING = "reading"
        const val STATUS_FINISHED = "finished"
    }
}
