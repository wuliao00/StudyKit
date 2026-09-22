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
    /**
     * 时段分类（v2.4 批次四）：ANY/MORNING/FORENOON/NOON/AFTERNOON/EVENING/NIGHT。
     * 依据 Gardner 2021 —— 绑例程（"睡前"）比绑钟点（"22:30"）更易成习惯，
     * 所以分类是"一天的哪一段"而不是提醒时刻。
     */
    @ColumnInfo(name = "category", defaultValue = "'ANY'") val category: String = CATEGORY_ANY,
    /** 手动排序（长按拖动）；小值在前 */
    @ColumnInfo(name = "sort_order", defaultValue = "0") val sortOrder: Int = 0,
) {
    companion object {
        const val CATEGORY_ANY = "ANY"
        val CATEGORIES = listOf(CATEGORY_ANY, "MORNING", "FORENOON", "NOON", "AFTERNOON", "EVENING", "NIGHT")
    }
}
