package com.studykit.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.studykit.data.entity.PracticeRecord
import kotlinx.coroutines.flow.Flow

@Dao
interface PracticeDao {

    @Insert
    suspend fun insert(record: PracticeRecord): Long

    @Query("SELECT * FROM practice_records WHERE question_id = :questionId ORDER BY at DESC")
    fun observeByQuestion(questionId: Long): Flow<List<PracticeRecord>>

    @Query("SELECT COUNT(*) FROM practice_records WHERE question_id = :questionId")
    suspend fun countByQuestion(questionId: Long): Int

    @Query("SELECT COUNT(*) FROM practice_records")
    fun observeTotal(): Flow<Int>
}
