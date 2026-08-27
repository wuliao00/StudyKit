package com.studykit.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "habits")
data class Habit(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String,
    @ColumnInfo(name = "syncStatus", defaultValue = "0") val syncStatus: Int = 0,
    val name: String,
    val icon: String,
    @ColumnInfo(name = "target_days") val targetDays: Int = 21,
    @ColumnInfo(name = "start_date") val startDate: Long = System.currentTimeMillis(),
    val archived: Boolean = false,
    /** 数量型目标总量（>0 即数量型习惯，如背 50 词；0 表示天数型） */
    @ColumnInfo(name = "target_count", defaultValue = "0") val targetCount: Double = 0.0,
    /** 数量型单位（如「个」「公里」「ml」） */
    @ColumnInfo(name = "unit", defaultValue = "''") val unit: String = "",
    /** 默认打卡文案：一键打卡时自动带入打卡备注 */
    @ColumnInfo(name = "default_text", defaultValue = "''") val defaultText: String = "",
)
