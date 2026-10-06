package com.studykit.optimize

import android.os.Looper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.studykit.AppContainer
import com.studykit.StudyKitApp
import com.studykit.data.AppDatabase
import com.studykit.data.entity.Book
import com.studykit.data.entity.Excerpt
import com.studykit.data.entity.ReviewLog
import com.studykit.data.entity.Word
import com.studykit.data.memory.ReviewGrade
import com.studykit.ui.book.BookViewModel
import com.studykit.ui.study.StudyViewModel
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 只为本文件那四条用例把容器换成一次性内存库（覆写点见 [StudyKitApp.createContainer]，理由见
 * [AppContainer] 类注释）：`review_log` 是 append-only、DAO 没有删除路径，库必须每个用例全新，
 * 否则上一条用例的行会留在下一条里，`singleOrNull` 就成了碰运气。
 */
class ScheduledDaysWriterTestApp : StudyKitApp() {
    override fun createContainer(): AppContainer = AppContainer(
        Room.inMemoryDatabaseBuilder(this, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build(),
    )
}

/**
 * T2.5 —— 两处**真评分点**（背词 / 书摘自评）往 `review_log.scheduled_days` 上写的那个数，必须是
 * 「这次评分**之前**该卡被排出去的计划间隔」（后向口径：[ReviewLog.scheduledDays] 的 KDoc、
 * [SynthLog] 与 py-fsrs `review_log.scheduled_days` 同一条约定），**不是**「这次评分刚排出去的间隔」。
 *
 * ## 为什么这一份要真起 ViewModel + Room
 * [ReviewLogWriteTest] 测的是仓储 helper 那一层：helper 只照原样落参数，口径对错它看不出来。
 * 而本缺陷恰恰出在**调用点传的是哪一个数**（词侧传 `days`=刚排出去的，书摘侧传 `0.0`=间隔没回传）。
 * 只测推导式的话，调用点换回 `days` 测试照样绿 —— 那种测试不是证据，所以这里把两条评分路径真跑
 * 一遍（Robolectric + 应用自己的 `container`），断言**落库的那一行**。
 *
 * ## 怎么保证这些断言分辨得出新旧接线（不是恒真）
 * 每条用例把「评分前行上的计划间隔」和「这次评分刚排出去的间隔」做成两个明显不同的数，然后
 *  1. 断言落库值 == 前者（逐字精确，播种常量相减）；
 *  2. 再**回读主表那一行**拿到后者（`applyReview` / `updateExcerpt` 刚写进去的到期），
 *     `assertNotEquals` 盯住它 —— 旧接线写的正是这个数（书摘侧旧接线写 0.0，也落不进期望值）。
 * 于是把调用点改回 `days` 或改回 `0.0`，第 1 条或第 2 条必红；本文件先红后绿（TDD）。
 *
 * ## 时钟
 * 评分点用 `System.currentTimeMillis()`，本测试不拨 Robolectric 的时钟，而是把**锚点**摆到相对真实
 * now 的整数天前：`elapsed_days` 因此是「整数天 + 几毫秒」，按 0.01 天容差读；`scheduled_days` 由两个
 * 播种常量相减，逐字精确（1e-9）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = ScheduledDaysWriterTestApp::class)
class ReviewLogScheduledDaysWriterTest {

    private val dayMs = TimeUnit.DAYS.toMillis(1)
    private val app get() = ApplicationProvider.getApplicationContext<StudyKitApp>()
    private val db get() = app.container.database

    /**
     * 评分点是 `viewModelScope.launch` + Room suspend：主线程（= Robolectric 测试线程）上把主 looper
     * 反复 idle，让「状态写 → 日志写」两步在 Room 的 executor 上跑完再回来。读到目标行即返回，
     * 超时才失败 —— 不用固定 sleep 赌时长。
     */
    private suspend fun <T> awaitRow(what: String, read: suspend () -> T?): T {
        val deadline = System.currentTimeMillis() + 20_000L
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            read()?.let { return it }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(2L)
        }
        throw AssertionError("等不到$what（评分点没把行写进 review_log？）")
    }

    /** 「到期 − 锚点」折成天数：评分后回读主表那一行、拿它当**前向**对照值时用。 */
    private fun plannedDays(anchorAt: Long, dueAt: Long): Double = (dueAt - anchorAt) / dayMs.toDouble()

    /** 只读回本用例这一张卡的那一行（`review_log` append-only、同库跨用例留存，故按 cardId 取） */
    private suspend fun logRow(kind: String, cardId: Long): ReviewLog? =
        db.reviewLogDao().getAllSince().singleOrNull { it.cardKind == kind && it.cardId == cardId }

    // ── ① 背词路径：StudyViewModel.gradeCard ────────────────────────────────

    @Test
    fun `背词评分落的是评分前那份计划间隔，不是刚排出去的间隔`() = runBlocking {
        // 库每个用例全新（见 [ScheduledDaysWriterTestApp]）：队列里只有这一张卡，
        // gradeCard 打的就是 session.current，不必赌它排第几
        val now = System.currentTimeMillis()
        val lastReviewAt = now - 7L * dayMs      // elapsed ≈ 7 天（真实 now，几毫秒误差）
        val dueAt = lastReviewAt + 5L * dayMs    // 前天就到期了：评分前的计划间隔 = 5 天 = 后向期望值
        val wordId = db.wordDao().insert(
            Word(
                uuid = "t25-word-$now",
                word = "apple",
                meaning = "苹果",
                example = "an apple a day",
                status = Word.STATUS_LEARNING,
                nextReviewAt = dueAt,
                halfLifeDays = 5.0,
                difficulty = 3.0,
                lastReviewAt = lastReviewAt,
                totalReviews = 3,
                createdAt = now - 40L * dayMs,
                fsrsStability = 8.0,
                fsrsDifficulty = 5.0,
                fsrsState = 2,
                kernel = "FSRS",
            ),
        )
        val vm = StudyViewModel(app)
        vm.startCardSession()
        awaitRow("背词会话把这条例到期词排进队列") {
            vm.session.value?.queue?.firstOrNull { it.id == wordId }
        }

        vm.gradeCard(ReviewGrade.RECALL, reactionMs = 700L, conf = null)
        val row = awaitRow("背词评分的 review_log 行") { logRow(ReviewLog.KIND_WORD, wordId) }

        // 后向口径：这一行记的是「促成这次复习的那段计划间隔」= 评分前行上的那 5 天
        assertEquals(
            "scheduled_days 必须是评分**前**那份排期（5 天），不是这次刚排出去的间隔",
            5.0, row.scheduledDays, 1e-9,
        )
        // 同一行里两列同锚点（都量「距上次评分」）：这才叫没串行，也才减得出「迟到了几天」
        assertEquals("elapsed_days 仍是距上次评分的真实经过（≈7 天）", 7.0, row.elapsedDays, 0.01)
        assertTrue(
            "这次是迟到的复习：同一行里 scheduled(5) 应小于 elapsed(7)",
            row.scheduledDays < row.elapsedDays,
        )

        // 牙齿：旧接线写的是「这次刚排出去的间隔」。它就落在刚被 applyReview 改过的词行上，
        // 回读回来比对 —— 两个数不同才说明本用例真能分辨新旧接线。
        val after = db.wordDao().getAll().first { it.id == wordId }
        val justScheduledDays = plannedDays(after.lastReviewAt!!, after.nextReviewAt)
        assertNotEquals(
            "落库值不能再等于「这次刚排出去的间隔」（=$justScheduledDays 天），否则口径又串回前向",
            justScheduledDays, row.scheduledDays, 0.25,
        )
        // 版本戳：新写的行必须是后向口径那一版（v2）；老库里的 v1 行才是混合口径、回放时要认出来
        assertEquals("v2 = scheduled_days 起统一为后向口径", 2, row.logSchemaVer)
        assertEquals(ReviewLog.CURRENT_SCHEMA_VER, row.logSchemaVer)
    }

    @Test
    fun `首评没有上次排期所以记 0 与 SynthLog 首评行同口径`() = runBlocking {
        db.wordDao().deleteAll()
        val now = System.currentTimeMillis()
        val wordId = db.wordDao().insert(
            Word(
                uuid = "t25-fresh-$now",
                word = "banana",
                meaning = "香蕉",
                example = "",
                status = Word.STATUS_NEW,
                nextReviewAt = 0L,   // 从没排过期（CardSessionQueue.isFresh 那一支）
                lastReviewAt = null, // 首评：没有「上次」可言
                totalReviews = 0,
                createdAt = now - 2L * dayMs,
                fsrsStability = null,
                fsrsDifficulty = null,
            ),
        )
        val vm = StudyViewModel(app)
        vm.startCardSession()
        awaitRow("新词补位进会话队列") { vm.session.value?.queue?.firstOrNull { it.id == wordId } }
        vm.gradeCard(ReviewGrade.RECALL, reactionMs = 500L, conf = null)
        val row = awaitRow("首评的 review_log 行") { logRow(ReviewLog.KIND_WORD, wordId) }

        // 首评之前没有任何排期 → 0（不是「刚排出去的那个间隔」，更不是负数）
        assertEquals("首评没有上次排期，后向口径记 0", 0.0, row.scheduledDays, 1e-9)
        assertEquals("首评 state=New0", 0, row.state)
        assertEquals("elapsed 仍按 createdAt 锚点算（≈2 天）", 2.0, row.elapsedDays, 0.01)

        val after = db.wordDao().getAll().first { it.id == wordId }
        assertTrue(
            "这次评分确实排出了一个 >0 的间隔（=${plannedDays(after.lastReviewAt!!, after.nextReviewAt)} 天），" +
                "而首评行没把它写进 scheduled_days",
            plannedDays(after.lastReviewAt!!, after.nextReviewAt) > 0.0,
        )
    }

    // ── ② 书摘路径：BookViewModel.gradeExcerptReview ────────────────────────

    @Test
    fun `书摘自评落的是评分前那份计划间隔，不再记 0`() = runBlocking {
        val now = System.currentTimeMillis()
        val bookId = db.bookDao().insert(
            Book(uuid = "t25-book-$now", title = "某书", author = "某作者", totalPages = 200),
        )
        val createdAt = now - 20L * dayMs
        // 上一次自评把到期排到了「建摘后第 9 天」；书摘行没有 last_review_at，elapsed 的锚点本来就是
        // createdAt（见 ExcerptReview 的 KDoc），所以计划间隔也用同一个锚点 → 期望 9.0
        val excerptId = db.bookDao().insertExcerpt(
            Excerpt(
                uuid = "t25-excerpt-$now",
                bookId = bookId,
                content = "书摘正文",
                createdAt = createdAt,
                nextReviewAt = createdAt + 9L * dayMs,
                reviewCount = 2,
                stability = 12.0,
            ),
        )

        BookViewModel(app).gradeExcerptReview(excerptId, ReviewGrade.RECALL, conf = null)
        val row = awaitRow("书摘自评的 review_log 行") { logRow(ReviewLog.KIND_EXCERPT, excerptId) }

        // 旧接线在这一列写死 0.0（间隔没从 ExcerptReview 回传）；新接线必须回传评分前那份计划间隔
        assertEquals(
            "scheduled_days = 评分前那份到期偏移（建摘后 9 天），与 elapsed 同锚点",
            9.0, row.scheduledDays, 1e-9,
        )
        assertEquals("elapsed = 距建摘 ≈20 天", 20.0, row.elapsedDays, 0.01)
        assertTrue(
            "迟到了 11 天：scheduled(9) 必须显著小于 elapsed(20)，且不能再是 0.0",
            row.scheduledDays > 0.0 && row.scheduledDays < row.elapsedDays,
        )
        assertTrue(
            "scheduled − elapsed 就是「还剩几天到期」（这里 −11），两列必须同锚点才减得动",
            row.elapsedDays - row.scheduledDays > 10.0,
        )

        // 牙齿：这次评分刚排出去的到期日（回写在书摘行上）不是本行该记的那个数
        val after = db.bookDao().getExcerpt(excerptId)!!
        val nextPlannedDays = plannedDays(createdAt, after.nextReviewAt)
        assertNotEquals(
            "落库值不能再等于「这次刚排出去的到期偏移」（=$nextPlannedDays 天）",
            nextPlannedDays, row.scheduledDays, 0.25,
        )
        assertEquals("v2 = scheduled_days 起统一为后向口径", 2, row.logSchemaVer)
    }

    @Test
    fun `没排过期的书摘首评记 0 不造数`() = runBlocking {
        val now = System.currentTimeMillis()
        val bookId = db.bookDao().insert(
            Book(uuid = "t25-book2-$now", title = "另一书", author = "另一作者", totalPages = 50),
        )
        val excerptId = db.bookDao().insertExcerpt(
            Excerpt(
                uuid = "t25-excerpt2-$now",
                bookId = bookId,
                content = "另一条书摘",
                createdAt = now - 3L * dayMs,
                nextReviewAt = 0L,  // 0 = 未启用哨兵：从没排过期，没有「上次计划间隔」可言
                reviewCount = 0,
                stability = null,
            ),
        )
        BookViewModel(app).gradeExcerptReview(excerptId, ReviewGrade.RECALL, conf = null)
        val row = awaitRow("未排期书摘的 review_log 行") { logRow(ReviewLog.KIND_EXCERPT, excerptId) }

        assertEquals("next_review_at=0 是哨兵，不能折算成负数或凭空间隔", 0.0, row.scheduledDays, 1e-9)
        assertEquals("首评 state=New0", 0, row.state)

        val after = db.bookDao().getExcerpt(excerptId)!!
        assertTrue(
            "这次评分确实排出了到期（next_review_at=${after.nextReviewAt} > createdAt=${after.createdAt}），" +
                "而首评行没把它写进 scheduled_days",
            after.nextReviewAt > after.createdAt,
        )
    }
}
