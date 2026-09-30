package com.studykit.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.studykit.data.entity.Mistake
import com.studykit.data.entity.MistakeReview
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

    /** 按 id 单次查询（删除等操作前取最新记录用，不依赖 UI 缓存） */
    @Query("SELECT * FROM mistakes WHERE id = :id")
    suspend fun getById(id: Long): Mistake?

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

    /** 到期错题按欠账程度排序（高置信答错的优先） */
    @Query(
        "SELECT * FROM mistakes WHERE mastered = 0 AND review_at IS NOT NULL AND review_at <= :now " +
            "ORDER BY high_confidence_error DESC, review_at ASC",
    )
    suspend fun getDueSorted(now: Long): List<Mistake>

    @Query("UPDATE mistakes SET cause = :cause WHERE id = :id")
    suspend fun updateCause(id: Long, cause: String)

    /** 写入下次复习时间；pinned=1 时后续算法不再覆盖 */
    @Query("UPDATE mistakes SET review_at = :reviewAt, pinned = :pinned WHERE id = :id")
    suspend fun schedule(id: Long, reviewAt: Long?, pinned: Boolean)

    /** 重做结果落库：跨间隔连续答对、FSRS 状态、是否已掌握 */
    @Query(
        "UPDATE mistakes SET correct_streak = :correctStreak, last_gap_days = :lastGapDays, " +
            "stability = :stability, difficulty = :difficulty, reps = :reps, lapses = :lapses, " +
            "last_review_at = :lastReviewAt, high_confidence_error = :highConfidenceError, " +
            "review_at = :reviewAt, mastered = :mastered WHERE id = :id",
    )
    suspend fun applyRedo(
        id: Long,
        correctStreak: Int,
        lastGapDays: Int,
        stability: Double,
        difficulty: Double,
        reps: Int,
        lapses: Int,
        lastReviewAt: Long,
        highConfidenceError: Boolean,
        reviewAt: Long?,
        mastered: Boolean,
    )

    @Insert
    suspend fun insertReview(review: MistakeReview): Long

    @Query("SELECT * FROM mistake_reviews WHERE mistake_id = :mistakeId ORDER BY reviewed_at DESC")
    fun observeReviews(mistakeId: Long): Flow<List<MistakeReview>>

    @Query("SELECT MAX(reviewed_at) FROM mistake_reviews WHERE mistake_id = :mistakeId")
    suspend fun lastRedoAt(mistakeId: Long): Long?

    @Query("SELECT COUNT(*) FROM mistake_reviews")
    fun observeReviewCount(): Flow<Int>
}
