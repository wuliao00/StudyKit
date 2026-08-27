package com.studykit.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.studykit.data.entity.Word
import com.studykit.data.entity.WordReview
import kotlinx.coroutines.flow.Flow

@Dao
interface WordDao {

    @Query("SELECT * FROM words ORDER BY created_at DESC")
    fun observeAll(): Flow<List<Word>>

    @Query("SELECT * FROM words ORDER BY created_at DESC")
    suspend fun getAll(): List<Word>

    @Query("SELECT * FROM words WHERE status = :status ORDER BY created_at DESC")
    fun observeByStatus(status: String): Flow<List<Word>>

    @Query("SELECT COUNT(*) FROM words")
    fun observeCount(): Flow<Int>

    @Insert
    suspend fun insert(word: Word): Long

    @Update
    suspend fun update(word: Word)

    @Delete
    suspend fun delete(word: Word)

    @Query("UPDATE words SET status = :status, next_review_at = :nextReviewAt WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String, nextReviewAt: Long = 0L)

    @Insert
    suspend fun insertReview(review: WordReview): Long
}
