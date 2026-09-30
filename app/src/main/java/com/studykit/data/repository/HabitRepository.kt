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
        targetDays: Int = 66,
        targetCount: Double = 0.0,
        unit: String = "",
        defaultText: String = "",
        cueTime: String = "",
        cuePlace: String = "",
        protectionPeriod: String = "",
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
                cueTime = cueTime,
                cuePlace = cuePlace,
                protectionPeriod = protectionPeriod,
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
     * - 当日无记录：新增（amount<=0 时记 1，即天数型）；isMakeup 标记过往日期回补
     * - 当日已有记录：数量型累加 amount；备注非空时覆盖（天数型重复打卡不会重复计数）
     */
    suspend fun checkInOn(
        habitId: Long,
        date: LocalDate,
        note: String,
        amount: Double,
        isMakeup: Boolean = false,
    ): Long {
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
                    isMakeup = isMakeup,
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

    /**
     * 过往日期补卡：写 isMakeup=true，并把调用方（已跨月刷新过的）额度落库。
     *
     * 额度刷新与扣张属业务规则，由 ui.habit.HabitScience 算好后传进来，
     * 数据层只做余额兵底校验与写入；余额不足时返回 false，不写入也不扣卡。
     */
    suspend fun makeupCheckIn(
        habitId: Long,
        date: LocalDate,
        note: String,
        amount: Double,
        quotaPeriod: String,
        quotaCards: Int,
        quotaUsedAfter: Int,
    ): Boolean {
        val habit = getAll().firstOrNull { it.id == habitId } ?: return false
        if (quotaUsedAfter > quotaCards) return false
        checkInOn(habitId, date, note, amount, isMakeup = true)
        habitDao.update(
            habit.copy(
                protectionPeriod = quotaPeriod,
                protectionCards = quotaCards,
                protectionUsed = quotaUsedAfter,
            ),
        )
        return true
    }

    /** 对某习惯在今日打卡（幂等，重复打卡不会产生多条记录） */
    suspend fun checkInToday(habitId: Long, note: String = ""): Long =
        checkInOn(habitId, LocalDate.now(), note, 0.0)

    suspend fun isCheckedInToday(habitId: Long): Boolean {
        val today = LocalDate.now().format(dateFmt)
        return habitDao.countCheckInOn(habitId, today) > 0
    }
}
