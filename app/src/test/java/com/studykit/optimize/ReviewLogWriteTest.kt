package com.studykit.optimize

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.studykit.data.AppDatabase
import com.studykit.data.entity.ReviewLog
import com.studykit.data.memory.CardState
import com.studykit.data.memory.Confidence
import com.studykit.data.memory.KernelRating
import com.studykit.data.memory.KernelState
import com.studykit.data.memory.toStorageInt
import com.studykit.data.repository.ReviewLogRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v2.9 计划 Task 6 —— 评分点双写 `review_log` 的写入侧证据（v2.9 那四处 + v3.0-B T12 的第五处）。
 *
 * 为什么测**仓储级 helper**（[ReviewLogRepository.recordGradedReview]）而不是那几个 ViewModel：
 * 本仓库既有测试文化一致回避直接驱动 `AndroidViewModel`（`KernelWiringTest`/`QuizConfidenceWiringTest`
 * 都写明"那是 Room + Robolectric 才跑得动，改测纯函数/仓储"）。五处评分点（word/practice/mistake/
 * excerpt，加上套卷交卷也落进 practice）最终都收敛成对同一个 helper 的一次调用，字段映射
 * （rating/self_conf/state）的**语义**在 helper 这一层钉死；各点"能拿到什么、缺什么用什么默认"在实现说明里逐条登记。
 *
 * 钉三件事：
 * 1) **每评一次分恰多一行**，且 `card_kind` / `origin` / `rating` 与该次评分自洽；四类各验一条。
 * 2) **self_conf 与传入的 [Confidence] 一致**（[Confidence.toStorageInt] = ordinal+1），没采集信心时落 null
 *    —— 拿 0 冒充"肯定不记得"会触发 FSRS 超纠正，必须留空（实体 KDoc 同口径）。
 * 3) **先状态后历史**：日志写在状态写之后。协程里状态写一抛，日志那行根本执行不到 → 库中零行
 *    （与 `applyReview → recordGradedReview`、`grade → insertRedo` 那条纪律同一条，见 [stateThrowsThenNoLogRow]）。
 * 4) **套卷交卷那一行与练习侧同形状**（v3.0-B T12，计划 D8：`KIND_PRACTICE` + `self_conf` 留空 +
 *    `elapsed`/`scheduled`/`duration` 成对 0）—— 见 [套卷交卷那条路径写的行与练习侧同形状]。
 *    第五个写入点（`StudyViewModel.submitMockExam`）本身要起 VM + Room，本文件不新建那套脚手架；
 *    那条路径**已经**有 VM 级证据（评审 F1 那轮补的 `MockSessionResumeWriterTest`：真起
 *    `StudyViewModel` + 内存库，交卷后读回 `review_log` 那一行的 `card_kind`/`rating`/`self_conf`
 *    与会话行的 `state=DONE`）。本文件仍只管 helper 那一层的字段语义。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReviewLogWriteTest {

    private lateinit var db: AppDatabase
    private lateinit var reviewLog: ReviewLogRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        reviewLog = ReviewLogRepository(db.reviewLogDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** 各点都必带的公共缺省（duration/session 在部分站点拿不到，用默认，见类 KDoc 登记） */
    private suspend fun writeWordGood(cardId: Long, conf: Confidence?) = reviewLog.recordGradedReview(
        cardKind = ReviewLog.KIND_WORD,
        cardId = cardId,
        rating = KernelRating.GOOD.ordinal + 1, // RECALL → GOOD → 3
        elapsedDays = 1.5,
        scheduledDays = 4.0,
        state = stateCode(KernelState(stability = 2.0, difficulty = 5.0, cardState = CardState.REVIEW)),
        durationMs = 800L,
        origin = "word",
        selfConf = conf?.toStorageInt(),
        sessionId = "sess-word",
    )

    @Test
    fun `背词评一次 GOOD 恰多一行且字段自洽`() = runBlocking {
        writeWordGood(cardId = 11L, conf = Confidence.SURE)
        val rows = db.reviewLogDao().getAllSince()
        assertEquals("一次评分只产一行", 1, rows.size)
        val row = rows.single()
        assertEquals(ReviewLog.KIND_WORD, row.cardKind)
        assertEquals("word", row.origin)
        assertEquals(11L, row.cardId)
        assertEquals(3, row.rating)
        assertEquals(1.5, row.elapsedDays, 1e-9)
        assertEquals(4.0, row.scheduledDays, 1e-9)
        // REVIEW 卡评分前状态 → 序号+1 = 2
        assertEquals(2, row.state)
        assertEquals(800L, row.durationMs)
        // self_conf 与传入 Confidence 完全一致
        assertEquals(Confidence.SURE.toStorageInt(), row.selfConf)
        assertEquals("sess-word", row.sessionId)
        assertEquals(0, row.isMakeup)
        assertEquals(ReviewLog.CURRENT_SCHEMA_VER, row.logSchemaVer)
    }

    @Test
    fun `没采集信心时 self_conf 落 NULL 不冒充`() = runBlocking {
        writeWordGood(cardId = 12L, conf = null)
        val row = db.reviewLogDao().getAllSince().single()
        assertNull("没选信心必须留空，不能拿 0 冒充『肯定不记得』", row.selfConf)
    }

    @Test
    fun `四类评分点各写一行 kind 与 origin 一一对应`() = runBlocking {
        reviewLog.recordGradedReview(
            ReviewLog.KIND_WORD, 1L, 3, 1.0, 2.0, 2, 0L, "word", 3, "s",
        )
        reviewLog.recordGradedReview(
            ReviewLog.KIND_PRACTICE, 2L, 3, 0.0, 0.0, 0, 0L, "practice", null, "s",
        )
        reviewLog.recordGradedReview(
            ReviewLog.KIND_MISTAKE, 3L, 1, 0.5, 1.0, 0, 0L, "mistake", 1, "s",
        )
        reviewLog.recordGradedReview(
            ReviewLog.KIND_EXCERPT, 4L, 3, 6.0, 3.0, 2, 0L, "excerpt", null, "s",
        )

        val rows = db.reviewLogDao().getAllSince()
        assertEquals(4, rows.size)
        // 按 (card_kind, card_id, ts) 升序读回：字母序 excerpt < mistake < practice < word
        assertEquals(
            listOf(
                ReviewLog.KIND_EXCERPT,
                ReviewLog.KIND_MISTAKE,
                ReviewLog.KIND_PRACTICE,
                ReviewLog.KIND_WORD,
            ),
            rows.map { it.cardKind },
        )
        // origin 与 card_kind 一一对应（同 kind 下区分写入路径的位）
        assertEquals(rows.map { it.cardKind }, rows.map { it.origin })
        // rating 各自自洽：practice 判对 → GOOD(3)，mistake 忘记 → AGAIN(1)
        assertEquals(1, rows.first { it.cardKind == ReviewLog.KIND_MISTAKE }.rating)
        assertEquals(3, rows.first { it.cardKind == ReviewLog.KIND_PRACTICE }.rating)
    }

    /**
     * v3.0-B T12（计划 D8）—— **套卷交卷**那条路径往 `review_log` 写的那一行，形状必须与练习侧
     * 逐列一致：`card_kind=practice`、`origin=practice`、`self_conf` 留空（交卷时没有信心采集，
     * 拿 0 冒充"肯定不记得"会去触发 FSRS 超纠正）、`elapsed` / `scheduled` / `duration` **成对** 0
     * （模考不进 FSRS 状态机、没有逐卡 Δt，只把其中一列填 0 会被 LogPacker 读成"另一列的间隔存在"）、
     * `state=0`（New）、`rating` 由对错映射（判对 GOOD3 / 判错 AGAIN1）。
     *
     * 这条**只钉 helper 那一层的形状**，不声称测了 `StudyViewModel.submitMockExam`：那要起 ViewModel
     * + Room 的脚手架，本任务不新建（缺口在 task-10-12 报告里点名，由真机清单覆盖）。
     * 与 [四类评分点各写一行 kind 与 origin 一一对应] 的分工是：那条证明四类写得出，这条证明
     * 模考这条**新写路径所依赖的那一组默认值**落得下来。
     */
    @Test
    fun `套卷交卷那条路径写的行与练习侧同形状`() = runBlocking {
        reviewLog.recordGradedReview(
            cardKind = ReviewLog.KIND_PRACTICE,
            cardId = 77L,
            rating = KernelRating.AGAIN.ordinal + 1,     // 判错 → AGAIN(1)，与 selectOption 同一映射
            elapsedDays = 0.0,
            scheduledDays = 0.0,
            state = 0,
            durationMs = 0L,
            origin = "practice",
            selfConf = null,
            sessionId = "sess-mock",
        )
        val row = db.reviewLogDao().getAllSince().single { it.cardId == 77L }
        assertEquals(ReviewLog.KIND_PRACTICE, row.cardKind)
        assertEquals("practice", row.origin)
        assertEquals(1, row.rating)
        assertNull("交卷没有信心采集，self_conf 必须留空", row.selfConf)
        assertEquals("三列必须成对为 0", 0.0, row.elapsedDays, 1e-9)
        assertEquals("三列必须成对为 0", 0.0, row.scheduledDays, 1e-9)
        assertEquals("三列必须成对为 0", 0L, row.durationMs)
        assertEquals(0, row.state)
        assertEquals("sess-mock", row.sessionId)
        assertEquals(ReviewLog.CURRENT_SCHEMA_VER, row.logSchemaVer)
    }

    /**
     * 先状态后历史：把"状态写"与"日志写"串在同一协程里（正是四个 ViewModel 的调用序），
     * 状态写抛异常时协程根本走不到日志那一行 → review_log 零行。反过来若把日志排在状态写之前，
     * 这条会留下"有一次评分、状态却没落库"的脏样本——那是在污染以后的校准（Task 7 LogPacker）。
     */
    @Test
    fun `状态写失败则不写日志行`() = runBlocking {
        val dao = db.reviewLogDao()
        val boom = runCatching {
            // ① 站点里"先写状态"那一步（此处用会抛的桩模拟 applyReview/update/updateExcerpt/submitAnswer）
            error("state write failed")
            // ② 只有状态写成功才会执行到这里
            reviewLog.recordGradedReview(
                ReviewLog.KIND_WORD, 99L, 3, 1.0, 2.0, 0, 0L, "word", null, "s",
            )
        }.isFailure
        assertTrue("状态写应确实抛错", boom)
        assertEquals("状态写失败时不得留下日志行", 0, dao.getAllSince().size)
    }

    /** 与实现里同一套 state 编码：稳定度为空 → New(0)，否则 CardState 序号+1（对齐 0New/1Learn/2Review/3Relearn） */
    private fun stateCode(before: KernelState): Int =
        if (before.stability == null) 0 else before.cardState.ordinal + 1
}
