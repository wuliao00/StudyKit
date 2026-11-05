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

    @Query("SELECT day FROM daily_sign_ins")
    suspend fun allDays(): List<String>

    @Query("DELETE FROM daily_sign_ins")
    suspend fun deleteAll()
}
