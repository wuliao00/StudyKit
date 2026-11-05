package com.studykit.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 每日签到的一行：一天最多一行，day 用本地日期 ISO 字符串（yyyy-MM-dd）做主键。
 *
 * 只存“签过没”，不存奖励/积分；首页的连击、日历格、累计天数都由 [com.studykit.util.signin.SignInStats] 现算。
 */
@Entity(tableName = "daily_sign_ins")
data class DailySignIn(
    @PrimaryKey @ColumnInfo(name = "day") val day: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)
