package com.studykit.data.repository

import com.studykit.data.dao.BookDao
import com.studykit.data.entity.Book
import com.studykit.data.entity.BookRecall
import com.studykit.data.entity.BookReview
import com.studykit.data.entity.Excerpt
import com.studykit.srs.Fsrs
import com.studykit.srs.SchedulingResult
import kotlinx.coroutines.flow.Flow
import java.util.UUID

class BookRepository(private val bookDao: BookDao) {

    fun observeAll(): Flow<List<Book>> = bookDao.observeAll()

    fun observeById(id: Long): Flow<Book?> = bookDao.observeById(id)

    suspend fun getById(id: Long): Book? = bookDao.getById(id)

    suspend fun add(title: String, author: String, totalPages: Int): Long =
        bookDao.insert(
            Book(uuid = UUID.randomUUID().toString(), title = title, author = author, totalPages = totalPages),
        )

    suspend fun update(book: Book) = bookDao.update(book)

    /** 更新阅读进度（当前页码） */
    suspend fun updateProgress(id: Long, page: Int) = bookDao.updateProgress(id, page)

    /** 标记书籍为已读完 */
    suspend fun markFinished(id: Long) = bookDao.markFinished(id, System.currentTimeMillis())

    fun observeExcerpts(bookId: Long): Flow<List<Excerpt>> = bookDao.observeExcerpts(bookId)

    fun observeExcerptCount(): Flow<Int> = bookDao.observeExcerptCount()

    fun observeExcerpt(id: Long): Flow<Excerpt?> = bookDao.observeExcerpt(id)

    suspend fun getExcerpt(id: Long): Excerpt? = bookDao.getExcerpt(id)

    /**
     * 新建书摘。[enqueue] 为 true 时给它一个次日到期的初始排期，使其进入间隔复习队列；
     * 排期参数（stability/reps）保持初始值，第一次「复习这条」时再由 FSRS 首次评分接管。
     */
    suspend fun addExcerpt(bookId: Long, content: String, pageNo: Int?, enqueue: Boolean): Long =
        bookDao.insertExcerpt(
            Excerpt(
                uuid = UUID.randomUUID().toString(),
                bookId = bookId,
                content = content,
                pageNo = pageNo,
                nextReviewAt = if (enqueue) System.currentTimeMillis() + Fsrs.DAY_MS else 0L,
            ),
        )

    suspend fun updateExcerpt(excerpt: Excerpt) = bookDao.updateExcerpt(excerpt)

    suspend fun deleteExcerpt(excerpt: Excerpt) = bookDao.deleteExcerpt(excerpt)

    /** 当前到期的书摘列表 */
    suspend fun getExcerptsDue(now: Long): List<Excerpt> = bookDao.getExcerptsDue(now)

    /** 一次复习后写回书摘的记忆状态与下次到期 */
    suspend fun scheduleExcerptReview(excerptId: Long, result: SchedulingResult) =
        bookDao.applyRecallScheduling(
            id = excerptId,
            nextReviewAt = result.dueAt,
            stability = result.state.stability,
            difficulty = result.state.difficulty,
            reps = result.state.reps,
            lapses = result.state.lapses,
            lastReviewAt = result.state.lastReviewAt,
        )

    // ── 合书回忆（检索练习）─────────────────────────────────────────────

    fun observeRecalls(bookId: Long): Flow<List<BookRecall>> = bookDao.observeRecalls(bookId)

    /** 全库检索练习总数：书架页的展示指标 */
    fun observeRecallCount(): Flow<Int> = bookDao.observeRecallCount()

    /** 单本检索练习次数：详情页的展示指标 */
    fun observeRecallCount(bookId: Long): Flow<Int> = bookDao.observeRecallCount(bookId)

    /** 落盘一次合书回忆；nextReviewAt 由调用方按 FSRS 计算好传入 */
    suspend fun addRecall(
        bookId: Long,
        pageNo: Int?,
        question: String,
        answer: String,
        selfScore: Int,
        nextReviewAt: Long,
    ): Long = bookDao.insertRecall(
        BookRecall(
            uuid = UUID.randomUUID().toString(),
            bookId = bookId,
            pageNo = pageNo,
            question = question,
            answer = answer,
            selfScore = selfScore.coerceIn(0, 2),
            nextReviewAt = nextReviewAt,
        ),
    )

    suspend fun updateRecall(recall: BookRecall) = bookDao.updateRecall(recall)

    fun observeReviews(bookId: Long): Flow<List<BookReview>> = bookDao.observeReviews(bookId)

    fun observeReview(id: Long): Flow<BookReview?> = bookDao.observeReview(id)

    suspend fun getReview(id: Long): BookReview? = bookDao.getReview(id)

    suspend fun addReview(bookId: Long, rating: Int, content: String): Long =
        bookDao.insertReview(
            BookReview(
                uuid = UUID.randomUUID().toString(),
                bookId = bookId,
                rating = rating.coerceIn(1, 5),
                content = content,
            ),
        )

    suspend fun updateReview(review: BookReview) =
        bookDao.updateReview(review.copy(rating = review.rating.coerceIn(1, 5)))
}
