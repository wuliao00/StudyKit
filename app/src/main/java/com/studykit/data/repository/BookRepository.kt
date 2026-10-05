package com.studykit.data.repository

import com.studykit.data.dao.BookDao
import com.studykit.data.dao.ScheduledExcerptRow
import com.studykit.data.entity.Book
import com.studykit.data.entity.BookReview
import com.studykit.data.entity.ChapterTest
import com.studykit.data.entity.Excerpt
import com.studykit.data.memory.Enrollment
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

    /** 主指标「检索练习次数」：书摘 review_count(>0) 之和 + 章节自测行数（一条 UNION SQL） */
    fun observeRetrievalCount(): Flow<Int> = bookDao.observeRetrievalCount()

    /** 到期且已启用的书摘（next_review_at>0 且 <= now） */
    fun observeDueExcerpts(now: Long): Flow<List<Excerpt>> = bookDao.observeDueExcerpts(now)

    /**
     * 全部**已排期**的书摘（负荷带 / 稳定性全景的取数口，v3.0-A Task 6 的 [com.studykit.ui.stats.StatsViewModel]
     * 是第一个消费者）。
     *
     * 只转发不加一层筛：「哪些行算排过期」的判据只有 [BookDao.observeScheduledExcerpts] 那一处 SQL，
     * 仓储再判一次就和词卡、错题那两侧分叉了（同一件事在 `ForecastCards` 类 KDoc 里写过）。
     * 加这一层的理由与 [WordRepository.observeScheduledMemoryRows] 相同：ViewModel 只认仓储，不碰 DAO。
     */
    fun observeScheduledExcerpts(): Flow<List<ScheduledExcerptRow>> = bookDao.observeScheduledExcerpts()

    /** :cutoff 之前建、却从没检索过（review_count==0）的书摘条数——RECALL_NOTES 贴士的判据 */
    fun observeExcerptsStaleWithoutRecall(cutoff: Long): Flow<Int> =
        bookDao.observeExcerptsStaleWithoutRecall(cutoff)

    fun observeExcerpt(id: Long): Flow<Excerpt?> = bookDao.observeExcerpt(id)

    suspend fun getExcerpt(id: Long): Excerpt? = bookDao.getExcerpt(id)

    /**
     * 新建书摘。v2.7.0.1 修复 / spec §7.2：建摘即默认入复习队列——`nextReviewAt` 填
     * [Enrollment.initialNextReviewAt] 给的 `now + 1d`（明天第一次到期），不再是实体默认的 0 未启用哨兵。
     * 每摘的 opt-out 仍走复习页「把这条移出复习队列」（`setExcerptEnrolled(false)` → 归 0），零 schema 变更。
     */
    suspend fun addExcerpt(bookId: Long, content: String, pageNo: Int?): Long =
        bookDao.insertExcerpt(
            Excerpt(
                uuid = UUID.randomUUID().toString(),
                bookId = bookId,
                content = content,
                pageNo = pageNo,
                nextReviewAt = Enrollment.initialNextReviewAt(System.currentTimeMillis()),
            ),
        )

    suspend fun updateExcerpt(excerpt: Excerpt) = bookDao.updateExcerpt(excerpt)

    suspend fun deleteExcerpt(excerpt: Excerpt) = bookDao.deleteExcerpt(excerpt)

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

    // ── 章节自测（v2.7 spec §3.2）─────────────────────────────────
    fun observeChapterTests(bookId: Long): Flow<List<ChapterTest>> = bookDao.observeChapterTests(bookId)

    suspend fun getChapterTest(id: Long): ChapterTest? = bookDao.getChapterTest(id)

    suspend fun countChapterTests(bookId: Long): Int = bookDao.countChapterTests(bookId)

    /** 新建一道章节自测（passed 默认 false，作答时再回写） */
    suspend fun addChapterTest(bookId: Long, chapterLabel: String, question: String, expectedAnswer: String): Long =
        bookDao.insertChapterTest(
            ChapterTest(
                uuid = UUID.randomUUID().toString(),
                bookId = bookId,
                chapterLabel = chapterLabel,
                question = question,
                expectedAnswer = expectedAnswer,
                passed = false,
            ),
        )

    suspend fun updateChapterTest(chapterTest: ChapterTest) = bookDao.updateChapterTest(chapterTest)
}
