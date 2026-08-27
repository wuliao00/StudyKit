package com.studykit.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "check_ins",
    foreignKeys = [
        ForeignKey(
            entity = Habit::class,
            parentColumns = ["id"],
            childColumns = ["habit_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["habit_id", "date"], unique = true)],
)
data class CheckIn(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String,
    @ColumnInfo(name = "syncStatus", defaultValue = "0") val syncStatus: Int = 0,
    @ColumnInfo(name = "habit_id") val habitId: Long,
    val date: String,
    /** 打卡备注（可为空；一键打卡时可带入习惯默认文案） */
    @ColumnInfo(name = "note", defaultValue = "''") val note: String = "",
    /** 本次打卡数量（数量型习惯累加用；天数型固定为 1） */
    @ColumnInfo(name = "amount", defaultValue = "1") val amount: Double = 1.0,
)
