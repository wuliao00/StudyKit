package com.studykit.ui.stats

import com.studykit.data.AppSettings
import com.studykit.data.dao.ScheduledExcerptRow
import com.studykit.data.dao.ScheduledMemoryRow
import com.studykit.data.entity.Mistake
import com.studykit.data.memory.DayBand
import com.studykit.data.memory.ExamEntry
import com.studykit.data.memory.ForecastCard
import com.studykit.data.memory.ForecastCfg
import com.studykit.data.memory.ReviewStrictness
import com.studykit.ui.study.schedulingForSubject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Task 5（v3.0-A）装配层的单测：纯 JVM、无 runner、不碰 Room —— [ForecastCards.of] 收的是
 * 三份已经 `.first()` 出来的列表，所以这里直接喂内存对象就能覆盖全部映射规则。
 *
 * 断言的期望值一律是**手算字面值**（`now - 2*day` 就是 2.0 天），不拿被测函数给自己出题。
 *
 * 四条真实坑，每条都有对应的测试（口径详见 [ForecastCards] 的 KDoc）：
 *  1. `Mistake` **没有 `last_review_at` 列** —— 锚点只能用 `created_at`，于是错题的 elapsed
 *     是「建卡至今」；这不是精确值，代码不许把它说成「距上次复习」。
 *  2. 书摘侧的稳定度**可空**（列 `excerpts.stability`，见 [ScheduledExcerptRow.stability]）——
 *     空就是「这本书摘还没被 FSRS 写过」，丢掉而不是当 0。
 *  3. `questions` 表**压根没有记忆态列** —— 所以它不参与预测，签名里没有它不是漏了。
 *  4. 脏 S（null / <=0 / 非有限）在**装配这一层**就丢掉 —— 留给 `DueForecast` 丢也行，但那样
 *     看板上的「14 天负荷」和「共 N 张卡」数的就不是同一批卡（本仓在概率与词数不同批这件事上
 *     写过整条 [com.studykit.ui.study.TomorrowLoad.sampledCount] 的防线，同源不另造口径）。
 *
 * 书摘入参用的是 [ScheduledExcerptRow] 投影（`BookDao.observeScheduledExcerpts` 的返回类型），
 * 不是 `Excerpt` 实体——所以这里压根不碰 Room，也不需要实体构造器。
 *
 * ## v3.0-B Task 7 追加的两组
 *  1. **学科进了卡集合**：涌量预警是在这批卡上按 `subject` 筛子集的，所以装配层必须把错题那一列带下来
 *     （词与书摘没有学科列，恒为空串 = 只被全学科那场抬，计划偏差 D5）。
 *  2. [sprintSurgeFor]：预测器与调度缝都是**注入**的。用真 `DueForecast` 写断言会得到一条
 *     「今天绿、改了 FSRS 默认参数就红」的脆测试，而它想钉的其实是三件与预测本身无关的事——
 *     **只跑一遍、跑的是 ramp 后那份 retention、流入参数照抄页面那份**。预测本身的正确性由
 *     `DueForecastAccuracyTest` 的 MAE 门锁在外面，不在这里重跑一遍。
 *
 * ## 修复轮 1（评审 F1/F2/F5）加的几条
 *  1. **闸门两边的算法**（F1）：`base` 必须**保住 horizon、只去掉 ramp**（`nearestExamDaysLeft` +
 *     `baseTargetRecall`，与 `ui/study/ExamCountdown.kt` 同一个配方）。上一版拿「没有考试」那份当基线，
 *     于是 shipped 默认档 `AUTO` 两边都在说谎：明天考时 0.90 < 0.95 让它空响，300 天后考时
 *     0.90 > 0.786 让真被抬的那一场不响。两条新用例一边一个方向钉住。
 *  2. **旧单日期合成**（F2）：只设 `examEpochDay` 的那一种设置必须仍然进闸门两侧；清单非空时旧键
 *     失去话语权，所以 `ramped` 那份只写 `exams` 一个字段（上一版那句没被钉住的 `examEpochDay = 0L`
 *     随 base 改道一起删掉了）。
 *  3. **断言自己得能红**（F5）：`inflowW` 改为对字面量数组比（`cfgBase` 换成一份与默认值不同的权重，
 *     否则 `copy()` 传的是同一个引用，`contentEquals` 恒真）；另外补了科目名带空白/大小写的那条筛子集路径。
 */
class ForecastCardsTest {

    private val now = 1_700_000_000_000L
    private val day = 86_400_000L

    @Test
    fun `脏稳定度的卡被丢掉`() {
        val cards = ForecastCards.of(
            words = emptyList(),
            mistakes = listOf(mistake(stability = null), mistake(stability = 0.0), mistake(stability = 3.0)),
            excerpts = emptyList(),
            now = now,
        )
        assertEquals(1, cards.size)
        assertEquals(3.0, cards.single().stabilityDays, 1e-9)
    }

    @Test
    fun `错题没有 last_review_at 时用 createdAt 当锚点`() {
        val cards = ForecastCards.of(
            words = emptyList(),
            // reviewAt 故意设成「今天到期」：装配层若错拿它当锚点（它不是 last_review_at，
            // 是 v2.7 起的「手动覆盖」列），elapsed 会算成 0.0 而不是 2.0。
            mistakes = listOf(mistake(stability = 5.0, createdAt = now - 2 * day, reviewAt = now)),
            excerpts = emptyList(),
            now = now,
        )
        assertEquals(2.0, cards.single().elapsedDays, 0.01)
    }

    @Test
    fun `书摘按 stability 进队列且 kind 可区分`() {
        val cards = ForecastCards.of(
            words = emptyList(),
            mistakes = emptyList(),
            // helper 默认建在 5 天前：这样 elapsed 有非零真值可断，锚点取错列不会被 0 天掩盖
            excerpts = listOf(excerpt(stability = 8.0)),
            now = now,
        )
        assertEquals("excerpt", cards.single().kind)
        assertEquals(8.0, cards.single().stabilityDays, 1e-9)
        assertEquals(5.0, cards.single().elapsedDays, 1e-9)
    }

    @Test
    fun `elapsed 永不为负`() {
        val cards = ForecastCards.of(
            words = emptyList(),
            mistakes = listOf(mistake(stability = 5.0, createdAt = now + 10 * day)),
            excerpts = emptyList(),
            now = now,
        )
        // 钳位后的真值是 0.0，不是"某个非负数"：断精确值才挡得住「算成了 10 天」这类反向错。
        assertEquals(0.0, cards.single().elapsedDays, 1e-9)
    }

    /**
     * 类 KDoc 承诺「`null` / `<= 0` / **非有限**一律丢」，前两种别的测试钉着，这一条专门钉非有限：
     * NaN 与 ±Inf 是能进库的脏值（导入、备份恢复、数值溢出都会写进去），而反解式在它们身上没有定义。
     *
     * 三张表都喂：守卫虽然只有一份（[ForecastCards] 私有的 `usable()` 被三条分支共用），但三条分支的
     * `?: continue` 是各自独立写的——只测一张表，另外两条分支哪天换成裸取值也不会红。
     * 另注：NaN 就算删掉 `isFinite()` 也过不了 `> 0.0`，所以**正无穷才是这条测试的杀招**。
     */
    @Test
    fun `非有限稳定度（NaN 与正负无穷）在三张表上都整条丢掉`() {
        val cards = ForecastCards.of(
            words = listOf(
                row(stability = Double.NaN, lastReviewAt = now - day, createdAt = now - day),
                row(stability = Double.POSITIVE_INFINITY, lastReviewAt = now - day, createdAt = now - day),
                row(stability = 7.0, lastReviewAt = now - day, createdAt = now - day),
            ),
            mistakes = listOf(
                mistake(stability = Double.NaN),
                mistake(stability = Double.POSITIVE_INFINITY),
                mistake(stability = 4.0),
            ),
            excerpts = listOf(
                excerpt(stability = Double.NaN),
                excerpt(stability = Double.POSITIVE_INFINITY),
                excerpt(stability = 5.0),
            ),
            now = now,
        )
        assertEquals(3, cards.size)
        assertEquals(listOf("word", "mistake", "excerpt"), cards.map { it.kind })
        assertEquals(7.0, cards[0].stabilityDays, 1e-9)
        assertEquals(4.0, cards[1].stabilityDays, 1e-9)
        assertEquals(5.0, cards[2].stabilityDays, 1e-9)
    }

    @Test
    fun `书摘 stability 为空时整条丢掉，不当成 0 天`() {
        val cards = ForecastCards.of(
            words = emptyList(),
            mistakes = emptyList(),
            excerpts = listOf(excerpt(stability = null), excerpt(stability = 6.0)),
            now = now,
        )
        assertEquals(1, cards.size)
        assertEquals(6.0, cards.single().stabilityDays, 1e-9)
    }

    /**
     * 词卡是三张表里唯一有 `last_review_at` 的那一张，所以它是唯一能给出真「距上次复习」的。
     * 两行各断一个锚点：有复习史走 `lastReviewAt`，迁移折算出来的老行（那一列全 NULL）走 `createdAt`。
     */
    @Test
    fun `词卡的锚点是 lastReviewAt，从没复习过的退回 createdAt`() {
        val cards = ForecastCards.of(
            words = listOf(
                row(lastReviewAt = now - 3 * day, createdAt = now - 30 * day),
                row(lastReviewAt = null, createdAt = now - 4 * day),
            ),
            mistakes = emptyList(),
            excerpts = emptyList(),
            now = now,
        )
        assertEquals("word", cards.first().kind)
        assertEquals(3.0, cards[0].elapsedDays, 0.01)
        assertEquals(4.0, cards[1].elapsedDays, 0.01)
    }

    /**
     * kind 的词表就这三张表。写成"恰好这三种"而不是"包含"，是为了让第四种（比如有人给
     * `questions` 补了个 `fsrs_stability` 就顺手接进来）必须改这条断言才能过 —— 那是个
     * 该被拦住的改动，不是顺手加的。
     */
    @Test
    fun `三张表的 kind 只有三种，题目表不参与`() {
        val cards = ForecastCards.of(
            words = listOf(row(lastReviewAt = now - day, createdAt = now - day)),
            mistakes = listOf(mistake(stability = 4.0)),
            excerpts = listOf(excerpt(stability = 6.0)),
            now = now,
        )
        assertEquals(setOf("word", "mistake", "excerpt"), cards.map { it.kind }.toSet())
    }

    /**
     * `toForecastCards()` 不重新过滤一遍脏 S：能走到这一步的卡已经在 `of` 里筛过，
     * 所以这里的条数必须与 `of` 的条数逐条相等（两处各筛一次迟早分叉成两套账）。
     */
    @Test
    fun `映射到预测输入的条数与内容都不加不减`() {
        val cards = ForecastCards.of(
            words = emptyList(),
            mistakes = listOf(mistake(stability = null), mistake(stability = 2.5)),
            excerpts = listOf(excerpt(stability = null), excerpt(stability = 9.0)),
            now = now,
        )
        val forecast = cards.toForecastCards()
        assertEquals(2, forecast.size)
        assertEquals(2.5, forecast[0].stabilityDays, 1e-9)
        assertEquals(9.0, forecast[1].stabilityDays, 1e-9)
        // elapsed 是投影带过来的真值，不是缺省：错题 helper 建在 now（0.0 天），书摘 helper 建在 5 天前（5.0 天）
        assertEquals(0.0, forecast[0].elapsedDays, 1e-9)
        assertEquals(5.0, forecast[1].elapsedDays, 1e-9)
    }

    // ---- v3.0-B Task 7 Step 0：学科跟着卡集合走 ----

    /**
     * 错题那一列是**唯一**能带进预测的学科（`words` 只有 `source_list_id`、`excerpts` 归属书），
     * 所以词与书摘恒为空串。空串不是"忘了填"：它在 [sprintSurgeFor] 那一侧读作
     * 「这张卡不属于任何一科」，只会被 `subjects` 为空的那场考试抬 —— 计划偏差 D5 的字面落地。
     *
     * 断的是装配顺序（word → mistake → excerpt，就是 [ForecastCards.of] 那三条分支的顺序），
     * 不是 `sorted()`：计划原文写的是 `listOf("", "数学", "")` 却又调了 `.sorted()`，
     * 而排完序真值是 `["", "", "数学"]` —— 那条断言自相矛盾、按原文写必红。钉顺序既保住同样的字面值，
     * 又顺带把「三条分支各产一条、不重排」这件本文件已经在钉的事再钉一次。
     */
    @Test
    fun `错题卡片带上学科，词与书摘为空串`() {
        val cards = ForecastCards.of(
            words = listOf(row(stability = 4.0, lastReviewAt = now - 2 * day, createdAt = now - 20 * day)),
            mistakes = listOf(mistake(stability = 8.0, subject = "数学")),
            excerpts = listOf(excerpt(stability = 6.0)),
            now = now,
        )
        assertEquals(listOf("", "数学", ""), cards.map { it.subject })
    }

    /**
     * 学科必须在 `toForecastCards()` 这一步也带过去。漏掉它不报错、不崩溃：症状是
     * 「涌量预警从此只对全学科那一场说话」——因为 [sprintSurgeFor] 筛的是 `ForecastCard.subject`，
     * 而那是它唯一能认出学科的字段。上面那条钉的是装配层，这一条钉投影层，两层各钉一次。
     */
    @Test
    fun `投影到预测输入时学科字段一起走，不在这里丢掉`() {
        val cards = ForecastCards.of(
            words = listOf(row(lastReviewAt = now - day, createdAt = now - day)),
            mistakes = listOf(mistake(stability = 4.0, subject = "数学"), mistake(stability = 5.0, subject = "英语")),
            excerpts = listOf(excerpt(stability = 6.0)),
            now = now,
        )
        assertEquals(listOf("", "数学", "英语", ""), cards.map { it.subject })
        assertEquals(listOf("", "数学", "英语", ""), cards.toForecastCards().map { it.subject })
    }

    // ---- v3.0-B Task 7：涌量读数 ----

    /**
     * 页面那份 `ForecastCfg` 的替身。`retention` 与 `maxIntervalDays` 是涌量**允许换掉**的两个字段，
     * 其余（窗口、每日新词、学习步、摊派权重）必须原样照抄 —— 流入参数一分叉，读出来的「涌」
     * 就有一部分是新词造成的而不是 ramp 造成的（测试因此逐字段钉它）。
     *
     * `inflowW` 这里交的是**一份与默认值不同的显式数组**（0.6/0.25/0.15，和为 1）而不是留默认参：
     * [ForecastCfg.inflowW] 的默认值是 `defaultInflow()`，而 `copy()` 传的是**同一个数组引用**，
     * 于是「照抄页面那份」用 `contentEquals` 对 cfgBase 自己比会恒真（两边本来就是同一个对象），
     * 实现换成新建一份默认数组也不会红。改成对字面量数组比，它才真的能失败。
     */
    private val cfgBase = ForecastCfg(
        retention = 0.80,
        maxIntervalDays = 36.0,
        inflowW = doubleArrayOf(0.6, 0.25, 0.15),
    )

    /** 假预测器：平的带，峰 = 中位 = 20 → 读数 1.0 */
    private val flat: (List<ForecastCard>, ForecastCfg) -> List<DayBand> =
        { _, _ -> (0 until 14).map { DayBand(it, 20.0, 2.0) } }

    /** 假预测器：最后一天堆到 100 → 峰 100 / 中位 20 = 5.0 */
    private val spiky: (List<ForecastCard>, ForecastCfg) -> List<DayBand> =
        { _, _ -> (0 until 14).map { DayBand(it, if (it == 13) 100.0 else 20.0, 2.0) } }

    @Test
    fun `硬闸 ramp 生效时按冲刺保留率重跑预测并报警`() {
        val seen = mutableListOf<ForecastCfg>()
        val cards = List(60) { ForecastCard(stabilityDays = 3.0 + it, elapsedDays = 2.0 + it / 3.0, subject = "数学") }
        val settings = AppSettings(
            reviewStrictness = ReviewStrictness.RELAXED,
            // 明天考、窗 30 天、冲刺 0.95：RELAXED base=0.80 -> ramp 后 0.80+0.15*(29/30)=0.945
            exams = listOf(ExamEntry("期末考", LocalDate.of(2026, 10, 7).toEpochDay(), emptyList(), 0.95, 30)),
        )
        val state = sprintSurgeFor(
            settings = settings,
            cards = { cards },
            cfgBase = cfgBase,
            today = LocalDate.of(2026, 10, 6),
            scheduleOf = { s, sub, t -> schedulingForSubject(s, sub, t) },
            forecast = { cs, cfg -> seen += cfg; if (cfg.retention > 0.85) spiky(cs, cfg) else flat(cs, cfg) },
        )
        assertEquals("只该按 ramp 后那份保留率跑一遍", 1, seen.size)
        assertEquals(0.945, seen[0].retention, 1e-3)
        // 流入参数必须与统计页那份一模一样，否则"涌"里掺的是新词而不是 ramp
        assertEquals(cfgBase.horizonDays, seen[0].horizonDays)
        assertEquals(cfgBase.newPerDay, seen[0].newPerDay)
        assertEquals(cfgBase.learningPerNew, seen[0].learningPerNew, 1e-9)
        // 摊派权重逐位钉**字面量数组**（不钉 cfgBase 自己，见上面那段说明）
        assertArrayEquals(
            "摊派权重也得照抄页面那份",
            doubleArrayOf(0.6, 0.25, 0.15),
            seen[0].inflowW,
            1e-9,
        )
        // 换掉的那两个：retention 见上，上限按 horizon=1 给 max(3, 1×0.2)=3.0（手算，不读被测实现）
        assertEquals(3.0, seen[0].maxIntervalDays, 1e-9)
        assertEquals(5.0, state.ratio!!, 1e-9)            // 峰 100 / 中位 20
        assertTrue(state.warn)
    }

    @Test
    fun `没有关联学科的那场不跑第二遍，也不报警`() {
        val calls = mutableListOf<ForecastCfg>()
        val cards = List(60) { ForecastCard(stabilityDays = 3.0 + it, elapsedDays = 2.0 + it / 3.0, subject = "英语") }
        val settings = AppSettings(
            exams = listOf(ExamEntry("期末考", LocalDate.of(2026, 10, 16).toEpochDay(), listOf("数学"), 0.95, 30)),
        )
        val state = sprintSurgeFor(
            settings, { cards }, cfgBase, LocalDate.of(2026, 10, 6),
            { s, sub, t -> schedulingForSubject(s, sub, t) },
            forecast = { cs, cfg -> calls += cfg; flat(cs, cfg) },
        )
        assertTrue(calls.isEmpty())     // 没 ramp 就不该跑预测：白跑一遍是要付的 CPU
        assertNull(state.ratio)
        assertFalse(state.warn)
    }

    /**
     * 「清单里有考试，但那场没把这一科的保留率抬上去」——窗口外的考试就是这个形状。
     * 这条钉的是上面那条 `continue` 的另一半：上一版拿**已经带 ramp** 的那份 `Scheduling` 当 base 比，
     * 于是单场考试的夹具上两边恒等、永远不跑（涌量预警在最典型的那一种用户身上静默失效）。
     */
    @Test
    fun `保留率没被抬上去的那场不跑预测`() {
        val calls = mutableListOf<ForecastCfg>()
        val cards = List(60) { ForecastCard(stabilityDays = 3.0 + it, elapsedDays = 2.0 + it / 3.0, subject = "数学") }
        val settings = AppSettings(
            reviewStrictness = ReviewStrictness.RELAXED,
            // 100 天后考、窗只有 30 天：ramp 不在生效区间，retention 与「清单为空」那次一模一样
            exams = listOf(ExamEntry("期末考", LocalDate.of(2027, 1, 14).toEpochDay(), emptyList(), 0.95, 30)),
        )
        val state = sprintSurgeFor(
            settings, { cards }, cfgBase, LocalDate.of(2026, 10, 6),
            { s, sub, t -> schedulingForSubject(s, sub, t) },
            forecast = { cs, cfg -> calls += cfg; spiky(cs, cfg) },
        )
        assertTrue(calls.isEmpty())
        assertNull(state.ratio)
        assertFalse(state.warn)
    }

    /**
     * ** shipped 默认档（AUTO）+ 明天那场：闸门必须关**。
     *
     * AUTO 是 `AppSettings.reviewStrictness` 的默认值，也就是绝大多数用户的档。这一档下
     * horizon=1 天本来就把 base 推到 `MemoryModel.targetRecallFor` 的顶端 0.95（≤60 天都是 0.95），
     * 而那场考试的冲刺值也是 0.95 —— ramp 一分没抬，闸门该关着。
     * 上一版的 base 是**没有考试**时那份（`defaultTargetRecall` = 0.90），于是 0.95 > 0.90 判成"被抬了"，
     * 屏幕对着一句「目标保留率被提到冲刺值」报警，而统计页那张图本来就在按 0.95 画（两边同一份 cfg，
     * 所谓"重跑"什么都没变）。这条就是拦这个假话。
     */
    @Test
    fun `严格度默认档明天那场不报警，基线本就等于冲刺值`() {
        val calls = mutableListOf<ForecastCfg>()
        val cards = List(60) { ForecastCard(stabilityDays = 3.0 + it, elapsedDays = 2.0 + it / 3.0, subject = "数学") }
        val settings = AppSettings(
            reviewStrictness = ReviewStrictness.AUTO,
            // 全学科、明天考、窗 30 天、冲刺 0.95：AUTO 的 base（horizon=1）就是 0.95
            exams = listOf(ExamEntry("期末考", LocalDate.of(2026, 10, 7).toEpochDay(), emptyList(), 0.95, 30)),
        )
        val state = sprintSurgeFor(
            settings, { cards }, cfgBase, LocalDate.of(2026, 10, 6),
            { s, sub, t -> schedulingForSubject(s, sub, t) },
            forecast = { cs, cfg -> calls += cfg; spiky(cs, cfg) },
        )
        assertTrue("AUTO 加明天那场什么都没抬，不该跑预测", calls.isEmpty())
        assertNull(state.ratio)
        assertFalse(state.warn)
    }

    /**
     * **AUTO 的反面：ramp 真抬了的时候闸门必须开**（与上面那条成对，防「一律不报警」把功能做死）。
     *
     * 窗 365 天、300 天后考：horizon=300 → AUTO 的 base = `targetRecallFor(300)` = 0.95 − 0.05×5 = **0.75**
     * （`coerceIn(0.75, 0.95)` 的下沿）；ramp = 0.75 + (0.95 − 0.75) × (1 − 300/365) = **0.7856164…**（手算）。
     * 上一版的 base 取的是「没有考试」那份 0.90，于是 0.786 ≤ 0.90 → 闸门关着，这条**真**被抬了的
     * 考试永远不响 —— 与上一条正好相反方向的同一个谎。上限按 horizon=300 给 `max(3, min(300×0.2, 365))` = 60.0。
     */
    @Test
    fun `严格度默认档三百天后那场确实被抬了，闸门打开`() {
        val seen = mutableListOf<ForecastCfg>()
        val cards = List(60) { ForecastCard(stabilityDays = 3.0 + it, elapsedDays = 2.0 + it / 3.0, subject = "数学") }
        val settings = AppSettings(
            reviewStrictness = ReviewStrictness.AUTO,
            // 2026-10-06 + 300 天 = 2027-08-02（手算，remainingDays 必须是 300）
            exams = listOf(ExamEntry("期末考", LocalDate.of(2027, 8, 2).toEpochDay(), emptyList(), 0.95, 365)),
        )
        val state = sprintSurgeFor(
            settings, { cards }, cfgBase, LocalDate.of(2026, 10, 6),
            { s, sub, t -> schedulingForSubject(s, sub, t) },
            forecast = { cs, cfg -> seen += cfg; spiky(cs, cfg) },
        )
        assertEquals("ramp 真抬了 0.75 → 0.786，该跑一遍预测", 1, seen.size)
        assertEquals(0.7856164, seen[0].retention, 1e-4)
        assertEquals(60.0, seen[0].maxIntervalDays, 1e-9)
        assertEquals(5.0, state.ratio!!, 1e-9)
        assertTrue(state.warn)
        // 报警归给哪一科：这一场是全学科那场，所以署名是 ExamEntry.DEFAULT_NAME 那个「考试」
        // （视图那行开头念它，见 com.studykit.ui.stats.LoadBandSection）；带学科的署名由下面筛子集那条钉
        assertEquals("考试", state.subject)
    }

    /**
     * 评审 F2 那条「旧键合成」的守卫：**只设旧 `exam_epoch_day`、`exams` 留空**的那一种设置
     * （v2.9 升上来的老用户全是这个形状，也是 [AppSettings.examList] 会凭空合成一场全学科考试的那一种）。
     *
     * 上一版算 base 时会 `copy(exams = emptyList(), examEpochDay = 0L)` 摊掉合成，其中
     * `examEpochDay = 0L` 那半句是承重的（删掉它 base 就被合成那场重新 ramp）。现在 base 不再走设置副本，
     * 而是 `nearestExamDaysLeft(subject, today, settings.examList)` + `baseTargetRecall` —— 合成那场
     * **本来就该被算进 horizon**，所以那半句抑制随之删掉了。这条钉的是删掉之后仍然成立的两件事：
     *  1. 合成的那场照样进闸门（RELAXED base=0.80 手动档、ramp=0.80+0.15×(29/30)=0.945 → 跑一遍）；
     *  2. horizon 走的是那场考试日（明天 → 上限 `max(3, 1×0.2)` = 3.0，不是「无考试」那档 36.0/73 天）。
     * 旧键一旦被谁从闸门里抹掉，这条立刻红（老用户的涌量预警静默失效就是原 bug 的另一半）。
     */
    @Test
    fun `只有旧考试日那份设置时也进闸门两侧`() {
        val seen = mutableListOf<ForecastCfg>()
        val cards = List(60) { ForecastCard(stabilityDays = 3.0 + it, elapsedDays = 2.0 + it / 3.0, subject = "数学") }
        val settings = AppSettings(
            reviewStrictness = ReviewStrictness.RELAXED,
            examEpochDay = LocalDate.of(2026, 10, 7).toEpochDay(),
            // exams 故意留空：这一份只能靠 examList 从旧键合成那场全学科考试（默认窗 30 / 冲刺 0.95）
        )
        assertTrue("夹具必须是「清单为空、旧键有值」那种形状", settings.exams.isEmpty())
        assertEquals(1, settings.examList.size)
        val state = sprintSurgeFor(
            settings, { cards }, cfgBase, LocalDate.of(2026, 10, 6),
            { s, sub, t -> schedulingForSubject(s, sub, t) },
            forecast = { cs, cfg -> seen += cfg; spiky(cs, cfg) },
        )
        assertEquals(1, seen.size)
        assertEquals(0.945, seen[0].retention, 1e-3)
        assertEquals(3.0, seen[0].maxIntervalDays, 1e-9)
        assertEquals(5.0, state.ratio!!, 1e-9)
        assertTrue(state.warn)
    }

    /**
     * 评审 F2 的另一半：清单**非空**时旧键失去话语权（`AppSettings.examList` 的口径写在 `Settings.kt`），
     * 所以 `ramped` 那一份只需 `copy(exams = listOf(exam))`，不必再连 `examEpochDay` 一起清。
     *
     * 夹具故意让两个来源打架：旧键说明天考、清单说 300 天后考。冲刺那一份读的必须是清单里那场
     * （retention 0.7856164、上限 60.0）——旧键若渗进去，horizon 变成 1 天、ramp 打满 0.95，
     * 这条的期望值立刻对不上。把 `copy(exams = listOf(exam))` 写成 `copy()` 是这条会拦住的改动。
     */
    @Test
    fun `清单非空时旧键不再说话，冲刺那份只读这一场`() {
        val seen = mutableListOf<ForecastCfg>()
        val cards = List(60) { ForecastCard(stabilityDays = 3.0 + it, elapsedDays = 2.0 + it / 3.0, subject = "数学") }
        val settings = AppSettings(
            reviewStrictness = ReviewStrictness.AUTO,
            examEpochDay = LocalDate.of(2026, 10, 7).toEpochDay(),
            exams = listOf(ExamEntry("期末考", LocalDate.of(2027, 8, 2).toEpochDay(), emptyList(), 0.95, 365)),
        )
        val state = sprintSurgeFor(
            settings, { cards }, cfgBase, LocalDate.of(2026, 10, 6),
            { s, sub, t -> schedulingForSubject(s, sub, t) },
            forecast = { cs, cfg -> seen += cfg; spiky(cs, cfg) },
        )
        assertEquals(1, seen.size)
        assertEquals(0.7856164, seen[0].retention, 1e-4)
        assertEquals(60.0, seen[0].maxIntervalDays, 1e-9)
        assertTrue(state.warn)
    }

    /**
     * 科目名两边都得掐首尾空白、且忽略大小写（与 `ExamPolicy.linksSubject` 同口径，评审 F3 那条）。
     * 库里 `mistakes.subject` 是用户手输的，`SELECT DISTINCT` 出来带空格是常态；
     * 漏掉掐边不报错，症状是「这一科的卡一张都筛不到、预警对它静默失效」——功能整块消失。
     */
    @Test
    fun `科目名带首尾空白或大小写不一时照样筛到卡`() {
        val seen = mutableListOf<ForecastCfg>()
        val cards = List(40) { ForecastCard(stabilityDays = 3.0 + it, elapsedDays = 2.0 + it / 3.0, subject = "数学 ") } +
            List(40) { ForecastCard(stabilityDays = 3.0 + it, elapsedDays = 2.0 + it / 3.0, subject = "ENGLISH ") }
        val settings = AppSettings(
            reviewStrictness = ReviewStrictness.RELAXED,
            // 考试那边写的是 " 数学 " 与全小写 english，卡那边是尾随空格 + 全大写：两边都要能对上
            exams = listOf(ExamEntry("期末考", LocalDate.of(2026, 10, 7).toEpochDay(), listOf(" 数学 ", "english"), 0.95, 30)),
        )
        val state = sprintSurgeFor(
            settings, { cards }, cfgBase, LocalDate.of(2026, 10, 6),
            { s, sub, t -> schedulingForSubject(s, sub, t) },
            forecast = { cs, cfg -> seen += cfg; spiky(cs, cfg) },
        )
        assertEquals("两科各跑一遍，一科都不许被空白或大小写挡在门外", 2, seen.size)
        assertEquals(0.945, seen[0].retention, 1e-3)
        assertEquals(0.945, seen[1].retention, 1e-3)
        assertEquals("报警归给掐过边的那一科", "数学", state.subject)
        assertTrue(state.warn)
    }

    @Test
    fun `清单为空时不预测涌量`() {
        var ran = false
        val state = sprintSurgeFor(
            AppSettings(), { List(10) { ForecastCard(3.0, 2.0, "数学") } }, cfgBase, LocalDate.of(2026, 10, 6),
            { s, sub, t -> schedulingForSubject(s, sub, t) },
            forecast = { _, _ -> ran = true; emptyList() },
        )
        assertFalse(ran)
        assertFalse(state.warn)
        assertEquals("", state.subject)
    }

    @Test
    fun `一科都筛不出来时不报警`() {
        val cards = List(60) { ForecastCard(3.0 + it, 2.0, "物理") }
        val settings = AppSettings(
            exams = listOf(ExamEntry("考", LocalDate.of(2026, 10, 16).toEpochDay(), listOf("数学"), 0.95, 30)),
        )
        val state = sprintSurgeFor(
            settings, { cards }, cfgBase, LocalDate.of(2026, 10, 6),
            { s, sub, t -> schedulingForSubject(s, sub, t) },
            forecast = { cs, cfg -> spiky(cs, cfg) },
        )
        assertFalse(state.warn)
    }

    private fun mistake(
        stability: Double?,
        createdAt: Long = now,
        reviewAt: Long? = null,
        subject: String = "数学",
    ) = Mistake(
        uuid = "u",
        // `Mistake.source` 是 NOT NULL 且**无默认值**（entity/Mistake.kt:16），计划里的 helper 漏了它
        source = Mistake.SOURCE_WORD,
        subject = subject,
        title = "t",
        content = "c",
        fsrsStability = stability,
        createdAt = createdAt,
        reviewAt = reviewAt,
    )

    /**
     * 书摘 helper 建在 **5 天前**（不是 `now`）：锚点用错列、或者钳位写错方向，在 `createdAt = now`
     * 下都会算出 0.0 天而悄悄通过——elapsed 得有非零真值才测得到。5.0 是能整除的字面值。
     */
    private fun excerpt(stability: Double?, createdAt: Long = now - 5 * day) = ScheduledExcerptRow(
        stability = stability,
        createdAt = createdAt,
    )

    private fun row(lastReviewAt: Long?, createdAt: Long, stability: Double? = 10.0) = ScheduledMemoryRow(
        nextReviewAt = now + day,
        halfLifeDays = 2.0,
        lastReviewAt = lastReviewAt,
        createdAt = createdAt,
        fsrsStability = stability,
    )
}
