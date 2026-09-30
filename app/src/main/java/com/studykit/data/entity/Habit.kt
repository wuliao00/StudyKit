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
    @ColumnInfo(name = "target_days") val targetDays: Int = 66,
    @ColumnInfo(name = "start_date") val startDate: Long = System.currentTimeMillis(),
    val archived: Boolean = false,
    /** 数量型目标总量（>0 即数量型习惯，如背 50 词；0 表示天数型） */
    @ColumnInfo(name = "target_count", defaultValue = "0") val targetCount: Double = 0.0,
    /** 数量型单位（如「个」「公里」「ml」） */
    @ColumnInfo(name = "unit", defaultValue = "''") val unit: String = "",
    /** 默认打卡文案：一键打卡时自动带入打卡备注 */
    @ColumnInfo(name = "default_text", defaultValue = "''") val defaultText: String = "",
    /** 执行意图的时间线索（何时），与 cue_place 一起构成 if-then 计划 */
    @ColumnInfo(name = "cue_time", defaultValue = "''") val cueTime: String = "",
    /** 执行意图的地点线索（何地） */
    @ColumnInfo(name = "cue_place", defaultValue = "''") val cuePlace: String = "",
    /** 一周达标天数（弹性连续），默认 5/7 */
    @ColumnInfo(name = "weekly_target_days", defaultValue = "5") val weeklyTargetDays: Int = 5,
    /** 本月可用的断签保护卡张数 */
    @ColumnInfo(name = "protection_cards", defaultValue = "2") val protectionCards: Int = 2,
    /** 本月已消耗的保护卡张数 */
    @ColumnInfo(name = "protection_used", defaultValue = "0") val protectionUsed: Int = 0,
    /** 额度所属月份（yyyy-MM）；跨月时保护卡回满 */
    @ColumnInfo(name = "protection_period", defaultValue = "''") val protectionPeriod: String = "",
) {
    /** 本月剩余保护卡（不判月份，需跨月回血请用 HabitScience.refreshQuota） */
    val cardsLeft: Int get() = (protectionCards - protectionUsed).coerceAtLeast(0)
}
