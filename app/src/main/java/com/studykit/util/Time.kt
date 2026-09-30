package com.studykit.util

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 时间戳与本地日期换算的统一入口。
 *
 * 只走 `Instant.ofEpochMilli(...).atZone(...).toLocalDate()`：
 * `LocalDate.ofInstant(...)` 需要 API 34，而本项目 minSdk 26，
 * 在 Android 11（API 30）等设备上会直接 NoSuchMethodError。
 * lint 的 NewApi 检查会拦住这类误用，请统一走这里而不是各处自己写。
 */
object Time {

    fun localDate(millis: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()

    fun millisOfDaysAgo(days: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
        localDate(System.currentTimeMillis(), zone).minusDays(days)
            .atStartOfDay(zone).toInstant().toEpochMilli()
}
