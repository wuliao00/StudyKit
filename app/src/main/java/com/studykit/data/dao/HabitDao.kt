package com.studykit.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.studykit.data.entity.CheckIn
import com.studykit.data.entity.Habit
import kotlinx.coroutines.flow.Flow

@Dao
interface HabitDao {

    @Query("SELECT * FROM habits WHERE archived = 0 ORDER BY start_date DESC")
    fun observeAll(): Flow<List<Habit>>

    @Query("SELECT * FROM habits ORDER BY start_date DESC")
    suspend fun getAll(): List<Habit>

    @Insert
    suspend fun insert(habit: Habit): Long

    @Update
    suspend fun update(habit: Habit)

    @Query("UPDATE habits SET archived = 1 WHERE id = :id")
    suspend fun archive(id: Long)

    @Query("SELECT * FROM check_ins WHERE habit_id = :habitId ORDER BY date DESC")
    fun observeCheckIns(habitId: Long): Flow<List<CheckIn>>

    /** 全部打卡记录（全局日历按日叠加用） */
    @Query("SELECT * FROM check_ins ORDER BY date ASC")
    fun observeAllCheckIns(): Flow<List<CheckIn>>

    @Query("SELECT COUNT(*) FROM check_ins WHERE habit_id = :habitId")
    fun observeCheckInCount(habitId: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM check_ins WHERE habit_id = :habitId AND date = :date")
    suspend fun countCheckInOn(habitId: Long, date: String): Int

    /** 查某习惯某日的打卡记录（补卡/累加/改备注用） */
    @Query("SELECT * FROM check_ins WHERE habit_id = :habitId AND date = :date LIMIT 1")
    suspend fun findCheckInOn(habitId: Long, date: String): CheckIn?

    @Update
    suspend fun updateCheckIn(checkIn: CheckIn)

    /** 全部打卡记录（导出 CSV 用，一次性读取） */
    @Query("SELECT * FROM check_ins ORDER BY date ASC")
    suspend fun getAllCheckIns(): List<CheckIn>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCheckIn(checkIn: CheckIn): Long

    // ── 清除学习数据用（设置页「数据管理」）。两条必须一起调：
    //    只删习惯会留下指向已消失习惯的 check_ins 悬空行，而它会让日历页读到幽灵日期。
    @Query("DELETE FROM check_ins")
    suspend fun deleteAllCheckIns()

    @Query("DELETE FROM habits")
    suspend fun deleteAllHabits()
}
