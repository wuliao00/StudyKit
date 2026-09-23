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

    /** 全部习惯**含已归档**（整理页用，v2.4 批次四）；组序在内存侧纯函数里定，SQL 不背分类知识 */
    @Query("SELECT * FROM habits")
    fun observeAllIncludingArchived(): Flow<List<Habit>>

    @Insert
    suspend fun insert(habit: Habit): Long

    @Update
    suspend fun update(habit: Habit)

    @Query("UPDATE habits SET archived = 1 WHERE id = :id")
    suspend fun archive(id: Long)

    /** 归档开关（整理页，v2.4 批次四）：true=归档隐藏，false=取消归档恢复显示 */
    @Query("UPDATE habits SET archived = :archived WHERE id = :id")
    suspend fun setArchived(id: Long, archived: Boolean)

    /** 改时段分类（整理页，v2.4 批次四）：ANY/MORNING/.../NIGHT，取值由 UI 层收口 */
    @Query("UPDATE habits SET category = :category WHERE id = :id")
    suspend fun updateCategory(id: Long, category: String)

    /** 写手动排序（整理页上移/下移，v2.4 批次四）：成对交换由调用方保证原子语义 */
    @Query("UPDATE habits SET sort_order = :sortOrder WHERE id = :id")
    suspend fun updateSortOrder(id: Long, sortOrder: Int)

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

    /**
     * 某习惯在 [fromStr, toStr] 闭区间内的打卡次数（自我契约对账用，v2.4 批次五）。
     *
     * `check_ins.date` 存的是 'yyyy-MM-dd' TEXT：四位定长年份 + 零填充月日，**字典序与日期序一致**，
     * 所以 SQL 的 BETWEEN 直接比字符串就是日期区间，不必逐日展开或另建数值列；
     * 反过来也意味着调用方必须保证传进来的串严格是 yyyy-MM-dd（格式化集中在
     * `ContractRepository.countCheckInsBetween`，不散落各处）。
     */
    @Query("SELECT COUNT(*) FROM check_ins WHERE habit_id = :habitId AND date BETWEEN :fromStr AND :toStr")
    suspend fun countCheckIns(habitId: Long, fromStr: String, toStr: String): Int

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
