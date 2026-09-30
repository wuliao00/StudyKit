package com.studykit.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.studykit.data.entity.Book
import com.studykit.data.entity.BookRecall
import com.studykit.data.entity.BookReview
import com.studykit.data.entity.Excerpt
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {

    @Query("SELECT * FROM books ORDER BY started_at DESC")
    fun observeAll(): Flow<List<Book>>

    @Query("SELECT * FROM books WHERE id = :id")
    fun observeById(id: Long): Flow<Book?>

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun getById(id: Long): Book?

    @Insert
    suspend fun insert(book: Book): Long

    @Update
    suspend fun update(book: Book)

    @Query("UPDATE books SET current_page = :page WHERE id = :id")
    suspend fun updateProgress(id: Long, page: Int)

    @Query("UPDATE books SET status = 'finished', finished_at = :finishedAt, current_page = total_pages WHERE id = :id")
    suspend fun markFinished(id: Long, finishedAt: Long = System.currentTimeMillis())

    @Query("SELECT COUNT(*) FROM excerpts")
    fun observeExcerptCount(): Flow<Int>

    @Query("SELECT * FROM excerpts WHERE book_id = :bookId ORDER BY created_at DESC")
    fun observeExcerpts(bookId: Long): Flow<List<Excerpt>>

    @Query("SELECT * FROM excerpts WHERE id = :id")
    fun observeExcerpt(id: Long): Flow<Excerpt?>

    @Query("SELECT * FROM excerpts WHERE id = :id")
    suspend fun getExcerpt(id: Long): Excerpt?

    @Insert
    suspend fun insertExcerpt(excerpt: Excerpt): Long

    @Update
    suspend fun updateExcerpt(excerpt: Excerpt)

    @Delete
    suspend fun deleteExcerpt(excerpt: Excerpt)

    @Query("SELECT * FROM book_reviews WHERE book_id = :bookId ORDER BY at DESC")
    fun observeReviews(bookId: Long): Flow<List<BookReview>>

    @Query("SELECT * FROM book_reviews WHERE id = :id")
    fun observeReview(id: Long): Flow<BookReview?>

    @Query("SELECT * FROM book_reviews WHERE id = :id")
    suspend fun getReview(id: Long): BookReview?

    @Insert
    suspend fun insertReview(review: BookReview): Long

    @Update
    suspend fun updateReview(review: BookReview)

    // ── 检索式笔记（合书回忆自测）──────────────────────────────────

    @Insert
    suspend fun insertRecall(recall: BookRecall): Long

    @Update
    suspend fun updateRecall(recall: BookRecall)

    @Query("SELECT * FROM book_recalls WHERE book_id = :bookId ORDER BY created_at DESC")
    fun observeRecalls(bookId: Long): Flow<List<BookRecall>>

    @Query("SELECT COUNT(*) FROM book_recalls")
    fun observeRecallCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM book_recalls WHERE book_id = :bookId")
    fun observeRecallCount(bookId: Long): Flow<Int>

    // ── 书摘的间隔复习队列──────────────────────────────────────

    @Query("SELECT * FROM excerpts WHERE next_review_at > 0 AND next_review_at <= :now ORDER BY next_review_at ASC")
    suspend fun getExcerptsDue(now: Long): List<Excerpt>

    @Query("SELECT * FROM excerpts WHERE next_review_at > 0 ORDER BY next_review_at ASC")
    suspend fun getScheduledExcerpts(): List<Excerpt>

    /** 一次检索练习后更新书摘的记忆状态与下次到期 */
    @Query(
        "UPDATE excerpts SET recall_count = recall_count + 1, next_review_at = :nextReviewAt, " +
            "stability = :stability, difficulty = :difficulty, reps = :reps, lapses = :lapses, " +
            "last_review_at = :lastReviewAt WHERE id = :id",
    )
    suspend fun applyRecallScheduling(
        id: Long,
        nextReviewAt: Long,
        stability: Double,
        difficulty: Double,
        reps: Int,
        lapses: Int,
        lastReviewAt: Long,
    )
}
