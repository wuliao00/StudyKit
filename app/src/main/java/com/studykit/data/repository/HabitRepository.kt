package com.studykit.data.repository

import com.studykit.data.dao.HabitDao
import com.studykit.data.entity.CheckIn
import com.studykit.data.entity.Habit
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID

class HabitRepository(private val habitDao: HabitDao) {

    private val dateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    fun observeAll(): Flow<List<Habit>> = habitDao.observeAll()

    suspend fun getAll(): List<Habit> = habitDao.getAll()

    suspend fun add(
        name: String,
        icon: String,
        targetDays: Int = 21,
        targetCount: Double = 0.0,
        unit: String = "",
        defaultText: String = "",
    ): Long =
        habitDao.insert(
            Habit(
                uuid = UUID.randomUUID().toString(),
                name = name,
                icon = icon,
                targetDays = targetDays,
                targetCount = targetCount,
                unit = unit,
                defaultText = defaultText,
            ),
        )

    suspend fun archive(id: Long) = habitDao.archive(id)

    fun observeCheckIns(habitId: Long): Flow<List<CheckIn>> = habitDao.observeCheckIns(habitId)

    /** 全部打卡记录（全局日历按日叠加用） */
    fun observeAllCheckIns(): Flow<List<CheckIn>> = habitDao.observeAllCheckIns()

    /** 全部打卡记录（导出 CSV 用） */
    suspend fun getAllCheckIns(): List<CheckIn> = habitDao.getAllCheckIns()

    fun observeCheckInCount(habitId: Long): Flow<Int> = habitDao.observeCheckInCount(habitId)

    suspend fun findCheckInOn(habitId: Long, date: LocalDate): CheckIn? =
        habitDao.findCheckInOn(habitId, date.format(dateFmt))

    /**
     * 在指定日期打卡（支持补卡，日期校验由调用方控制）：
     * - 当日无记录：新增（amount<=0 时记 1，即天数型）
     * - 当日已有记录：数量型累加 amount；备注非空时覆盖（天数型重复打卡不会重复计数）
     */
    suspend fun checkInOn(habitId: Long, date: LocalDate, note: String, amount: Double): Long {
        val dateStr = date.format(dateFmt)
        val existing = habitDao.findCheckInOn(habitId, dateStr)
        return if (existing == null) {
            habitDao.insertCheckIn(
                CheckIn(
                    uuid = UUID.randomUUID().toString(),
                    habitId = habitId,
                    date = dateStr,
                    note = note.trim(),
                    amount = if (amount > 0) amount else 1.0,
                ),
            )
        } else {
            habitDao.updateCheckIn(
                existing.copy(
                    amount = if (amount > 0) existing.amount + amount else existing.amount,
                    note = note.trim().ifBlank { existing.note },
                ),
            )
            existing.id
        }
    }

    /** 对某习惯在今日打卡（幂等，重复打卡不会产生多条记录） */
    suspend fun checkInToday(habitId: Long, note: String = ""): Long =
        checkInOn(habitId, LocalDate.now(), note, 0.0)

    suspend fun isCheckedInToday(habitId: Long): Boolean {
        val today = LocalDate.now().format(dateFmt)
        return habitDao.countCheckInOn(habitId, today) > 0
    }
}
