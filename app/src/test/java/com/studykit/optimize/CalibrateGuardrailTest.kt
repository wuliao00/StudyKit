package com.studykit.optimize

import com.studykit.data.AppSettings
import com.studykit.data.dao.AppSettingDao
import com.studykit.data.dao.ParamSnapshotDao
import com.studykit.data.dao.ReviewLogDao
import com.studykit.data.entity.AppSetting
import com.studykit.data.entity.ReviewLog
import com.studykit.data.memory.FsrsModel
import com.studykit.data.repository.ReviewLogRepository
import com.studykit.data.repository.SettingsRepository
import com.studykit.ui.optimize.CalibrateState
import com.studykit.ui.optimize.CalibrateViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.9 计划 Task 11：把优化器接成一条**跑得动、也测得动**的流程，并把护栏逐条钉住。
 *
 * ## 为什么要 seam
 *
 * 训练在前者是 Rust JNI（[FsrsNative]），`.so` 只有 arm 两份，CI 的 x86 JVM 一个都调不动
 * （[FsrsNativeGuardTest] 钉的就是这件事）。而 Task 11 的全部风险**不在算式在编排**：
 * 谁先谁后、什么时候不许写、写几份、异常落在哪。所以把原生调用收进 [FsrsTrainer] 这一个接口，
 * 本文件用**假训练器**驱动真的 [CalibrateViewModel] 编排 —— 纯 JVM，不起 Robolectric，
 * 也不碰任何 `external fun`。真机上那条 JNI 链路由 T13 验。
 *
 * ## 钉住的七条护栏（逐条对应计划 Task 11 Step 1 与文档 §阶段5）
 *
 * 1. 数据不足（term 数 < [FsrsGate.MIN_TERMS]）→ 直接失败，**连训练器都不叫**（不烧一次设备训练），
 *    更不许写快照 / 写活跃参数；
 * 2. 干跑模式 → 出报告、出状态，但一次写库都没有，`adopt()` 也拒绝；
 * 3. 采纳 → 写一份 [ParamSnapshot]（参数 / ts / nTerms / logloss / 引擎版本五样都来自这份报告）
 *    + 置 `fsrsActiveParams`，并裁到最近 [ParamSnapshot.MAX_KEEP] 份；
 * 4. 恢复默认 → `fsrsActiveParams` 回 null（快照留着，回滚的本钱不烧）；
 * 5. 训练器 / 评估器返回 null（原生不可用或 JNI 失败）→ Failed，旧参数原样留着，不崩；
 * 6. 门禁 Reject → Failed(理由)，绝不自动 adopt；Manual / Adopt → Result，
 *    **采纳永远是单独的用户动作**（Manual 还带着那条要给人看的原因）；
 * 7. 常识校验：候选参数不满足 `S0(Again) ≤ S0(Hard) ≤ S0(Good) ≤ S0(Easy)` → 按 Reject 处理，
 *    换核之前先拦一道。
 *
 * 外加两条"永不崩调度"：训练器抛异常、日志为空，都只落成 Failed 状态 + 一条日志，旧参数不动。
 *
 * 存储侧用**手写 fake DAO**（同 [com.studykit.ui.habit.HabitTargetDaysTest] 的做法）：
 * `AppSettingDao` / `ParamSnapshotDao` 就是接口，`SettingsRepository` 拿真实现，
 * 于是 `fsrsActiveParams` 的落库口径（21 项逗号串 → 脏值退 null）也在这一条链上被顺带钉住。
 */
class CalibrateGuardrailTest {

    // ── 数据与替身 ────────────────────────────────────────────

    /** 注入用的固定"现在"，快照的 ts 要能被逐字断言。 */
    private val nowMs = 1_780_000_000_000L

    private val defaultFloats = FloatArray(FsrsModel.NP) { FsrsModel.DEFAULT_PARAMS[it].toFloat() }

    /**
     * 造复习日志：每张卡 `perCard` 条、首评 elapsed=0（不计损失）、其后 elapsed=5 天。
     * 可产生损失的 term 数 = 卡数 × (perCard-1)，train 侧按 [LogPacker] 的 80/20 整卡切分取前段。
     */
    private fun rows(cards: Int, perCard: Int = 2): List<ReviewLog> = buildList {
        val base = 1_700_000_000_000L
        for (c in 0 until cards) {
            for (k in 0 until perCard) {
                add(
                    ReviewLog(
                        cardKind = ReviewLog.KIND_WORD,
                        cardId = c.toLong(),
                        ts = base + c * 60_000L + k * 5 * 86_400_000L,
                        rating = if (k == 0) 3 else (k % 4) + 1,
                        elapsedDays = if (k == 0) 0.0 else 5.0,
                        scheduledDays = if (k == 0) 0.0 else 5.0,
                        state = if (k == 0) 0 else 2,
                        durationMs = 3_000L,
                        origin = "word",
                        selfConf = null,
                        sessionId = "s$c",
                        isMakeup = 0,
                        logSchemaVer = ReviewLog.CURRENT_SCHEMA_VER,
                    ),
                )
            }
        }
    }

    /** 2600 行 → 1300 张可训卡 → train 侧 1040 个 term，刚好过 1000 那道硬门槛。 */
    private fun enoughRows(): List<ReviewLog> = rows(cards = 1_300)

    private class FakeReviewLogDao(initial: List<ReviewLog>) : ReviewLogDao {
        var rows: List<ReviewLog> = initial
        var reads = 0
        var writes = 0

        override suspend fun insert(log: ReviewLog): Long {
            writes++
            rows = rows + log
            return rows.size.toLong()
        }

        override suspend fun getAllSince(): List<ReviewLog> {
            reads++
            return rows.sortedWith(compareBy({ it.cardKind }, { it.cardId }, { it.ts }))
        }
    }

    private class FakeParamSnapshotDao : ParamSnapshotDao {
        private val kept = mutableListOf<ParamSnapshot>()
        private var nextId = 1L

        /** 每次 prune 裁完之后剩下的 ts，按时间倒序（与真 DAO 的 `ORDER BY ts DESC` 同口径）。 */
        val survivingTs: List<Long> get() = kept.sortedByDescending { it.ts }.map { it.ts }
        val inserts: List<ParamSnapshot> get() = kept.toList()
        var pruneCalls = 0
        var lastPruneKeep: Int? = null

        override suspend fun insert(snapshot: ParamSnapshot): Long {
            val withId = snapshot.copy(id = nextId++)
            kept += withId
            return withId.id
        }

        override suspend fun getAll(): List<ParamSnapshot> = kept.sortedByDescending { it.ts }

        override suspend fun pruneToMostRecent(keep: Int) {
            pruneCalls++
            lastPruneKeep = keep
            val doomed = kept.sortedByDescending { it.ts }.drop(keep.coerceAtLeast(0))
                .map { it.id }.toSet()
            kept.removeAll { it.id in doomed }
        }
    }

    private class FakeAppSettingDao : AppSettingDao {
        private val flow = MutableStateFlow<List<AppSetting>>(emptyList())

        override fun observeAll(): Flow<List<AppSetting>> = flow

        override suspend fun upsertAll(rows: List<AppSetting>) {
            val byKey = flow.value.associateBy { it.key }.toMutableMap()
            rows.forEach { byKey[it.key] = it }
            flow.value = byKey.values.toList()
        }

        override suspend fun clearAll() {
            flow.value = emptyList()
        }
    }

    /**
     * 假训练器：`train` 回一份写好的报告（或 null），`evaluate` 按"是不是候选参数"回两份不同 loss。
     * 记下了被调次数与最后一次收到的 init / iters / packed，好让断言能说清"根本没训练"这类事。
     */
    private class FakeTrainer(
        var report: TrainReport? = null,
        var evalOf: (DoubleArray) -> EvalMetrics? = { null },
        var throws: Throwable? = null,
    ) : FsrsTrainer {
        var trainCalls = 0
        var evaluateCalls = 0
        var lastInit: DoubleArray? = null
        var lastIters: Int = -1
        var lastPacked: Packed? = null

        override fun train(packed: Packed, init: DoubleArray, iters: Int): TrainReport? {
            trainCalls++
            lastInit = init
            lastIters = iters
            lastPacked = packed
            throws?.let { throw it }
            return report
        }

        override fun evaluate(packed: Packed, params: DoubleArray): EvalMetrics? {
            evaluateCalls++
            return evalOf(params)
        }
    }

    /** 10 桶每桶 50 条、预测与实测差 1pp —— 软门槛看得见的"干净"分桶。 */
    private fun cleanBins(dev: Float = 0.01f, n: Int = 50) = List(10) { i ->
        val pred = 0.05f + i * 0.1f
        Bin(pred = pred, obs = (pred + dev).coerceAtMost(1f), n = n)
    }

    private fun report(
        params: FloatArray = defaultFloats,
        nTerms: Int = 1_040,
        lossFirst: Float = 0.55f,
        lossLast: Float = 0.40f,
    ) = TrainReport(
        params = params, lossFirst = lossFirst, lossLast = lossLast,
        nItems = 1_300, nTerms = nTerms, ms = 1_200L,
    )

    /**
     * 一组"比默认略好、但仍讲常识"的候选参数：只把前四档首评稳定度抬高约 4%，
     * 顺序 `w0<w1<w2<w3` 保持不动 —— 这样软门槛（回测间隔比 / 保留率漂移）也真的在**允许区间内**，
     * 采纳路径才测得下去。
     */
    private fun tunedParams(): DoubleArray = DoubleArray(FsrsModel.NP) { i ->
        when (i) {
            0 -> 0.24
            1 -> 1.35
            2 -> 2.40
            3 -> 8.40
            else -> FsrsModel.DEFAULT_PARAMS[i]
        }
    }

    /**
     * "训出了更好的参数"的假训练器。
     *
     * ⚠ 比较键必须是**报告里那份 float 再回读成 double**（[TrainReport.paramsAsDoubles]），
     * 而不是传进来的原始 double：JNI 的载体是 FloatArray，`double→float→double` 有尾差，
     * 用原始 double 比会认不出"这就是候选参数"，于是 cur 与 cand 拿到同一个 loss，门禁只剩"改善不足"。
     */
    private fun betterTrainer(
        cand: DoubleArray = tunedParams(),
        candLoss: Float = 0.40f,
        bins: List<Bin> = cleanBins(),
    ): FakeTrainer {
        val rep = report(params = FloatArray(FsrsModel.NP) { cand[it].toFloat() })
        val candDoubles = rep.paramsAsDoubles()
        return FakeTrainer(
            report = rep,
            evalOf = { p ->
                val loss = if (p.contentEquals(candDoubles)) candLoss else 0.50f
                EvalMetrics(logloss = loss, bins = bins, nTerms = 1_040)
            },
        )
    }

    private class Harness(
        val vm: CalibrateViewModel,
        val logs: FakeReviewLogDao,
        val snapshots: FakeParamSnapshotDao,
        val settings: SettingsRepository,
        val trainer: FakeTrainer,
        val logsSeen: MutableList<Pair<String, Throwable?>>,
    )

    private fun harness(
        rows: List<ReviewLog>,
        trainer: FakeTrainer,
        seedActiveParams: DoubleArray? = null,
    ): Harness {
        val logDao = FakeReviewLogDao(rows)
        val snapDao = FakeParamSnapshotDao()
        val settingDao = FakeAppSettingDao()
        val settings = SettingsRepository(settingDao)
        val seen = mutableListOf<Pair<String, Throwable?>>()
        runBlocking {
            // 直接写库播种"已采纳过"的样子，走的是真实的那条键值口径
            settingDao.upsertAll(
                (
                    seedActiveParams?.let {
                        listOf(AppSetting(AppSettings.KEY_FSRS_ACTIVE_PARAMS, it.joinToString(",")))
                    } ?: emptyList()
                    ),
            )
        }
        val vm = CalibrateViewModel(
            reviewLogs = ReviewLogRepository(logDao),
            snapshots = snapDao,
            settings = settings,
            trainer = trainer,
            log = { msg, err -> seen += msg to err },
            now = { nowMs },
        )
        return Harness(vm, logDao, snapDao, settings, trainer, seen)
    }

    // ── 1. 数据不足：连训练都不叫，更不写库 ──────────────────

    @Test
    fun `数据不足时直接失败_不训练不写快照不换活跃参数`() = runBlocking {
        val h = harness(rows(cards = 30), betterTrainer())

        val state = h.vm.run(dryRun = false)

        assertTrue("数据不足该落 Failed，实际是 $state", state is CalibrateState.Failed)
        val reason = (state as CalibrateState.Failed).reason
        assertTrue("理由要带上『数据不足』与原口径的分母：$reason", reason.contains("数据不足"))
        assertTrue("理由里要看得见具体条数与 1000：$reason", reason.contains("/${FsrsGate.MIN_TERMS}"))
        assertEquals("不足量就别烧一次设备训练", 0, h.trainer.trainCalls)
        assertEquals(0, h.snapshots.inserts.size)
        assertNull("活跃参数必须还是没校准过", h.settings.current().fsrsActiveParams)
    }

    @Test
    fun `空日志不崩且给一句人话`() = runBlocking {
        val h = harness(emptyList(), betterTrainer())

        val state = h.vm.run(dryRun = false)

        assertTrue(state is CalibrateState.Failed)
        assertFalse((state as CalibrateState.Failed).reason.isBlank())
        assertEquals(0, h.trainer.trainCalls)
        assertEquals(0, h.snapshots.inserts.size)
    }

    // ── 2. 干跑：只出报告，一次写库都没有 ────────────────────

    @Test
    fun `干跑出报告但不写快照_不换活跃参数_adopt也拒绝`() = runBlocking {
        val h = harness(enoughRows(), betterTrainer())

        val state = h.vm.run(dryRun = true)

        val result = state as? CalibrateState.Result
        assertNotNull("干跑也要出一份报告状态，实际是 $state", result)
        assertTrue("状态要如实标出这是干跑", result!!.dryRun)
        assertFalse("干跑不许把门禁结论说成拒绝：${result.decision}", result.decision is Decision.Reject)
        assertEquals(0, h.snapshots.inserts.size)
        assertNull(h.settings.current().fsrsActiveParams)

        // 干跑跑出来的那份报告不许被 adopt 拿去换核
        assertFalse(h.vm.adopt())
        assertEquals(0, h.snapshots.inserts.size)
        assertNull(h.settings.current().fsrsActiveParams)
    }

    // ── 3. 采纳：写快照 + 置活跃参数 + 只留最近 5 份 ─────────

    @Test
    fun `采纳写一份字段齐全的快照并置活跃参数`() = runBlocking {
        val h = harness(enoughRows(), betterTrainer())
        val result = h.vm.run(dryRun = false) as CalibrateState.Result

        assertTrue(h.vm.adopt())

        val snap = h.snapshots.inserts.single()
        assertEquals(
            "快照存的就是这份报告训出来的 21 参",
            result.report.paramsAsDoubles().toList(),
            snapParamValues(snap).toList(),
        )
        assertEquals(nowMs, snap.ts)
        assertEquals(result.report.nTerms, snap.nTerms)
        assertEquals(result.candidate.logloss.toDouble(), snap.logloss, 1e-9)
        assertEquals(ParamSnapshot.ENGINE_VER, snap.engineVer)
        assertEquals("裁尾必须真的被叫到一次", 1, h.snapshots.pruneCalls)
        assertEquals("裁的目标就是 MAX_KEEP", ParamSnapshot.MAX_KEEP, h.snapshots.lastPruneKeep)

        val active = h.settings.current().fsrsActiveParams
        assertNotNull("采纳之后活跃参数必须真的生效", active)
        assertEquals(21, active!!.size)
        assertArrayEqualsNear(result.report.paramsAsDoubles(), active)
    }

    @Test
    fun `采纳七次之后只剩最新五份`() = runBlocking {
        val h = harness(enoughRows(), betterTrainer())
        // 先塞 7 份历史快照（ts 各不相同，最新的是 7000）
        for (i in 1..7) {
            h.snapshots.insert(
                ParamSnapshot(
                    params = "0.5", ts = i * 1_000L, nTerms = 1_000,
                    logloss = 0.5, engineVer = ParamSnapshot.ENGINE_VER,
                ),
            )
        }
        assertEquals(7, h.snapshots.survivingTs.size)

        h.vm.run(dryRun = false)
        assertTrue(h.vm.adopt())

        val left = h.snapshots.survivingTs
        assertEquals("只留最近 5 份", 5, left.size)
        assertEquals("新写的那份（注入的 now）必须排在最前", nowMs, left.first())
        assertTrue("最旧的两份（1000/2000）该被淘汰", left.none { it == 1_000L || it == 2_000L })
    }

    // ── 4. 恢复默认：清空活跃参数，快照留着 ──────────────────

    @Test
    fun `恢复默认把活跃参数清成null且不动快照`() = runBlocking {
        val seed = DoubleArray(21) { 0.5 + it * 0.01 }
        val h = harness(enoughRows(), betterTrainer(), seedActiveParams = seed)
        assertNotNull(h.settings.current().fsrsActiveParams)

        assertTrue(h.vm.restoreDefault())

        assertNull("恢复默认 = 回到出厂那 21 个值（fsrsActiveParams 为 null）", h.settings.current().fsrsActiveParams)
        assertEquals("恢复默认不该烧掉回滚的本钱", 0, h.snapshots.inserts.size)
    }

    // ── 5. 原生不可用：null 一律 Failed，旧参数原样留着 ──────

    @Test
    fun `训练器返回null时失败且保留旧参数`() = runBlocking {
        val keep = DoubleArray(21) { 0.4 + it * 0.01 }
        val h = harness(enoughRows(), FakeTrainer(report = null), seedActiveParams = keep)

        val state = h.vm.run(dryRun = false)

        assertTrue("原生不可用必须落成 Failed，实际是 $state", state is CalibrateState.Failed)
        assertEquals(0, h.snapshots.inserts.size)
        assertArrayEqualsNear(keep, h.settings.current().fsrsActiveParams)
        assertTrue("要留下一条能查的日志", h.logsSeen.isNotEmpty())
    }

    @Test
    fun `评估器返回null时同样失败且不换核`() = runBlocking {
        val keep = DoubleArray(21) { 0.4 + it * 0.01 }
        val trainer = FakeTrainer(report = report(), evalOf = { null })
        val h = harness(enoughRows(), trainer, seedActiveParams = keep)

        val state = h.vm.run(dryRun = false)

        assertTrue(state is CalibrateState.Failed)
        assertEquals(0, h.snapshots.inserts.size)
        assertArrayEqualsNear(keep, h.settings.current().fsrsActiveParams)
    }

    @Test
    fun `训练器抛异常也只是Failed_绝不冒泡`() = runBlocking {
        val h = harness(
            enoughRows(),
            FakeTrainer(report = report(), throws = IllegalStateException("boom")),
        )

        val state = h.vm.run(dryRun = false)

        assertTrue("异常要被打成 Failed 状态，实际是 $state", state is CalibrateState.Failed)
        assertEquals(0, h.snapshots.inserts.size)
        assertNull(h.settings.current().fsrsActiveParams)
        assertTrue("异常必须带原文落日志", h.logsSeen.any { it.second != null })
    }

    @Test
    fun `host_jvm_上原生训练器如实返回null`() {
        // 与 FsrsNativeGuardTest 同一口径：没有 libfsrsjni.so 时只降级，不炸
        val packed = LogPacker.pack(rows(cards = 4))
        val trainer = NativeFsrsTrainer()
        assertNull(trainer.train(packed, FsrsModel.DEFAULT_PARAMS, iters = 150))
        assertNull(trainer.evaluate(packed, FsrsModel.DEFAULT_PARAMS))
    }

    // ── 6. 门禁结论：Reject → Failed；Manual → Result 带理由，且都不自动换核 ──

    @Test
    fun `门槛拒绝时状态是Failed且不自动采纳`() = runBlocking {
        // loss 一点没降（0.50 → 0.50）→ 硬门槛"改善 ≥1%"不过
        val trainer = FakeTrainer(
            report = report(),
            evalOf = { EvalMetrics(logloss = 0.50f, bins = cleanBins(), nTerms = 1_040) },
        )
        val h = harness(enoughRows(), trainer)

        val state = h.vm.run(dryRun = false)

        assertTrue("拒绝该落成 Failed，实际是 $state", state is CalibrateState.Failed)
        assertTrue((state as CalibrateState.Failed).reason.contains("改善不足"))
        assertFalse("Reject 之后 adopt 不许点头", h.vm.adopt())
        assertEquals(0, h.snapshots.inserts.size)
        assertNull(h.settings.current().fsrsActiveParams)
    }

    @Test
    fun `软门槛存疑时是Result并带着理由_采纳仍是单独动作`() = runBlocking {
        // 校准偏差 20pp → 软门槛不过 → Manual（不自动换，但把报告交给人看）
        val h = harness(enoughRows(), betterTrainer(bins = cleanBins(dev = 0.2f)))

        val state = h.vm.run(dryRun = false)

        val result = state as? CalibrateState.Result
        assertNotNull("Manual 也必须有报告状态好让界面摆出理由，实际是 $state", result)
        val decision = result!!.decision
        assertTrue("软门槛存疑应当是 Manual，实际是 $decision", decision is Decision.Manual)
        assertTrue("Manual 必须带着要给人看的理由", (decision as Decision.Manual).reasons.isNotEmpty())
        assertEquals("报告跑完不许自己换核", 0, h.snapshots.inserts.size)
        assertNull(h.settings.current().fsrsActiveParams)

        // 人工点头之后才写
        assertTrue(h.vm.adopt())
        assertEquals(1, h.snapshots.inserts.size)
        assertNotNull(h.settings.current().fsrsActiveParams)
    }

    @Test
    fun `还没跑过就没有可采纳的东西`() = runBlocking {
        val h = harness(enoughRows(), betterTrainer())

        assertEquals(CalibrateState.Idle, h.vm.ui.value)
        assertFalse(h.vm.adopt())
        assertEquals(0, h.snapshots.inserts.size)
    }

    // ── 7. 常识校验：S0 顺序不对就按拒绝处理 ────────────────

    @Test
    fun `候选参数违反S0常识序时按拒绝处理`() = runBlocking {
        // Again 的首评稳定性比 Easy 还高 —— 违反 S0(Again)≤S0(Hard)≤S0(Good)≤S0(Easy)
        val crazy = DoubleArray(FsrsModel.NP) { FsrsModel.DEFAULT_PARAMS[it] }
        crazy[0] = 99.0
        val h = harness(enoughRows(), betterTrainer(crazy))

        val state = h.vm.run(dryRun = false)

        assertTrue("违反常识序的参数该被拦成 Failed，实际是 $state", state is CalibrateState.Failed)
        assertTrue(
            "理由要说清是哪一条常识不对：${(state as CalibrateState.Failed).reason}",
            state.reason.contains("首评稳定") || state.reason.contains("S0"),
        )
        assertFalse(h.vm.adopt())
        assertEquals(0, h.snapshots.inserts.size)
        assertNull(h.settings.current().fsrsActiveParams)
    }

    @Test
    fun `默认参数本身过得了常识校验`() {
        // 反向闸：这条校验不许把出厂参数自己拦下（否则"恢复默认"以后什么都训不出来）
        assertNull(FsrsGate.s0OrderViolation(FsrsModel.DEFAULT_PARAMS))
        assertNotNull(
            FsrsGate.s0OrderViolation(
                DoubleArray(FsrsModel.NP) { if (it == 0) 99.0 else FsrsModel.DEFAULT_PARAMS[it] },
            ),
        )
    }

    // ── 编排本身：训练起点必须是"在位参数" ──────────────────

    @Test
    fun `训练从在位参数起步而不是硬编码默认`() = runBlocking {
        val active = DoubleArray(21) { 0.3 + it * 0.02 }
        val h = harness(enoughRows(), betterTrainer(), seedActiveParams = active)

        h.vm.run(dryRun = false)

        assertEquals(1, h.trainer.trainCalls)
        assertArrayEqualsNear(active, h.trainer.lastInit)
        assertNotNull("打包结果必须真给了训练器", h.trainer.lastPacked)
        assertEquals(1_040, h.trainer.lastPacked!!.nTrainTerms)
        assertTrue("iters 要真的传下去", h.trainer.lastIters > 0)
    }

    // ── 断言小工具 ────────────────────────────────────────────

    private fun snapParamValues(snap: ParamSnapshot): DoubleArray =
        snap.params.split(",").map { it.toDouble() }.toDoubleArray()

    private fun assertArrayEqualsNear(expected: DoubleArray, actual: DoubleArray?) {
        assertNotNull(actual)
        assertEquals("长度必须同为 21", expected.size, actual!!.size)
        for (i in expected.indices) assertEquals(expected[i], actual[i], 1e-12)
    }
}
