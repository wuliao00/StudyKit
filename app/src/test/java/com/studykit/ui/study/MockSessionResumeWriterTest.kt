package com.studykit.ui.study

import android.os.Looper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.studykit.AppContainer
import com.studykit.StudyKitApp
import com.studykit.data.AppDatabase
import com.studykit.data.entity.MockSession
import com.studykit.data.entity.Question
import com.studykit.data.entity.ReviewLog
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 只为本文件那两条用例把容器换成一次性内存库（覆写点见 [StudyKitApp.createContainer]）：
 * `mock_sessions` 那一行与 `review_log` 都是要**落库**的东西，库必须每个用例全新。
 */
class MockExamResumeTestApp : StudyKitApp() {
    override fun createContainer(): AppContainer = AppContainer(
        Room.inMemoryDatabaseBuilder(this, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build(),
    )
}

/**
 * 评审 F1 —— 跨进程恢复时「暂停即停表」那一段，必须按**库里那一列**（`mock_sessions.paused_at`）
 * 补回来，而不是按恢复的那一刻当瞬时暂停。
 *
 * ## 为什么这一份要真起 ViewModel + Room
 * 纯层那三条硬闸（`MockSessionMachineTest`）钉的是算术；这个缺陷恰恰出在**调用点拿哪个数当起点**
 * （旧代码：`mockPausedAt = now` ⇒ `deadlineAfterResume(now, now, deadline)` ⇒ 补 0 且夹到此刻）。
 * 只测算术的话，调用点换回 `now` 测试照样绿 —— 那种测试不是证据。这里把真链路跑一遍：
 * 库里插一行「一小时前的暂停」→ [StudyViewModel.loadActiveMockSession] → [StudyViewModel.resumeMockExam]
 * → 断言**内存状态与落库那一行**的截止时刻。
 *
 * ## 时钟
 * 暂停起点按真实 now 往前拨一小时（VM 里用的是 `System.currentTimeMillis()`，测试拨不了它的钟），
 * 于是"补了多久"只能带几秒容差读；能逐字精确的两件事（原截止时刻、暂停起点）都另外断言。
 *
 * ## 第二条用例钉的是界面守卫
 * 恢复那一刻就已经过截止的卷子（库里那一列为空 = 崩在作答途中的 RUN 行）：按下「继续作答」
 * **不许**无声判分。这里钉的是它走了 `autoSubmitted = true` 那条既有路径 —— 成绩页顶上因此会印
 * 「时间到，已自动交卷」，而不是把用户丢回一张第一个 tick 就自杀的考卷。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = MockExamResumeTestApp::class)
class MockSessionResumeWriterTest {

    private val app get() = ApplicationProvider.getApplicationContext<StudyKitApp>()
    private val db get() = app.container.database
    private val hourMs = TimeUnit.MINUTES.toMillis(60)
    private val minMs = TimeUnit.SECONDS.toMillis(60)

    private fun question(id: Long) = Question(
        uuid = "resume-q$id",
        subject = "数学",
        stem = "题干$id",
        optionsJson = JSONArray().apply { put("甲"); put("乙"); put("丙"); put("丁") }.toString(),
        answerIndex = 1,
        explanation = "解析",
    )

    /**
     * 恢复/继续那两步都是 `viewModelScope.launch` + Room suspend：主线程上反复 idle 主 looper
     * 让它们跑完，读到目标状态即返回，超时才失败（口径同 `ReviewLogScheduledDaysWriterTest`）。
     */
    private suspend fun awaitTrue(what: String, read: suspend () -> Boolean) {
        val stopAt = System.currentTimeMillis() + 20_000L
        while (System.currentTimeMillis() < stopAt) {
            shadowOf(Looper.getMainLooper()).idle()
            if (read()) return
            Thread.sleep(2L)
        }
        throw AssertionError("等不到$what")
    }

    private suspend fun awaitRow(what: String, read: suspend () -> MockSession?): MockSession {
        val stopAt = System.currentTimeMillis() + 20_000L
        while (System.currentTimeMillis() < stopAt) {
            shadowOf(Looper.getMainLooper()).idle()
            read()?.let { return it }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(2L)
        }
        throw AssertionError("等不到$what")
    }

    /**
     * ⛔ 一小时的停表必须整段补回来。
     *
     * 场景（全部字面量）：45 分钟限时 → 开卷那一刻就按下暂停（`paused_at` = 一小时前）→
     * 一小时后回到页面 → 按下「继续作答」。补对了：截止时刻正好往后推一小时、手里仍然握着
     * 完整 45 分钟。补错了（旧实现）：截止被夹到"现在"，`submitted` 立刻为真 —— 那正是
     * 「继续作答」把整卷悄悄判掉的那次事故。
     */
    @Test
    fun `硬闸 暂停一小时后按下继续，那卷的截止时刻正好晚一小时`() = runBlocking {
        val qid = db.questionDao().insert(question(1))
        val now = System.currentTimeMillis()
        val pausedAt = now - hourMs                     // 库里那一列：一小时前停的表
        val deadline = pausedAt + 45L * minMs           // 原截止时刻（暂停时手里还有 45 分钟）
        val id = db.mockSessionDao().insert(
            MockSession(
                subject = "数学",
                examName = "期末考",
                questionIds = "[$qid]",
                answers = "{}",
                cursor = 0,
                startTs = pausedAt,
                deadlineTs = deadline,
                pausedAt = pausedAt,
                limitMin = 45,
                state = MockSession.STATE_PAUSED,
                createdAt = pausedAt,
            ),
        )

        val vm = StudyViewModel(app)
        vm.loadActiveMockSession()
        awaitTrue("恢复提示那一屏（暂停态的卷）") {
            val s = vm.mockExam.value
            s.paused && s.sessionId == id && s.questions.map { it.id } == listOf(qid)
        }
        val restored = vm.mockExam.value
        // 进页面**不许**动截止时刻：用户可能就在这张卡上坐着，推移发生在按下继续那一刻
        assertEquals("恢复时不改库里的截止时刻", deadline, restored.deadlineTs)
        assertEquals("暂停的表也不许在恢复时自己跑起来（不补时、不判卷）", false, restored.submitted)
        assertTrue(
            "暂停起点从库里那一列取回，而不是取恢复的此刻",
            (restored.pausedAtTs ?: -1L) in (pausedAt - 2_000L)..(pausedAt + 2_000L),
        )

        vm.resumeMockExam()
        awaitTrue("按下继续之后回到作答态") { !vm.mockExam.value.paused }
        val after = vm.mockExam.value
        val shifted = after.deadlineTs ?: error("限时卷恢复后必须有截止时刻")
        assertFalse("手里还剩 45 分钟，绝不该当场交卷", after.submitted)
        assertTrue(
            "截止时刻要比原来晚整段暂停（一小时），不是夹到此刻：$shifted vs $deadline",
            shifted in (deadline + hourMs - 2_000L)..(deadline + hourMs + 2_000L),
        )
        assertTrue(
            "按下继续后还剩整整 45 分钟可答",
            (shifted - System.currentTimeMillis()) in 43L * minMs..47L * minMs,
        )

        // 按下继续之后：内存里那份暂停起点必须清空，否则下一次恢复会再把同一段补第二遍
        assertNull("按下继续后暂停起点清空，同一段暂停不能补两次", after.pausedAtTs)
    }

    /**
     * ⛔ 恢复那一刻就已经过截止的卷子：「继续作答」不许无声判分。
     *
     * 这一行的形状是"崩在作答途中"（RUN、`paused_at` 为空）：钟本来就一直在跑，没有可信的暂停
     * 起点 ⇒ 一分不白送（[pausedAnchorForRestore] 退回此刻），于是截止时刻已在身后。旧行为是把
     * 用户放回 RUN，第一个 tick 自动交卷 —— 屏幕上什么都没解释。新行为：按下继续即按**时间到**
     * 那条既有路径交卷（`autoSubmitted = true` ⇒ 成绩页顶上那句「时间到，已自动交卷」）。
     *
     * 顺带钉两件事：① 交卷把这一行定格成 DONE 且**清空 `paused_at`**（R8 那一步，同步落库）；
     * ② 套卷答过的那一题确实进了 `review_log`（第五个评分点的 VM 级证据，`ReviewLogWriteTest`
     * 的仓储级用例之外）。
     */
    @Test
    fun `硬闸 恢复时时间已经跑完，按下继续按时间到交卷而非悄悄判分`() = runBlocking {
        val qid = db.questionDao().insert(question(2))
        val now = System.currentTimeMillis()
        val deadline = now - minMs                      // 已经过截止一分钟
        val id = db.mockSessionDao().insert(
            MockSession(
                subject = "数学",
                examName = "期末考",
                questionIds = "[$qid]",
                answers = "{\"$qid\":1}",               // 答过一题，且选的正是正确项（answerIndex=1）
                cursor = 0,
                startTs = deadline - 44L * minMs,
                deadlineTs = deadline,
                pausedAt = null,                        // 崩在作答途中：没有可信暂停起点
                limitMin = 45,
                state = MockSession.STATE_RUN,
                createdAt = deadline - 44L * minMs,
            ),
        )

        val vm = StudyViewModel(app)
        vm.loadActiveMockSession()
        awaitTrue("恢复提示那一屏") { vm.mockExam.value.paused && vm.mockExam.value.sessionId == id }
        val restored = vm.mockExam.value
        // 起点不可信 ⇒ 锚点就是恢复那一刻：一分不白送（这一卷于是已经在截止时间之后）
        assertTrue(
            "库里那一列为空 ⇒ 暂停起点只能取此刻，不能拿开卷时间去白补一段",
            (restored.pausedAtTs ?: -1L) >= restored.nowTs - 2_000L,
        )

        vm.resumeMockExam()
        awaitTrue("按下继续之后这卷已经交掉") { vm.mockExam.value.submitted }
        val after = vm.mockExam.value
        assertTrue("时间到那条路径：autoSubmitted 必须为真，成绩页才有那句说明", after.autoSubmitted)
        assertFalse("交卷后不许停在暂停外观（那张卡还在屏幕上就是假象）", after.paused)
        assertEquals("答过的那一题判了，未答的零题", 1, after.gradedCount)
        assertEquals("未答的题进待批改而不是判成错", emptyList<Long>(), after.toGrade)

        val row = awaitRow("交卷定格的会话行") {
            db.mockSessionDao().getById(id)?.takeIf { it.state == MockSession.STATE_DONE }
        }
        assertNull("交卷之后必须清空暂停起点：不能让下一次恢复以为还在暂停", row.pausedAt)
        assertEquals("交卷把作答定格进 answers", mapOf(qid to 1), decodeAnswers(row.answers))

        // ② 第五个评分点真的落库：那一行的 card_kind / rating 与练习侧同一口径（字面量）
        awaitTrue("套卷交卷写进 review_log 的那一行") {
            db.reviewLogDao().getAllSince().any { it.cardKind == ReviewLog.KIND_PRACTICE && it.cardId == qid }
        }
        val log = db.reviewLogDao().getAllSince().first { it.cardId == qid }
        assertEquals("practice 这一种卡", "practice", log.cardKind)
        assertEquals("判对 = GOOD 档，写成 ordinal+1 = 3", 3, log.rating)
        assertNull("模考不采集信心：self_conf 留空，不拿 0 冒充", log.selfConf)
    }
}
