package com.studykit.data.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.studykit.data.AppDatabase
import com.studykit.data.entity.Word
import com.studykit.data.entity.WordReview
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
 * [WordDao.observeReviewTimestampsBetween]（v3.0-A Task 4，Task 2 时就该随窗口语义一起落地）。
 *
 * 两个生产读数都长在这条查询上，而它们的正确性都在**窗口边界**上：
 *  - `ReminderWorker` 拿「今天零点 → 此刻」判 `todayReviewed`（[com.studykit.data.memory.ReminderPolicy.decide]
 *    的第一道闸：今天练过就不催）。零点那一刻那条复习**必须**算进今天，否则用户在 00:00 打卡后仍会被催。
 *  - `ReminderScheduler` 拿「0 → 此刻」的全量历史算复习众数小时（相位的原料）。
 *
 * 窗口两端是否闭、区间外是否真的不进来、以及 `BETWEEN` 拼错成单侧条件——这些只有真 SQL 上测得到，
 * 所以走 in-memory Room（`RetrievalCountTest` 立好的同一套量具）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReviewTimestampWindowTest {

    private lateinit var db: AppDatabase
    private var wordId: Long = 0L

    private val dayStart = 1_760_000_000_000L
    private val hourMs = 3_600_000L

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        wordId = db.wordDao().insert(
            Word(uuid = "w-1", word = "example", meaning = "示例", example = ""),
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun reviewAt(reviewedAt: Long) {
        db.wordDao().insertReview(
            WordReview(uuid = "r-$reviewedAt", wordId = wordId, correct = true, reviewedAt = reviewedAt),
        )
    }

    @Test
    fun `窗口两端都是闭区间`() = runBlocking {
        reviewAt(dayStart)
        reviewAt(dayStart + hourMs)
        reviewAt(dayStart + 6 * hourMs)

        val inWindow = db.wordDao().observeReviewTimestampsBetween(dayStart, dayStart + 6 * hourMs)
            .first()

        // 起点与终点那两条都要在里面：半开区间会把「零点整的那一次」漏掉
        assertEquals(listOf(dayStart, dayStart + hourMs, dayStart + 6 * hourMs), inWindow)
    }

    @Test
    fun `窗口外的复习不进来`() = runBlocking {
        reviewAt(dayStart - 1)
        reviewAt(dayStart)
        reviewAt(dayStart + 6 * hourMs)
        reviewAt(dayStart + 6 * hourMs + 1)

        val inWindow = db.wordDao().observeReviewTimestampsBetween(dayStart, dayStart + 6 * hourMs)
            .first()

        assertEquals(listOf(dayStart, dayStart + 6 * hourMs), inWindow)
    }

    /** 空库/没练过 ⇒ 空列表，`todayReviewed` 据此为 false；绝不能是 null 或"取不到就当练过"。 */
    @Test
    fun `没有任何复习时返回空列表`() = runBlocking {
        assertEquals(emptyList<Long>(), db.wordDao().observeReviewTimestampsBetween(0L, dayStart).first())
    }
}
