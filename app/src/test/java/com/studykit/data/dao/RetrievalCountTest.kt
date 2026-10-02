package com.studykit.data.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.studykit.data.AppDatabase
import com.studykit.data.entity.Book
import com.studykit.data.entity.ChapterTest
import com.studykit.data.entity.Excerpt
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 主指标「检索练习次数」的聚合计数（v2.7 计划 B Task 18）。
 *
 * ## 它钉住什么
 *
 * 书架 hero 那第一等数字从「书摘总数」改成「检索练习次数」—— 完成的检索动作总和。
 * 口径必须是**纯计数**、不编造：
 *  - 书摘侧 = 各条 `excerpts.review_count`（自评次数）之和，只累加 `> 0` 的贡献；
 *  - 章节自测侧 = `chapter_tests` 的行数（每行一次完成的检索）。
 *
 * 走 in-memory Room 而非假 DAO，是因为这条性质长在真实 SQL（UNION + SUM + WHERE）上 ——
 * `ContractBatchSettleTest` 已立好这套 Robolectric + `Room.inMemoryDatabaseBuilder` 的量具，
 * 本仓 CI 唯一执行器是 `testDebugUnitTest`，能这么跑就不引模拟器。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RetrievalCountTest {

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

    private fun book() = Book(uuid = "b-1", title = "书", author = "甲", totalPages = 100)

    private fun excerpt(reviewCount: Int) = Excerpt(
        uuid = "e-$reviewCount-${System.nanoTime()}",
        bookId = 1L,
        content = "摘",
        reviewCount = reviewCount,
    )

    private fun chapterTest() = ChapterTest(
        uuid = "c-${System.nanoTime()}",
        bookId = 1L,
        chapterLabel = "第1章",
        question = "Q",
        expectedAnswer = "A",
        passed = true,
    )

    @Test
    fun `空库检索练习次数是零而不是凭空数`() = runBlocking {
        assertEquals(0, db.bookDao().countRetrievalActions())
        assertEquals(0, db.bookDao().observeRetrievalCount().first())
    }

    @Test
    fun `书摘按自评次数累加_未自评的那条不计`() = runBlocking {
        val bookId = db.bookDao().insert(book())
        db.bookDao().insertExcerpt(excerpt(reviewCount = 3).copy(bookId = bookId))
        db.bookDao().insertExcerpt(excerpt(reviewCount = 0).copy(bookId = bookId))
        db.bookDao().insertExcerpt(excerpt(reviewCount = 2).copy(bookId = bookId))
        // 3 + 0（不计）+ 2 = 5，且没有任何章节自测时也是 5
        assertEquals(5, db.bookDao().countRetrievalActions())
    }

    @Test
    fun `章节自测每行记一次_与书摘自评相加`() = runBlocking {
        val bookId = db.bookDao().insert(book())
        db.bookDao().insertExcerpt(excerpt(reviewCount = 4).copy(bookId = bookId))
        db.bookDao().insertChapterTest(chapterTest().copy(bookId = bookId))
        db.bookDao().insertChapterTest(chapterTest().copy(bookId = bookId))
        // 书摘自评 4 + 章节自测 2 行 = 6
        assertEquals(6, db.bookDao().countRetrievalActions())
        // Flow 变体与 suspend 变体同一份 SQL，读数必须一致
        assertEquals(6, db.bookDao().observeRetrievalCount().first())
    }

    @Test
    fun `只有章节自测没有书摘时也只数真实行`() = runBlocking {
        val bookId = db.bookDao().insert(book())
        repeat(3) { db.bookDao().insertChapterTest(chapterTest().copy(bookId = bookId)) }
        assertEquals(3, db.bookDao().countRetrievalActions())
    }
}
