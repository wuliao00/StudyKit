package com.studykit.worker

import com.studykit.data.dao.ScheduledMemoryRow
import com.studykit.data.memory.AlertKind
import com.studykit.data.memory.DueForecast
import com.studykit.data.memory.ForecastCfg
import com.studykit.data.memory.FsrsModel
import com.studykit.data.memory.ReminderDecision
import com.studykit.data.memory.ReminderPolicy
import com.studykit.ui.stats.ForecastCards
import com.studykit.ui.stats.toForecastCards
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Task 4（v3.0-A）接线的可测部分：提醒文案由 [ReminderPolicy] 选、周期相位对齐历史复习的众数小时。
 *
 * **这里没有 WorkManager 测试量具**，而且是故意的：本仓无 androidTest、CI 只跑 `testDebugUnitTest`
 * （Robolectric），给 `doWork()` 搭一套 TestObserver 换来的只是「把三行装配再演一遍」。所以接线拆成
 * 三个纯函数分别钉住 —— 众数小时 [sessionHourMode]、对齐延迟 [delayMillisToHour]、
 * 决策→通知那两行字的取舍 [reminderCopy]。`doWork()` 里剩下的只有取数与顺序，没有判断。
 *
 * [reminderCopy] 断言的是 [com.studykit.data.memory.ReminderDecision.headline] /
 * [com.studykit.data.memory.ReminderDecision.body] **两个字段各自落到哪一行**，
 * 不是把它们拼成一个字符串再比（拼串是断言接缝，产品里没有那个形状）。
 * 它还收一个 `nothingDueToday`：旧的那道「两类皆 0 ⇒ 什么都不发」闸现在按档位放行
 * （跌破 / 堆积照发，只有 DUE_NOW 留在闸后），所以这一组断言同时是"一刀切被装回去"的哨兵。
 *
 * 夹具一律 [ZoneOffset.UTC] 并**显式传 now**，不依赖测试机的默认时区。
 */
class ReminderPolicyWiringTest {

    private val utc = ZoneOffset.UTC
    private val today = LocalDate.of(2026, 10, 5)
    private val nowMs = today.atTime(8, 0).toInstant(utc).toEpochMilli()
    private val dayMs: Long = TimeUnit.DAYS.toMillis(1)

    private fun stampAt(hour: Int, minute: Int = 30, date: LocalDate = today): Long =
        date.atTime(hour, minute).toInstant(utc).toEpochMilli()

    // ── 众数小时：相位对齐的原料 ────────────────────────────────

    @Test
    fun `众数小时取出现最多的整点`() {
        val stamps = listOf(21, 21, 21, 9, 9, 7).map { stampAt(it) }
        assertEquals(21, sessionHourMode(stamps, utc))
    }

    /**
     * 并列取**较晚**的那个整点（用户更可能在晚间复习，晚间那一档也更该被当成提醒时刻）。
     *
     * 这条不是可选语义：`maxByOrNull { count }` 在并列时返回**第一个**，而 `groupingBy` 的
     * 迭代序是首次出现序 —— 把 9 排在前面就取到 9。所以实现必须显式按 (次数, 整点) 双键取大，
     * 这条测试钉的就是它有没有偷懒读迭代顺序。
     */
    @Test
    fun `并列时取较晚的整点`() {
        val stamps = listOf(9, 9, 21, 21).map { stampAt(it) }
        assertEquals(21, sessionHourMode(stamps, utc))
    }

    @Test
    fun `没有历史时返回 null 由调用方退回固定周期`() {
        assertNull(sessionHourMode(emptyList(), utc))
    }

    /**
     * 整点必须按**传入的时区**折算。写死 UTC 或写死 `ZoneId.systemDefault()` 都能让上面几条绿，
     * 因为测试机恰好就在那个区；这里拿同一个瞬间喂两个区，逼它真的用上参数。
     * 21:30 UTC = 次日 05:30（UTC+8），众数小时跟着换一天里的另一档。
     */
    @Test
    fun `整点按传入时区折算而不是写死`() {
        val stamps = listOf(21, 21, 21, 9, 9, 7).map { stampAt(it) }
        assertEquals(21, sessionHourMode(stamps, ZoneOffset.UTC))
        assertEquals(5, sessionHourMode(stamps, ZoneId.of("Asia/Shanghai")))
    }

    // ── 对齐延迟：下一个众数小时 ────────────────────────────────

    @Test
    fun `对齐延迟指向下一个众数小时`() {
        val delay = delayMillisToHour(hour = 21, nowMs = nowMs, zone = utc)
        assertEquals(today.atTime(21, 0).toInstant(utc).toEpochMilli(), nowMs + delay)
    }

    @Test
    fun `众数小时已过则对齐到明天同一小时`() {
        val late = today.atTime(22, 0).toInstant(utc).toEpochMilli()
        val delay = delayMillisToHour(hour = 21, nowMs = late, zone = utc)
        assertEquals(today.plusDays(1).atTime(21, 0).toInstant(utc).toEpochMilli(), late + delay)
    }

    /**
     * 此刻正好落在众数小时上也算"已过"，推到明天：延迟 0 等于任务入队当场就跑，
     * 而这条延迟的用途是"把提醒挪到用户真的在复习的那个钟点"，不是"立刻再发一条"。
     *
     * 唯一的断言就是 `delay == 一天`（生产函数的返回值），它同时覆盖"推到明天"和"不是零延迟"两件事。
     * 这里原有第二条断言拿 `exactly + dayMs` 比 `today.plusDays(1).atTime(21, 0)`——两边都是夹具自己
     * 算出来的本地量，一个字的生产代码都没经过，因此在 UTC 夹具下恒真、永远不会红，已删。
     * （"延迟落在正确的绝对时刻上"由上面两条 `nowMs + delay == 目标整点` 钉住，形状是对的。）
     */
    @Test
    fun `此刻正是众数小时也推到明天而不是零延迟`() {
        val exactly = today.atTime(21, 0).toInstant(utc).toEpochMilli()
        val delay = delayMillisToHour(hour = 21, nowMs = exactly, zone = utc)
        assertEquals(dayMs, delay)
    }

    // ── 决策 → 通知那两行字 ─────────────────────────────────────

    @Test
    fun `决策在场时标题与正文逐字段取自它`() {
        val decision = ReminderDecision(
            AlertKind.LOAD_SPIKE, "明天会堆到 42 项", "今天先清一点。",
        )
        val copy = reminderCopy(decision, fallbackText = "5 个单词待复习，打开 StudyKit 开始复习", nothingDueToday = false)!!
        assertEquals("明天会堆到 42 项", copy.title)
        assertEquals("今天先清一点。", copy.text)
    }

    /** 决策为 null（今天练过了 / 没有可信的卡 / 三档都不成立）⇒ 老文案原样出去，标题仍是那句「今日复习」。 */
    @Test
    fun `没有决策时退回既有直陈文案`() {
        val copy = reminderCopy(null, fallbackText = "3 道错题到期，打开 StudyKit 开始复习", nothingDueToday = false)!!
        assertEquals(FALLBACK_TITLE, copy.title)
        assertEquals("3 道错题到期，打开 StudyKit 开始复习", copy.text)
    }

    /** 兜底文案为 null（两类到期皆 0）且没有决策 ⇒ 不发通知，这条闸与改动前一致。 */
    @Test
    fun `决策与兜底都没有时不发通知`() {
        assertNull(reminderCopy(null, fallbackText = null, nothingDueToday = true))
    }

    // ── 零到期日的按档位放行（这组是"一刀切闸被重新装回去"的专用哨兵）──────────
    //
    // 上一条 commit 在 `doWork()` 里保留的旧闸是「两类到期皆为 0 ⇒ 什么都不发」，它打在**所有**档位上。
    // 现在的形状是：那个事实以 `nothingDueToday` 传进 [reminderCopy]，只有 DUE_NOW 留在闸后。
    // 谁把一刀切装回去（无论装在 `reminderCopy` 里还是装在 `doWork()` 里但没同时改这里），
    // 下面前两条立刻红。

    /**
     * 「今天没东西到期、明天堆积」是 [AlertKind.LOAD_SPIKE] 的靶心情形——不是边角案例。
     * 零到期日把它静音等于三档里唯一为闲置用户设计的那档永远不开口。
     */
    @Test
    fun `零到期日的堆积档照发`() {
        val decision = ReminderDecision(AlertKind.LOAD_SPIKE, "明天会堆到 42 项", "今天先清一点。")
        val copy = reminderCopy(decision, fallbackText = null, nothingDueToday = true)
        requireNotNull(copy)
        assertEquals("明天会堆到 42 项", copy.title)
        assertEquals("今天先清一点。", copy.text)
    }

    /**
     * [AlertKind.RETENTION_DROP] 同样不能被零到期日打死：跌破判定按 `fsrs_stability` 反解，
     * 而那道闸读的两条 SQL 更窄（书摘压根不在里面），所以「两类皆 0 但确有卡跌破线」是真会发生的状态。
     */
    @Test
    fun `零到期日的跌破档照发`() {
        val decision = ReminderDecision(AlertKind.RETENTION_DROP, "有 3 条记忆已经过了线", "现在回想起来会吃力，补一次能把它拉回去。")
        val copy = reminderCopy(decision, fallbackText = null, nothingDueToday = true)
        requireNotNull(copy)
        assertEquals("有 3 条记忆已经过了线", copy.title)
        assertEquals("现在回想起来会吃力，补一次能把它拉回去。", copy.text)
    }

    /**
     * [AlertKind.DUE_NOW] 按定义是"有东西到期"，零到期日维持旧行为不发——
     * 那句「N 项今天到期」对着两个列表都空的用户是在催一批他们在 App 里找不到的卡。
     */
    @Test
    fun `零到期日的到期档维持不发`() {
        val decision = ReminderDecision(AlertKind.DUE_NOW, "2 项今天到期", "按现在的状态，这些还来得及。")
        assertNull(reminderCopy(decision, fallbackText = null, nothingDueToday = true))
    }

    /** 反向哨兵：闸不许被顺手扩到"有到期也不发"——DUE_NOW 在非零到期日照发。 */
    @Test
    fun `有到期内容时到期档照发`() {
        val decision = ReminderDecision(AlertKind.DUE_NOW, "2 项今天到期", "按现在的状态，这些还来得及。")
        val copy = reminderCopy(decision, fallbackText = "2 个单词待复习，打开 StudyKit 开始复习", nothingDueToday = false)
        requireNotNull(copy)
        assertEquals("2 项今天到期", copy.title)
        assertEquals("按现在的状态，这些还来得及。", copy.text)
    }

    /** 零到期日 + 决策读挂了（decision 为 null）：兜底直陈也是 null，照旧不发——旧行为一字未动。 */
    @Test
    fun `零到期日且没有决策时不发通知`() {
        assertNull(reminderCopy(null, fallbackText = reminderContentText(0, 0, null), nothingDueToday = true))
        assertNull(reminderContentText(0, 0, null))
    }

    /**
     * 端到端的"闲置用户"链路：装配 → 预测 → 决策 → 文案，全程**没有任何东西到期**。
     *
     * 夹具：10 张 S=10 天、已流逝 9 天的词卡（`intervalDays(w, 10, 0.9) ≈ 10.0`，所以今天没到期、
     * 跌破线（第 19.06 天）更远未过），到期事件整批落在明天；`newPerDay = 1` 让其余天的基线只有约 2.5 项。
     * 于是负荷带是「明天 12 项 vs 平时约 3 项」——1.5 倍门槛远超，堆积档成立。
     * 兜底文案用真实的 [reminderContentText] 现算（0/0 ⇒ null），为的是把"这条通知没有任何直陈可退回"
     * 也一并钉住：它只能在堆积档上发出去。
     */
    @Test
    fun `今天无到期但明天堆积时从决策走到文案`() {
        val cards = ForecastCards.of(
            words = List(10) { overdueRow(lastReviewAt = nowMs - 9 * dayMs) },
            mistakes = emptyList(),
            excerpts = emptyList(),
            now = nowMs,
        ).toForecastCards()
        val bands = DueForecast.forecast(
            cards,
            ForecastCfg(horizonDays = 14, retention = 0.9, newPerDay = 1),
            w = FsrsModel.DEFAULT_PARAMS,
        )
        val decision = ReminderPolicy.decide(
            cards = cards, bands = bands, retention = 0.9, todayReviewed = false,
            w = FsrsModel.DEFAULT_PARAMS,
        )
        requireNotNull(decision)
        assertEquals(AlertKind.LOAD_SPIKE, decision.kind)
        // 没有任何东西到期 ⇒ 兜底为 null ⇒ 能不能发出去全看档位放行那一支
        val fallbackText = reminderContentText(mistakeCount = 0, wordCount = 0, retentionPercent = null)
        assertNull(fallbackText)
        val copy = reminderCopy(decision, fallbackText, nothingDueToday = true)
        requireNotNull(copy)
        assertEquals(decision.headline, copy.title)
        assertEquals(decision.body, copy.text)
    }

    /**
     * 端到端（装配 → 预测 → 决策 → 文案）：真正的"第一个生产消费者"形状。
     *
     * 一条 S=10 天、已经拖了 1000 天的词卡：跌破线（`intervalDays(w, 10, 0.85) ≈ 19.06` 天）
     * 早就过了，所以第一档 [AlertKind.RETENTION_DROP] 命中；通知上的两行就是这个决策自己的
     * headline / body，而不是把到期数直陈一遍。
     *
     * `todayReviewed = true` 那一臂顺带钉住"今天练过就不催"这条闸确实接到了 Worker 侧。
     */
    @Test
    fun `装配到文案一路贯通`() {
        val cards = ForecastCards.of(
            words = listOf(overdueRow(lastReviewAt = nowMs - 1_000 * dayMs)),
            mistakes = emptyList(),
            excerpts = emptyList(),
            now = nowMs,
        ).toForecastCards()
        val bands = DueForecast.forecast(
            cards,
            ForecastCfg(horizonDays = 14, retention = 0.9, newPerDay = 20),
            w = FsrsModel.DEFAULT_PARAMS,
        )
        val decision = ReminderPolicy.decide(
            cards = cards,
            bands = bands,
            retention = 0.9,
            todayReviewed = false,
            w = FsrsModel.DEFAULT_PARAMS,
        )
        requireNotNull(decision)
        val copy = reminderCopy(decision, fallbackText = "1 个单词待复习，打开 StudyKit 开始复习", nothingDueToday = false)!!
        assertEquals(AlertKind.RETENTION_DROP, decision.kind)
        assertEquals(decision.headline, copy.title)
        assertEquals(decision.body, copy.text)
        assertNotEquals("1 个单词待复习，打开 StudyKit 开始复习", copy.text)
    }

    @Test
    fun `今天练过之后决策为空于是走兜底文案`() {
        val cards = ForecastCards.of(
            words = listOf(overdueRow(lastReviewAt = nowMs - 1_000 * dayMs)),
            mistakes = emptyList(),
            excerpts = emptyList(),
            now = nowMs,
        ).toForecastCards()
        val bands = DueForecast.forecast(
            cards,
            ForecastCfg(horizonDays = 14, retention = 0.9, newPerDay = 20),
            w = FsrsModel.DEFAULT_PARAMS,
        )
        val decision = ReminderPolicy.decide(
            cards = cards, bands = bands, retention = 0.9, todayReviewed = true,
            w = FsrsModel.DEFAULT_PARAMS,
        )
        assertNull(decision)
        val copy = reminderCopy(decision, fallbackText = "1 个单词待复习，打开 StudyKit 开始复习", nothingDueToday = false)!!
        assertEquals(FALLBACK_TITLE, copy.title)
    }

    /** 只给预测要用的那一列：稳定度 10 天、锚点由入参决定，其余取投影行的合法占位值。 */
    private fun overdueRow(lastReviewAt: Long) = ScheduledMemoryRow(
        nextReviewAt = lastReviewAt,
        halfLifeDays = 5.0,
        lastReviewAt = lastReviewAt,
        createdAt = lastReviewAt,
        fsrsStability = 10.0,
    )
}
