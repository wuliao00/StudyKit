package com.studykit.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.studykit.data.entity.Mistake
import kotlinx.coroutines.flow.Flow

@Dao
interface MistakeDao {

    @Query("SELECT * FROM mistakes ORDER BY created_at DESC")
    fun observeAll(): Flow<List<Mistake>>

    @Query("SELECT * FROM mistakes WHERE source = :source ORDER BY created_at DESC")
    fun observeBySource(source: String): Flow<List<Mistake>>

    @Query("SELECT * FROM mistakes WHERE subject = :subject ORDER BY created_at DESC")
    fun observeBySubject(subject: String): Flow<List<Mistake>>

    @Query("SELECT * FROM mistakes WHERE mastered = 0 ORDER BY created_at DESC")
    fun observeUnmastered(): Flow<List<Mistake>>

    @Query("SELECT * FROM mistakes WHERE id = :id")
    fun observeById(id: Long): Flow<Mistake?>

    /** 到期且未掌握的错题（复习提醒用） */
    @Query("SELECT * FROM mistakes WHERE mastered = 0 AND review_at IS NOT NULL AND review_at <= :now")
    suspend fun getDueForReview(now: Long): List<Mistake>

    @Query("SELECT COUNT(*) FROM mistakes WHERE mastered = 0")
    fun observeUnmasteredCount(): Flow<Int>

    @Query("DELETE FROM mistakes WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Insert
    suspend fun insert(mistake: Mistake): Long

    @Update
    suspend fun update(mistake: Mistake)

    @Query("UPDATE mistakes SET mastered = 1 WHERE id = :id")
    suspend fun markMastered(id: Long)
}
