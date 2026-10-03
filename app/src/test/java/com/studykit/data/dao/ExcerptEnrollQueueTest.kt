package com.studykit.data.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.studykit.data.AppDatabase
import com.studykit.data.entity.Book
import com.studykit.data.entity.Excerpt
import com.studykit.data.repository.BookRepository
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 新摘录「默认入复习队列」这条写路径的集成守卫（v2.7.0.1 修复 / spec §7.2）。
 *
 * ## 它钉住什么
 *
 * v2.7 发版时如实记下的缺陷：`addExcerpt` 落库 `next_review_at=0`（未启用哨兵），而复习队列那条
 * SQL 要 `next_review_at > 0`，生产里又没有任何录入侧的纳入路径把它写成正数——于是新摘录永远进不了
 * 队列，复习页只剩空态。修复口径回归 spec §7.2 原样：新摘录默认 `next_review_at = now + 1d`。
 *
 * 走 in-memory Room 而非假 DAO，是因为这条性质长在**真实写路径 + 真实到期 SQL**上：只有把
 * `BookRepository.addExcerpt` 真的插进行、再用 `observeDueExcerpts` 那条查询读回来，才能同时证明
 * 「建摘即入队」与「now+1d 意味着今天不到期、约一天后才到期」。量具复用 [RetrievalCountTest] 那套
 * Robolectric + `Room.inMemoryDatabaseBuilder` 纪律，本仓 CI 唯一执行器 `testDebugUnitTest` 能跑就不引模拟器。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExcerptEnrollQueueTest {

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun newBook(): Long =
        db.bookDao().insert(Book(uuid = "b-1", title = "线性代数", author = "佚名", totalPages = 200))

    // ── 默认入队：addExcerpt 落库即 next_review_at > 0，且明天才到期 ──────────

    @Test
    fun `新建书摘默认排进复习队列_此刻不到期_约一天后才到期`() = runBlocking {
        val bookId = newBook()
        val repo = BookRepository(db.bookDao())
        val now = System.currentTimeMillis()

        val excerptId = repo.addExcerpt(bookId, "特征值 λ det(A−λI)=0", pageNo = 63)
        val saved = db.bookDao().getExcerpt(excerptId)!!

        // 缺陷现状：saved.nextReviewAt 落的是 0（未启用哨兵）→ 这条断言先红。
        assertTrue(
            "新摘录应默认入队（next_review_at>0），实际 ${saved.nextReviewAt}",
            saved.nextReviewAt > 0L,
        )
        // spec §7.2 原口径 now+1d：当天不该到期，约一天之后才进到期队列。
        val dueToday = db.bookDao().observeDueExcerpts(now).first()
        assertTrue("今天不该到期", dueToday.none { it.id == excerptId })
        val dueTomorrow = db.bookDao().observeDueExcerpts(now + TimeUnit.DAYS.toMillis(2)).first()
        assertTrue("约一天后应出现在到期队列", dueTomorrow.any { it.id == excerptId })
    }

    // ── opt-out 契约：哨兵 0 被到期查询排除（这条修复前后都应绿，钉住不回归） ──

    @Test
    fun `opt_out_写哨兵0_到期查询把它排除`() = runBlocking {
        val bookId = newBook()
        val now = System.currentTimeMillis()
        // 两行对照：一条已入队（此刻正好到期，>0 且 <=now），一条已 opt-out（未启用哨兵 0）。
        val enrolledId = db.bookDao().insertExcerpt(
            Excerpt(uuid = "enrolled", bookId = bookId, content = "入队的那条", nextReviewAt = now),
        )
        db.bookDao().insertExcerpt(
            Excerpt(uuid = "optedout", bookId = bookId, content = "退出的那条", nextReviewAt = 0L),
        )

        val due = db.bookDao().observeDueExcerpts(now).first()
        assertEquals("只收已启用且到期的那条", listOf(enrolledId), due.map { it.id })
        assertTrue("哨兵 0 永不进队列", due.none { it.nextReviewAt == 0L })
    }
}
