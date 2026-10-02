package com.studykit.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.studykit.data.entity.Book
import com.studykit.data.entity.BookReview
import com.studykit.data.entity.ChapterTest
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

    // ── 主指标「检索练习次数」（v2.7 计划 B Task 18）────────────────────────
    // 一条 UNION ALL：完成的检索动作总和 = 书摘侧各条 review_count(>0) 之和 + 章节自测行数。
    // 纯计数、不编造：只数已经真实发生过的自评与自测行；外层 COALESCE 兜住两表皆空时 SUM 返回 NULL。
    @Query(
        "SELECT COALESCE(SUM(n), 0) FROM (" +
            "SELECT COALESCE(SUM(review_count), 0) AS n FROM excerpts WHERE review_count > 0 " +
            "UNION ALL " +
            "SELECT COUNT(*) AS n FROM chapter_tests"
        + ")",
    )
    fun observeRetrievalCount(): Flow<Int>

    @Query(
        "SELECT COALESCE(SUM(n), 0) FROM (" +
            "SELECT COALESCE(SUM(review_count), 0) AS n FROM excerpts WHERE review_count > 0 " +
            "UNION ALL " +
            "SELECT COUNT(*) AS n FROM chapter_tests"
        + ")",
    )
    suspend fun countRetrievalActions(): Int

    // ── 书摘复习队列（v2.7 spec §7.2）───────────────────────────────────────
    // next_review_at=0 是「未启用」哨兵（每摘 opt-out：不动 review_count，只把 next_review_at 归 0）；
    // 队列只收已启用且到期的那些。
    @Query(
        "SELECT * FROM excerpts WHERE next_review_at > 0 AND next_review_at <= :now " +
            "ORDER BY next_review_at ASC",
    )
    fun observeDueExcerpts(now: Long): Flow<List<Excerpt>>

    /** 「只有划线没有检索」那条 RECALL_NOTES 贴士的判据：:cutoff 之前建的摘且 review_count==0 的条数 */
    @Query("SELECT COUNT(*) FROM excerpts WHERE created_at < :cutoff AND review_count = 0")
    fun observeExcerptsStaleWithoutRecall(cutoff: Long): Flow<Int>

    // ── 章节自测（v2.7 spec §3.2）───────────────────────────────────────────
    @Insert
    suspend fun insertChapterTest(chapterTest: ChapterTest): Long

    @Update
    suspend fun updateChapterTest(chapterTest: ChapterTest)

    @Query("SELECT * FROM chapter_tests WHERE book_id = :bookId ORDER BY tested_at DESC")
    fun observeChapterTests(bookId: Long): Flow<List<ChapterTest>>

    @Query("SELECT * FROM chapter_tests WHERE id = :id")
    suspend fun getChapterTest(id: Long): ChapterTest?

    @Query("SELECT COUNT(*) FROM chapter_tests WHERE book_id = :bookId")
    suspend fun countChapterTests(bookId: Long): Int

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

    // ── 清除学习数据用（设置页「数据管理」）。三张表一起清：
    //    单删 books 会留下 excerpts / book_reviews 指向不存在书籍的悬空行。
    @Query("DELETE FROM book_reviews")
    suspend fun deleteAllReviews()

    @Query("DELETE FROM excerpts")
    suspend fun deleteAllExcerpts()

    @Query("DELETE FROM books")
    suspend fun deleteAllBooks()
}
