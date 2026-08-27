package com.studykit.data

import android.content.Context
import android.provider.CalendarContract
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 系统日历账户（日历账户显示名与颜色） */
data class CalendarAccount(
    val id: Long,
    val displayName: String,
    val color: Int,
)

/** 系统日历事件（只读展示模型） */
data class SystemEvent(
    val title: String,
    val startMillis: Long,
    val endMillis: Long,
    val calendarName: String,
    val allDay: Boolean,
    val calendarColor: Int = 0xFF007AFF.toInt(),
    val location: String = "",
) {
    /** 事件起始日期（本地时区） */
    val startDate: LocalDate
        get() = Instant.ofEpochMilli(startMillis).atZone(ZoneId.systemDefault()).toLocalDate()
}

/**
 * 系统日历读取器：纯 ContentResolver 封装，只读查询 [CalendarContract]。
 * 需要 READ_CALENDARS 运行时权限；调用方应在授权后再调用。
 */
class SystemCalendarReader(private val context: Context) {

    /**
     * 查询 [from, from + days) 时间窗口内的未删除事件（按开始时间升序）。
     * 限定窗口查询，避免全表扫描。
     */
    fun queryEvents(from: LocalDate, days: Long): List<SystemEvent> {
        val zone = ZoneId.systemDefault()
        val beginMillis = from.atStartOfDay(zone).toInstant().toEpochMilli()
        val endMillis = from.plusDays(days).atStartOfDay(zone).toInstant().toEpochMilli()
        val accounts = queryAccounts()

        val events = mutableListOf<SystemEvent>()
        val projection = arrayOf(
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.CALENDAR_ID,
            CalendarContract.Events.ALL_DAY,
            CalendarContract.Events.EVENT_LOCATION,
        )
        val selection = "${CalendarContract.Events.DELETED} = 0" +
            " AND ${CalendarContract.Events.DTSTART} >= ?" +
            " AND ${CalendarContract.Events.DTSTART} < ?"
        val args = arrayOf(beginMillis.toString(), endMillis.toString())

        context.contentResolver.query(
            CalendarContract.Events.CONTENT_URI,
            projection,
            selection,
            args,
            "${CalendarContract.Events.DTSTART} ASC",
        )?.use { cursor ->
            val titleIdx = cursor.getColumnIndexOrThrow(CalendarContract.Events.TITLE)
            val startIdx = cursor.getColumnIndexOrThrow(CalendarContract.Events.DTSTART)
            val endIdx = cursor.getColumnIndexOrThrow(CalendarContract.Events.DTEND)
            val calIdIdx = cursor.getColumnIndexOrThrow(CalendarContract.Events.CALENDAR_ID)
            val allDayIdx = cursor.getColumnIndexOrThrow(CalendarContract.Events.ALL_DAY)
            val locIdx = cursor.getColumnIndexOrThrow(CalendarContract.Events.EVENT_LOCATION)

            while (cursor.moveToNext()) {
                val start = cursor.getLong(startIdx)
                val account = accounts[cursor.getLong(calIdIdx)]
                events += SystemEvent(
                    title = cursor.getString(titleIdx)?.takeIf { it.isNotBlank() } ?: "（无标题）",
                    startMillis = start,
                    endMillis = if (cursor.isNull(endIdx)) start else maxOf(cursor.getLong(endIdx), start),
                    calendarName = account?.displayName ?: "系统日历",
                    allDay = cursor.getInt(allDayIdx) == 1,
                    calendarColor = account?.color ?: 0xFF007AFF.toInt(),
                    location = cursor.getString(locIdx).orEmpty(),
                )
            }
        }
        return events
    }

    /** 查询全部日历账户的显示名与颜色 */
    private fun queryAccounts(): Map<Long, CalendarAccount> {
        val accounts = mutableMapOf<Long, CalendarAccount>()
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(
                CalendarContract.Calendars._ID,
                CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                CalendarContract.Calendars.CALENDAR_COLOR,
            ),
            null,
            null,
            null,
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(CalendarContract.Calendars._ID)
            val nameIdx = cursor.getColumnIndexOrThrow(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME)
            val colorIdx = cursor.getColumnIndexOrThrow(CalendarContract.Calendars.CALENDAR_COLOR)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idIdx)
                accounts[id] = CalendarAccount(
                    id = id,
                    displayName = cursor.getString(nameIdx)?.takeIf { it.isNotBlank() } ?: "系统日历",
                    color = cursor.getInt(colorIdx),
                )
            }
        }
        return accounts
    }
}
