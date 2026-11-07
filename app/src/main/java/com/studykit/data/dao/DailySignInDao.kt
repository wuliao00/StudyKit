package com.studykit.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.studykit.data.entity.DailySignIn
import kotlinx.coroutines.flow.Flow

@Dao
interface DailySignInDao {

    /** 同一天重复签到时由主键冲突静默忽略；返回 -1 表示今天已经签过。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(signIn: DailySignIn): Long

    @Query("SELECT day FROM daily_sign_ins ORDER BY day DESC")
    fun observeDays(): Flow<List<String>>

    @Query("SELECT day FROM daily_sign_ins WHERE makeup = 1 ORDER BY day DESC")
    fun observeMakeupDays(): Flow<List<String>>

    @Query("SELECT day FROM daily_sign_ins")
    suspend fun allDays(): List<String>

    @Query("SELECT day FROM daily_sign_ins WHERE makeup = 1")
    suspend fun allMakeupDays(): List<String>

    @Query("DELETE FROM daily_sign_ins WHERE day = :day AND makeup = 1")
    suspend fun deleteMakeup(day: String): Int

    @Query("DELETE FROM daily_sign_ins")
    suspend fun deleteAll()
}
