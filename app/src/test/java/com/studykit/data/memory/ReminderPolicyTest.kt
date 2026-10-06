package com.studykit.data.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task 3（v3.0-A）提醒决策的单测：纯 JVM，无 runner，不碰 Room，本文件也不 import Android。
 *
 * ## 数值口径（断言全部手算，不拿被测函数给自己出题）
 * v6 默认参 `w[20] = 0.1542` → `decay = −0.1542`、`factor = 0.9^(1/decay) − 1 = 0.9803464944`，
 * 于是反解 `intervalDays(w, S, R, cap) = min(S / factor · (R^(1/decay) − 1), cap)` 有两条硬事实：
 * 1. **R=0.9 时它恒等于 S**（分子分母是同一个 factor）。但浮点上带 ULP 噪声——S=2 的实测是
 *    `1.9999999999999998`。所以凡"正好到期"的用例都**不**把 `elapsed` 压在 `S` 上，一律留出
 *    ≥0.5 天余量：贴着 0 的断言换台机器（`Math.pow` 的最后一比特）就会从 DUE_NOW 漂成 null。
 * 2. **跌破线在到期线之后**：跌破阈值是 `R − DELTA = 0.85`，比 0.9 更低，要等**更久**才跌破
 *    （S=10 → 到期第 10 天、跌破第 19.0643 天）。计划原文把这条序关系写反了，见下面那条测试的注释。
 *
 * ## 可失败性
 * 三档优先级（跌破 > 堆积 > 到期）逐对钉死：每两个相邻档都有"同时成立"的用例，先后写反立刻红。
 * `SPIKE_RATIO` 的门槛同理给出 1.49×（不算）/ 1.50×（**恰等于门槛，仍不算**）/ 1.51×（算）三点，
 * 中间那点专治 `>` 被写成 `>=`。文案里的条数/项数也被逐字断言，计数错一位就红。
 *
 * ## 上限与到期档的措辞
 * `maxIntervalDays` 与 `retention` 一样是**必填**的政策输入（生产侧交 `Scheduling.maxIntervalDays`），
 * 本文件绝大多数用例显式写 [capUnscoped]（3650 天 = 不额外夹），为的是把"档位判定"与"夹取"两件事分开测；
 * 夹取本身另有两条（`紧上限把到期与跌破判定提前` / `夹取时到期线与跌破线落在同一天`）。
 * 到期档的标题写的是「按现在的状态该复习了」而**不是**「N 项今天到期」：那一批是反解出来的卡集
 * （含书摘、按当前内核与严格度），与 Worker 那道「今日无到期」闸读的两条 SQL 没有等式关系，
 * 措辞不许冒充列表计数（对应断言在 `ReminderPolicyWiringTest`）。
 */
class ReminderPolicyTest {

    private val w = FsrsModel.DEFAULT_PARAMS

    /**
     * 反解上限取「没有政策上下文」那一档（3650 天）：本文件钉的是**档位判定**，
     * 上限本身怎么把卡提前，另有下面那两条专门盯（[紧上限让到期与跌破提前] 等）。
     * 生产调用点交的是 `Scheduling.maxIntervalDays`，不许拿这个数替它。
     */
    private val capUnscoped = ForecastCfg.MAX_INTERVAL_DAYS_UNSCOPED

    private fun card(s: Double, elapsed: Double) = ForecastCard(s, elapsed)

    /** 14 天全平 `v`；`spikeAt` 指定哪一格抬到 3×（用来构造堆积）。 */
    private fun flatBands(v: Double, spikeAt: Int = -1) =
        (0 until 14).map { DayBand(it, if (it == spikeAt) v * 3 else v, 0.0) }

    // ---- 三档优先级：每对相邻关系各钉一条"同时成立"的用例 ----

    @Test
    fun `跌破 R-DELTA 的卡优先于一切`() {
        // S=0.5 已流逝 99 天：跌破线在 0.9532 天、到期线在 0.5 天，双双越界；bands 全平无堆积。
        val d = ReminderPolicy.decide(
            cards = listOf(card(0.5, elapsed = 99.0)),
            bands = flatBands(1.0),
            retention = 0.9,
            todayReviewed = false,
            maxIntervalDays = capUnscoped,
        )
        assertEquals(AlertKind.RETENTION_DROP, d?.kind)
        assertEquals("有 1 条记忆已经过了线", d?.headline)
        assertEquals("现在回想起来会吃力，补一次能把它拉回去。", d?.body)
    }

    @Test
    fun `跌破与堆积同时成立时先报跌破`() {
        // 两条跌破（0.5/99、1.0/50）+ 明天 3× 堆积。若把堆积排在前面，这里就是 LOAD_SPIKE。
        val d = ReminderPolicy.decide(
            cards = listOf(card(0.5, elapsed = 99.0), card(1.0, elapsed = 50.0)),
            bands = flatBands(10.0, spikeAt = 1),
            retention = 0.9,
            todayReviewed = false,
            maxIntervalDays = capUnscoped,
        )
        assertEquals(AlertKind.RETENTION_DROP, d?.kind)
        assertEquals("有 2 条记忆已经过了线", d?.headline)
    }

    @Test
    fun `没有跌破但明天堆积时选 LOAD_SPIKE`() {
        // S=30 / elapsed=1 → 到期还有 29 天、跌破还有 56 天，两档都不响，只剩堆积信号。
        val d = ReminderPolicy.decide(
            cards = listOf(card(30.0, elapsed = 1.0)),
            bands = flatBands(10.0, spikeAt = 1),
            retention = 0.9,
            todayReviewed = false,
            maxIntervalDays = capUnscoped,
        )
        assertEquals(AlertKind.LOAD_SPIKE, d?.kind)
        assertEquals("明天会堆到 30 项", d?.headline)
        assertEquals("今天先清一点，明天不至于被压住（平时约 10 项）。", d?.body)
    }

    @Test
    fun `堆积与到期同时成立时先报堆积`() {
        // 两张已到期（S=2/3 天、S=5/6 天）+ 明天 3× 堆积。若把到期排在堆积前面，这里就是 DUE_NOW。
        val d = ReminderPolicy.decide(
            cards = listOf(card(2.0, elapsed = 2.5), card(5.0, elapsed = 6.0)),
            bands = flatBands(10.0, spikeAt = 1),
            retention = 0.9,
            todayReviewed = false,
            maxIntervalDays = capUnscoped,
        )
        assertEquals(AlertKind.LOAD_SPIKE, d?.kind)
    }

    @Test
    fun `都不满足但今天有到期时选 DUE_NOW`() {
        // S=2 → 到期第 2 天、跌破第 3.8129 天；elapsed 取 2.5 天正落在两者之间：
        // 已到期、尚未跌破。（不取 2.0：那正压在 ULP 刀刃上，见类注释第 1 条。）
        val d = ReminderPolicy.decide(
            cards = listOf(card(2.0, elapsed = 2.5)),
            bands = flatBands(1.0),
            retention = 0.9,
            todayReviewed = false,
            maxIntervalDays = capUnscoped,
        )
        assertEquals(AlertKind.DUE_NOW, d?.kind)
        assertEquals("1 项按现在的状态该复习了", d?.headline)
        assertEquals("按现在的状态，这些还来得及。", d?.body)
    }

    /** 到期档的项数按**卡**计数（不是按 bands 的期望值取整），而且只数反解已越线的那几张。 */
    @Test
    fun `到期条数按卡计数写进文案`() {
        val d = ReminderPolicy.decide(
            cards = listOf(card(2.0, elapsed = 2.5), card(5.0, elapsed = 6.0), card(30.0, elapsed = 1.0)),
            bands = flatBands(1.0),
            retention = 0.9,
            todayReviewed = false,
            maxIntervalDays = capUnscoped,
        )
        // 前两张到期（第三张还有 29 天），计数必须是 2 而不是 3。
        assertEquals("2 项按现在的状态该复习了", d?.headline)
    }

    // ---- 间隔上限：与调度器同一个政策数，漏了它就等于在提醒侧另起一份排期 ----

    /**
     * 紧上限把到期与跌破判定**提前**：调度器考前把间隔夹到 4 天，判定就必须按 4 天问。
     *
     * 夹具：S=10 的卡、已流逝 5 天。宽上限下反解恒等于 S（R=0.9）→ 还有 5 天才到期、跌破还有 14.06 天，
     * 三档全不响；上限夹到 4 天后两条线都被压到 `4 − 5 = −1` → 当天就已越过 → 命中第一档
     * [AlertKind.RETENTION_DROP]。把 `maxIntervalDays` 重新写回 3650 天常数的话这一条立刻红——
     * 那正是本轮修掉的缺陷（考前那两周因此少报）。
     */
    @Test
    fun `紧上限把到期与跌破判定提前`() {
        val cards = listOf(card(10.0, elapsed = 5.0))
        val flat = flatBands(0.0)
        // 宽上限：还有 5 天到期，什么都不可催
        assertEquals(5.0, ReminderPolicy.dueInDays(w, 10.0, 0.9, 5.0, capUnscoped), 1e-9)
        assertNull(ReminderPolicy.decide(cards, flat, 0.9, false, capUnscoped))
        // 夹到 4 天：到期判定提前 6 天，档位从"不可催"变成"已过线"
        assertEquals(-1.0, ReminderPolicy.dueInDays(w, 10.0, 0.9, 5.0, maxIntervalDays = 4.0), 1e-9)
        val d = ReminderPolicy.decide(cards, flat, retention = 0.9, todayReviewed = false, maxIntervalDays = 4.0)
        assertEquals(AlertKind.RETENTION_DROP, d?.kind)
        assertEquals("有 1 条记忆已经过了线", d?.headline)
    }

    /**
     * 上限真夹住时，到期线与跌破线**同一天**（两次反解被同一个 cap 截顶），于是那种卡上
     * [AlertKind.DUE_NOW] 永远被第一档盖住。这不是缺陷，而是夹取的直接后果，
     * 记在这里免得下一个人以为"考前到期档根本不响"是笔误。
     */
    @Test
    fun `夹取时到期线与跌破线落在同一天`() {
        assertEquals(
            ReminderPolicy.dueInDays(w, 10.0, 0.9, 5.0, maxIntervalDays = 4.0),
            ReminderPolicy.dropInDays(w, 10.0, 0.9, 5.0, maxIntervalDays = 4.0),
            1e-9,
        )
        // 没夹住时才是原本那条序：到期早于跌破
        assertTrue(
            ReminderPolicy.dueInDays(w, 10.0, 0.9, 5.0, capUnscoped) <
                ReminderPolicy.dropInDays(w, 10.0, 0.9, 5.0, capUnscoped),
        )
    }

    // ---- SPIKE_RATIO 门槛：三点夹住严格大于 ----

    @Test
    fun `中位一点四九倍不算堆积 一点五一倍才算`() {
        // 明天 = 1.49× / 1.51× 其余天的中位数；其余天全等，中位数就是那个常数 10。
        // 两个用例只差明天的值，所以被考的正是门槛本身。
        val below = ReminderPolicy.decide(emptyList(), bandsWithTomorrow(14.9), 0.9, false, capUnscoped)
        val above = ReminderPolicy.decide(emptyList(), bandsWithTomorrow(15.1), 0.9, false, capUnscoped)
        assertNull(below)
        assertEquals(AlertKind.LOAD_SPIKE, above?.kind)
        // 15.1 → roundToInt = 15：文案里的项数也钉住取整方式。
        assertEquals("明天会堆到 15 项", above?.headline)
    }

    @Test
    fun `恰为中位的一点五倍也不算堆积 门槛是严格大于`() {
        // 15.0 == 10.0 * 1.5 是**精确**相等（10 与 1.5 都是二进制可精确表示的数），
        // 所以这一条只在比较符被写成 >= 时会红；写成 > 时明天不算异常，空库 → null。
        assertNull(ReminderPolicy.decide(emptyList(), bandsWithTomorrow(15.0), 0.9, false, capUnscoped))
    }

    @Test
    fun `堆积比的是其余天的中位数而不是平均数`() {
        // 其余 13 天：12 天 1.0 + 1 天 60.0 → 中位数 1.0（门槛 1.5），平均数 5.5385（门槛 8.3077）。
        // 明天 7.0：中位数口径算堆积，平均数口径不算——这一条专治把中位数换成均值。
        val rest = List(12) { 1.0 } + listOf(60.0)
        val d = ReminderPolicy.decide(emptyList(), bandsOfDay1(7.0, rest), 0.9, false, capUnscoped)
        assertEquals(AlertKind.LOAD_SPIKE, d?.kind)
        assertEquals("明天会堆到 7 项", d?.headline)
        assertEquals("今天先清一点，明天不至于被压住（平时约 1 项）。", d?.body)
    }

    @Test
    fun `其余天为偶数时中位数取中间两项的均值`() {
        // 其余 4 天：1.0, 1.0, 9.0, 9.0 → 中位数 (1+9)/2 = 5.0 → 门槛 7.5 → 明天 6.0 不算堆积。
        // 若误取单个中间项 rest[size/2 − 1] = 1.0（门槛 1.5），6.0 就成了堆积 → 这条立刻红。
        assertNull(ReminderPolicy.decide(emptyList(), bandsOfDay1(6.0, listOf(1.0, 1.0, 9.0, 9.0)), 0.9, false, capUnscoped))
    }

    // ---- 抑制与空库 ----

    @Test
    fun `今天已经练过就不提醒`() {
        assertNull(
            ReminderPolicy.decide(
                cards = listOf(card(0.5, elapsed = 99.0)),
                bands = flatBands(1.0),
                retention = 0.9,
                todayReviewed = true,
                maxIntervalDays = capUnscoped,
            ),
        )
    }

    @Test
    fun `今天已经练过时连堆积也不报`() {
        // 上一条例子只压了跌破档；堆积档也得被 todayReviewed 一票否决。
        assertNull(
            ReminderPolicy.decide(
                cards = emptyList(),
                bands = flatBands(10.0, spikeAt = 1),
                retention = 0.9,
                todayReviewed = true,
                maxIntervalDays = capUnscoped,
            ),
        )
    }

    @Test
    fun `空库返回 null 不发通知`() {
        assertNull(ReminderPolicy.decide(emptyList(), flatBands(0.0), 0.9, false, capUnscoped))
    }

    @Test
    fun `脏稳定度不参与判定`() {
        // NaN / 0 / 负数：脏 S 若不先滤掉，`intervalDays` 会给它们算出 0 天间隔，
        // 于是"跌破线已过"凭空成立 → RETENTION_DROP（通知里就会出现用户找不到的条数）。
        val dirty = listOf(ForecastCard(Double.NaN, 0.0), ForecastCard(0.0, 0.0), ForecastCard(-3.0, 0.0))
        assertNull(ReminderPolicy.decide(dirty, flatBands(0.0), 0.9, false, capUnscoped))
    }

    // ---- 反解本身的两条不变量 ----

    @Test
    fun `跌破线天数随稳定度单调递增`() {
        // 计划原文这条叫"单调递减"，但断言写的是 small < big——递的是"还有几天跌破"，
        // S 越大越晚跌破，故实为**递增**。名字按事实改，断言原样保留。
        val small = ReminderPolicy.dropInDays(w, 1.0, 0.9, 0.0, capUnscoped)
        val big = ReminderPolicy.dropInDays(w, 20.0, 0.9, 0.0, capUnscoped)
        assertTrue("S=1 应比 S=20 更早跌破：$small vs $big", small < big)
    }

    @Test
    fun `到期线一定不晚于跌破线`() {
        // 计划原文写的是 `dropInDays <= dueInDays`（"到期线不早于跌破线"），与它自己给的实现相矛盾：
        // 跌破阈值 R−DELTA=0.85 低于目标 0.9，召回**先**跌破 0.9（到期）**后**跌破 0.85。
        // 实现侧 `dropInDays = intervalDays(R−DELTA) − elapsed` 是裁决要保留的口径，故改断言方向。
        // 这一条仍然可失败：DELTA 的符号一旦被写反成 R+DELTA，跌破就跑到到期前面 → 红。
        for (s in listOf(0.5, 2.0, 10.0, 120.0)) {
            assertTrue(
                "S=$s：到期(${ReminderPolicy.dueInDays(w, s, 0.9, 0.0, capUnscoped)}) 不应晚于跌破(${ReminderPolicy.dropInDays(w, s, 0.9, 0.0, capUnscoped)})",
                ReminderPolicy.dueInDays(w, s, 0.9, 0.0, capUnscoped) <= ReminderPolicy.dropInDays(w, s, 0.9, 0.0, capUnscoped),
            )
        }
    }

    @Test
    fun `跌破判定相对目标保留率恰好下探 DELTA`() {
        // 手算字面值（v6 默认参）：intervalDays(w,10,0.9) = 10/f·(0.9^(1/decay)−1) = 10.0（R=0.9 恒等）
        //   intervalDays(w,10,0.85) = 10/0.9803464944 · 1.86896 = 19.0643
        // 只断"单调"和"序关系"的话，DELTA 从 0.05 改成 0.15 也不会红；这条把量值本身钉住。
        assertEquals(10.0, ReminderPolicy.dueInDays(w, 10.0, 0.9, 0.0, capUnscoped), 1e-9)
        assertEquals(19.0643, ReminderPolicy.dropInDays(w, 10.0, 0.9, 0.0, capUnscoped), 1e-3)
        // elapsed 只把两条线一起往前挪，不该改变它们的差。
        assertEquals(
            ReminderPolicy.dropInDays(w, 10.0, 0.9, 0.0, capUnscoped) - ReminderPolicy.dueInDays(w, 10.0, 0.9, 0.0, capUnscoped),
            ReminderPolicy.dropInDays(w, 10.0, 0.9, 4.0, capUnscoped) -
                ReminderPolicy.dueInDays(w, 10.0, 0.9, 4.0, capUnscoped),
            1e-9,
        )
    }

    // ---- 造 bands 的 helper ----

    /** 明天（index 1）= `tomorrowMean`，其余 13 天全平 10.0。 */
    private fun bandsWithTomorrow(tomorrowMean: Double): List<DayBand> =
        bandsOfDay1(tomorrowMean, List(13) { 10.0 })

    /** 其余天按 `otherDays` 顺序排，明天插在 index 1 —— 两种中位数口径都由它构造。 */
    private fun bandsOfDay1(tomorrowMean: Double, otherDays: List<Double>): List<DayBand> {
        val means = otherDays.toMutableList()
        means.add(1, tomorrowMean)
        return means.mapIndexed { i, m -> DayBand(i, m, 0.0) }
    }
}
