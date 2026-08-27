package com.studykit.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.studykit.data.entity.Question
import kotlinx.coroutines.flow.Flow

@Dao
interface QuestionDao {

    @Query("SELECT * FROM questions ORDER BY id")
    fun observeAll(): Flow<List<Question>>

    @Query("SELECT * FROM questions WHERE id = :id")
    suspend fun getById(id: Long): Question?

    @Query("SELECT * FROM questions WHERE subject = :subject ORDER BY id LIMIT :limit OFFSET :offset")
    suspend fun getBySubject(subject: String, limit: Int, offset: Int): List<Question>

    @Query("SELECT DISTINCT subject FROM questions ORDER BY subject")
    fun observeSubjects(): Flow<List<String>>

    @Query("SELECT COUNT(*) FROM questions WHERE subject = :subject")
    suspend fun countBySubject(subject: String): Int

    @Insert
    suspend fun insert(question: Question): Long
}
