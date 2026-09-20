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

    /** 到期待复习且未掌握的单词（复习提醒用，WHERE 条件下推 SQL，避免全量内存过滤） */
    @Query(
        "SELECT * FROM words WHERE status != 'MASTERED' AND next_review_at <= :now " +
            "ORDER BY next_review_at ASC",
    )
    suspend fun getDueForReview(now: Long): List<Word>

    @Query("SELECT * FROM words WHERE status = :status ORDER BY created_at DESC")
    fun observeByStatus(status: String): Flow<List<Word>>

    @Query("SELECT COUNT(*) FROM words")
    fun observeCount(): Flow<Int>

    @Insert
    suspend fun insert(word: Word): Long

    /** 批量入库；返回自增 id 列表，实践上与入参同序，但 Room 未承诺 —— 勿依赖顺序，只当入库计数用 */
    @Insert
    suspend fun insertAll(words: List<Word>): List<Long>

    /** 全量词面，仅用于导入前去重（词表万级以内可接受；M2 不做索引优化） */
    @Query("SELECT word FROM words")
    suspend fun getWordTexts(): List<String>

    @Query("DELETE FROM words WHERE source_list_id = :sourceListId")
    suspend fun deleteByList(sourceListId: Long): Int

    @Query("SELECT COUNT(*) FROM words WHERE source_list_id = :sourceListId")
    suspend fun countByList(sourceListId: Long): Int

    @Update
    suspend fun update(word: Word)

    @Delete
    suspend fun delete(word: Word)

    @Query("UPDATE words SET status = :status, next_review_at = :nextReviewAt WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String, nextReviewAt: Long = 0L)

    @Insert
    suspend fun insertReview(review: WordReview): Long

    /** 学习活跃日统计用：全部复习时间戳 */
    @Query("SELECT reviewed_at FROM word_reviews")
    fun observeReviewTimestamps(): Flow<List<Long>>
}
