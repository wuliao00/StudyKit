package com.studykit.optimize

import com.studykit.data.memory.FsrsModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task 10 单测：[FsrsGate]「不过门槛不换核」的决策层 + [MetricsParser]（JNI FloatArray 解析）
 * + [backtest] 回测重放。
 *
 * **纯 JVM**：不调任何 `external fun`（CI 的 x86 跑不了 arm 的 `.so`）。原生返回的
 * `FloatArray` 在这里用**手搭数组**喂给解析器，布局逐值取自 `fsrsjni/src/lib.rs`：
 * - `nativeTrain` → `FloatArray[21+5]`：`params[21]` + `[lossFirst, lossLast, nItems, nTerms, ms]`
 *   （lib.rs 第 110-111 行：先 `w.iter()` 装 21 个，再 `extend([f, l, ni, nt, ms])`）
 * - `nativeEvaluate` → `FloatArray[2+30]`：`[logloss, nTerms]` + 10 桶 ×（pred 均值, obs 均值, 计数）
 *   （lib.rs 第 134-140 行：先 `vec![loss, n]`，再逐桶 `extend([pred, obs, c])`；
 *   空桶（c==0）的 pred/obs 由 Rust 写成 **-1.0**）
 *
 * 门禁阈值逐条对齐开发文档 §② `FsrsGate`（参数维度 19→21 适配）：
 * 硬门槛（数据量 / NaN / logloss 改善）不过 → Reject；软门槛（校准偏差 / 回测漂移 / 间隔比）
 * 不过 → Manual（转人工确认）；全过 → Adopt。
 */
class FsrsGateTest {

    private val w = FsrsModel.DEFAULT_PARAMS

    // ── 构造辅助 ──────────────────────────────────────────────

    /** 10 个"干净"分桶：每桶 pred/obs 相差 [dev]，样本数 [n]。 */
    private fun bins(dev: Float = 0.01f, n: Int = 50, from: Int = 0) = List(10) { i ->
        val pred = 0.05f + i * 0.1f
        Bin(pred = pred, obs = (pred + dev).coerceAtMost(1f), n = if (i < from) 0 else n)
    }

    /** 10 个"每桶样本都不足 20"的分桶（触发"校准桶样本过少"）。 */
    private fun thinBins() = List(10) { i -> Bin(pred = 0.05f + i * 0.1f, obs = 0.06f + i * 0.1f, n = 5) }

    private fun metrics(logloss: Float, list: List<Bin> = bins(), nTerms: Int = 5_000) =
        EvalMetrics(logloss = logloss, bins = list, nTerms = nTerms)

    private fun report(
        nTerms: Int = 5_000,
        params: FloatArray = FloatArray(FsrsModel.NP) { 0.5f + it * 0.01f },
        lossFirst: Float = 0.5f,
        lossLast: Float = 0.4f,
    ) = TrainReport(
        params = params, lossFirst = lossFirst, lossLast = lossLast,
        nItems = 1_200, nTerms = nTerms, ms = 900L,
    )

    private fun bt(interval: Float = 10f, predR: Float = 0.9f, n: Int = 500) =
        Backtest(meanInterval = interval, meanPredR = predR, n = n)

    private fun Decision.reasons(): List<String> = when (this) {
        is Decision.Adopt -> emptyList()
        is Decision.Manual -> reasons
        is Decision.Reject -> reasons
    }

    private fun rev(rating: Int, elapsed: Float, state: Int = 2) =
        LogPacker.Rev(rating = rating, elapsedDays = elapsed, scheduledDays = elapsed, state = state)

    private fun item(cardId: Long, vararg reviews: LogPacker.Rev) =
        LogPacker.Item(cardKind = "word", cardId = cardId, firstTs = cardId * 1_000L, reviews = reviews.toList())

    // ── 1. MetricsParser：nativeEvaluate 的 FloatArray[2+30] ──

    @Test fun `parseEvaluate reads logloss and nTerms from the first two slots`() {
        val out = FloatArray(2 + 30)
        out[0] = 0.42f
        out[1] = 1234f
        // 桶 0..8：(pred, obs, count)；桶 9 留 Rust 的空桶写法 (-1, -1, 0)
        for (i in 0 until 9) {
            out[2 + i * 3] = 0.05f + i * 0.1f
            out[2 + i * 3 + 1] = 0.06f + i * 0.1f
            out[2 + i * 3 + 2] = 10f
        }
        out[2 + 9 * 3] = -1f
        out[2 + 9 * 3 + 1] = -1f
        out[2 + 9 * 3 + 2] = 0f

        val m = MetricsParser.parseEvaluate(out)
        assertEquals(0.42f, m.logloss, 0f)
        assertEquals(1234, m.nTerms)
        assertEquals(10, m.bins.size)
        assertEquals(0.05f, m.bins[0].pred, 1e-6f)
        assertEquals(0.06f, m.bins[0].obs, 1e-6f)
        assertEquals(10, m.bins[0].n)
        assertEquals(0.85f, m.bins[8].pred, 1e-6f)
        assertEquals(0.86f, m.bins[8].obs, 1e-6f)
        assertEquals(10, m.bins[8].n)
        // 第 10 桶（下标 9）是 Rust 写的空桶哨兵，原样保留
        assertEquals(-1f, m.bins[9].pred, 0f)
        assertEquals(-1f, m.bins[9].obs, 0f)
        assertEquals(0, m.bins[9].n)
        assertTrue(m.bins[9].isEmpty)
    }

    @Test fun `parseEvaluate keeps the JNI empty-bin sentinel and flags it`() {
        val out = FloatArray(2 + 30) // 全 0，再补空桶哨兵
        out[0] = 0.5f
        out[1] = 0f
        for (i in 0 until 10) {
            out[2 + i * 3] = -1f
            out[2 + i * 3 + 1] = -1f
            out[2 + i * 3 + 2] = 0f
        }
        val m = MetricsParser.parseEvaluate(out)
        assertEquals(10, m.bins.size)
        assertTrue(m.bins.all { it.n == 0 })
        assertTrue(m.bins.all { it.pred == -1f })
        assertTrue(m.bins.all { it.isEmpty })
    }

    @Test fun `parseEvaluate of an empty array is NaN-safe instead of crashing`() {
        val m = MetricsParser.parseEvaluate(FloatArray(0))
        assertTrue(m.logloss.isNaN())
        assertEquals(10, m.bins.size)
        assertTrue(m.bins.all { it.isEmpty })
        assertEquals(0, m.nTerms)
        // 短数组（被截断的 JNI 返回）同样走这条降级路
        assertTrue(MetricsParser.parseEvaluate(FloatArray(10)).logloss.isNaN())
    }

    // ── 2. MetricsParser：nativeTrain 的 FloatArray[21+5] ─────

    @Test fun `parseTrain splits params from the five trailer fields`() {
        val out = FloatArray(FsrsModel.NP + 5)
        for (i in 0 until FsrsModel.NP) out[i] = 0.1f + i * 0.01f
        out[21] = 0.678f // lossFirst
        out[22] = 0.456f // lossLast
        out[23] = 1200f // nItems
        out[24] = 5321f // nTerms
        out[25] = 1234f // ms

        val rep = MetricsParser.parseTrain(out)!!
        assertEquals(FsrsModel.NP, rep.params.size)
        assertEquals(0.1f, rep.params[0], 1e-6f)
        assertEquals(0.3f, rep.params[20], 1e-6f)
        assertEquals(0.678f, rep.lossFirst, 0f)
        assertEquals(0.456f, rep.lossLast, 0f)
        assertEquals(1200, rep.nItems)
        assertEquals(5321, rep.nTerms)
        assertEquals(1234L, rep.ms)
    }

    @Test fun `parseTrain returns null on the empty-array failure signal`() {
        assertNull(MetricsParser.parseTrain(FloatArray(0)))
        assertNull(MetricsParser.parseTrain(FloatArray(FsrsModel.NP + 4)))
    }

    @Test fun `parseTrain keeps two reports with equal params equal`() {
        val a = MetricsParser.parseTrain(FloatArray(FsrsModel.NP + 5) { 1f })!!
        val b = MetricsParser.parseTrain(FloatArray(FsrsModel.NP + 5) { 1f })!!
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    // ── 3. 硬门槛：Reject ─────────────────────────────────────

    @Test fun `rejects when the term count is below MIN_TERMS`() {
        val d = FsrsGate.decide(metrics(0.5f), metrics(0.4f), report(nTerms = FsrsGate.MIN_TERMS - 1), bt(), bt(), emptyList())
        assertTrue(d is Decision.Reject)
        val text = d.reasons().joinToString("；")
        assertTrue(text, text.contains("数据不足"))
        assertTrue(text, text.contains("${FsrsGate.MIN_TERMS - 1}/${FsrsGate.MIN_TERMS}"))
    }

    @Test fun `the MIN_TERMS boundary itself is allowed through`() {
        val d = FsrsGate.decide(metrics(0.5f), metrics(0.4f), report(nTerms = FsrsGate.MIN_TERMS), bt(), bt(), emptyList())
        assertSame(Decision.Adopt, d)
    }

    @Test fun `rejects NaN candidate logloss`() {
        val d = FsrsGate.decide(metrics(0.5f), metrics(Float.NaN), report(), bt(), bt(), emptyList())
        assertTrue(d.reasons().joinToString("；"), d is Decision.Reject)
        assertTrue((d as Decision.Reject).reasons.contains("数值异常"))
    }

    @Test fun `rejects NaN candidate params`() {
        val dirty = FloatArray(FsrsModel.NP) { 0.5f }.also { it[7] = Float.NaN }
        val d = FsrsGate.decide(metrics(0.5f), metrics(0.4f), report(params = dirty), bt(), bt(), emptyList())
        assertTrue(d is Decision.Reject)
        assertTrue((d as Decision.Reject).reasons.contains("数值异常"))
    }

    @Test fun `rejects when loss improvement is under one percent`() {
        // 0.500 → 0.496：相对改善 0.8% < 1%
        val d = FsrsGate.decide(metrics(0.500f), metrics(0.496f), report(), bt(), bt(), emptyList())
        assertTrue(d is Decision.Reject)
        val text = (d as Decision.Reject).reasons.joinToString("；")
        assertTrue(text, text.contains("改善不足"))
        assertTrue(text, text.contains("0.8%"))
    }

    @Test fun `rejects a candidate that is worse than the incumbent`() {
        val d = FsrsGate.decide(metrics(0.4f), metrics(0.6f), report(), bt(), bt(), emptyList())
        assertTrue(d is Decision.Reject)
    }

    @Test fun `rejects when the baseline loss is not usable for a relative comparison`() {
        val d = FsrsGate.decide(metrics(0f), metrics(0.4f), report(), bt(), bt(), emptyList())
        assertTrue(d.reasons().joinToString("；"), d is Decision.Reject)
    }

    @Test fun `rejects a non-finite calibration bin`() {
        val bad = bins().toMutableList().also { it[3] = Bin(0.35f, Float.NaN, n = 40) }
        val d = FsrsGate.decide(metrics(0.5f), metrics(0.4f, bad), report(), bt(), bt(), emptyList())
        assertTrue(d.reasons().joinToString("；"), d is Decision.Reject)
        assertTrue((d as Decision.Reject).reasons.contains("数值异常"))
    }

    // ── 4. 软门槛：Manual ─────────────────────────────────────

    @Test fun `manual when a single bin with enough samples deviates over the max`() {
        val hot = bins().toMutableList().also { it[4] = Bin(0.45f, 0.54f, n = 30) } // dev 0.09 > 0.08
        val d = FsrsGate.decide(metrics(0.5f), metrics(0.4f, hot), report(), bt(), bt(), emptyList())
        assertTrue(d is Decision.Manual)
        assertTrue(d.reasons().toString(), (d as Decision.Manual).reasons.any { it.contains("校准曲线偏差偏大") })
    }

    @Test fun `a bin under the per-bin sample floor is not measured for deviation`() {
        // 唯一的偏桶只有 19 条（<20）→ 不进偏差统计；其余桶都干净 → 仍应 Adopt
        val small = bins().toMutableList().also { it[4] = Bin(0.45f, 0.60f, n = FsrsGate.BIN_MIN_N - 1) }
        val d = FsrsGate.decide(metrics(0.5f), metrics(0.4f, small), report(), bt(), bt(), emptyList())
        assertSame(Decision.Adopt, d)
    }

    @Test fun `manual when the mean bin deviation is over the mean threshold`() {
        // 每桶 dev = 0.06（单桶不超 0.08），但均值 0.06 > 0.05
        val d = FsrsGate.decide(metrics(0.5f), metrics(0.4f, bins(dev = 0.06f)), report(), bt(), bt(), emptyList())
        assertTrue(d is Decision.Manual)
        assertTrue((d as Decision.Manual).reasons.any { it.contains("校准曲线偏差偏大") })
    }

    @Test fun `manual when every bin is too thin to judge calibration`() {
        val d = FsrsGate.decide(metrics(0.5f), metrics(0.4f, thinBins()), report(), bt(), bt(), emptyList())
        assertTrue(d is Decision.Manual)
        val text = (d as Decision.Manual).reasons.joinToString("；")
        assertTrue(text, text.contains("校准桶样本过少"))
    }

    @Test fun `manual when backtest predicted retention drifts over two points`() {
        val d = FsrsGate.decide(
            metrics(0.5f), metrics(0.4f), report(),
            bt(predR = 0.90f), bt(predR = 0.93f), emptyList(),
        )
        assertTrue(d is Decision.Manual)
        val text = (d as Decision.Manual).reasons.joinToString("；")
        assertTrue(text, text.contains("预计保留率"))
        assertTrue(text, text.contains("3.0pp"))
    }

    @Test fun `manual when the mean interval ratio leaves the allowed band`() {
        val tooLong = FsrsGate.decide(metrics(0.5f), metrics(0.4f), report(), bt(interval = 10f), bt(interval = 15f), emptyList())
        assertTrue(tooLong is Decision.Manual)
        assertTrue(tooLong.reasons().toString(), (tooLong as Decision.Manual).reasons.any { it.contains("平均间隔") })

        val tooShort = FsrsGate.decide(metrics(0.5f), metrics(0.4f), report(), bt(interval = 10f), bt(interval = 8f), emptyList())
        assertTrue(tooShort is Decision.Manual)
    }

    @Test fun `interval ratio band edges still adopt`() {
        assertSame(Decision.Adopt, FsrsGate.decide(metrics(0.5f), metrics(0.4f), report(), bt(10f), bt(8.5f), emptyList()))
        assertSame(Decision.Adopt, FsrsGate.decide(metrics(0.5f), metrics(0.4f), report(), bt(10f), bt(12.5f), emptyList()))
    }

    @Test fun `manual when the backtest has nothing to compare`() {
        val d = FsrsGate.decide(metrics(0.5f), metrics(0.4f), report(), bt(interval = 0f, predR = 0f, n = 0), bt(interval = 5f, predR = 0.9f, n = 10), emptyList())
        assertTrue(d is Decision.Manual)
    }

    @Test fun `slice notes ride along with manual reasons`() {
        val notes = listOf("新卡切片仅 120 条，只附注不拦截")
        val d = FsrsGate.decide(metrics(0.5f), metrics(0.4f, thinBins()), report(), bt(), bt(), notes)
        assertTrue(d is Decision.Manual)
        assertEquals(notes, (d as Decision.Manual).reasons.takeLast(notes.size))
    }

    @Test fun `a fully clean run adopts`() {
        val d = FsrsGate.decide(metrics(0.5f), metrics(0.4f), report(), bt(10f, 0.90f), bt(11f, 0.905f), emptyList())
        assertSame(Decision.Adopt, d)
        assertEquals(emptyList<String>(), d.reasons())
    }

    // ── 5. backtest：用推理同一份公式重放 ────────────────────

    @Test fun `backtest replays the same forward as inference for a two-review card`() {
        val items = listOf(item(1L, rev(3, 0f, state = 0), rev(3, 10f)))
        val bt = FsrsGate.backtest(items, w, retention = 0.9)
        val s0 = FsrsModel.initStability(w, 3)
        assertEquals(1, bt.n)
        assertEquals(FsrsModel.recall(w, s0, 10.0).toFloat(), bt.meanPredR, 1e-6f)
        assertEquals(
            FsrsModel.nextIntervalDays(w, s0, 0.9, FsrsGate.BACKTEST_MAX_INTERVAL_DAYS).toFloat(),
            bt.meanInterval, 1e-6f,
        )
    }

    @Test fun `backtest skips same-day reviews but still grows the state`() {
        val items = listOf(item(1L, rev(3, 0f, state = 0), rev(3, 0.5f), rev(3, 10f)))
        val bt = FsrsGate.backtest(items, w, retention = 0.9)
        assertEquals(1, bt.n) // elapsed=0.5 那次不产生可回测项
        val s1 = FsrsModel.initStability(w, 3)
        val s2 = FsrsModel.shortTermStability(w, s1, 3.0) // 同天支与推理同式
        assertEquals(FsrsModel.recall(w, s2, 10.0).toFloat(), bt.meanPredR, 1e-6f)
    }

    @Test fun `backtest averages over every card term`() {
        val items = listOf(
            item(1L, rev(3, 0f, state = 0), rev(3, 10f)),
            item(2L, rev(1, 0f, state = 0), rev(3, 10f)),
        )
        val bt = FsrsGate.backtest(items, w, retention = 0.9)
        assertEquals(2, bt.n)
        val s0Good = FsrsModel.initStability(w, 3)
        val s0Again = FsrsModel.initStability(w, 1)
        val expectedR = (FsrsModel.recall(w, s0Good, 10.0) + FsrsModel.recall(w, s0Again, 10.0)) / 2.0
        assertEquals(expectedR.toFloat(), bt.meanPredR, 1e-6f)
    }

    @Test fun `backtest is deterministic across calls`() {
        val items = listOf(
            item(1L, rev(3, 0f, state = 0), rev(2, 4f), rev(1, 20f)),
            item(2L, rev(4, 0f, state = 0), rev(3, 7f)),
        )
        assertEquals(FsrsGate.backtest(items, w, 0.9), FsrsGate.backtest(items, w, 0.9))
    }

    @Test fun `backtest reacts to the params it is given`() {
        val items = listOf(item(1L, rev(3, 0f, state = 0), rev(3, 10f)))
        val stronger = w.copyOf().also { it[2] = 10.0 } // S0(Good) 更大 → 间隔与预测召回都更宽
        val base = FsrsGate.backtest(items, w, 0.9)
        val high = FsrsGate.backtest(items, stronger, 0.9)
        assertTrue("${base} vs ${high}", high.meanInterval > base.meanInterval)
        assertTrue("${base} vs ${high}", high.meanPredR > base.meanPredR)
    }

    @Test fun `backtest of no items divides by nothing and returns zeros`() {
        val bt = FsrsGate.backtest(emptyList(), w, 0.9)
        assertEquals(0, bt.n)
        assertEquals(0f, bt.meanInterval, 0f)
        assertEquals(0f, bt.meanPredR, 0f)
        assertTrue(!bt.meanInterval.isNaN() && !bt.meanPredR.isNaN())
    }

    @Test fun `backtest of single-review cards has no replayable term`() {
        val bt = FsrsGate.backtest(listOf(item(1L, rev(3, 0f, state = 0)), item(2L, rev(2, 0f, state = 0))), w, 0.9)
        assertEquals(0, bt.n)
        assertEquals(0f, bt.meanInterval, 0f)
    }

    @Test fun `backtest over a synthetic log stays inside the model domain`() {
        val packed = LogPacker.pack(SynthLog.generate(seed = 7L, nCards = 60, reviewsPerCard = 6))
        val items = LogPacker.items(LogPacker.clean(SynthLog.generate(seed = 7L, nCards = 60, reviewsPerCard = 6)))
        assertTrue(items.isNotEmpty())
        assertTrue(packed.nTrainTerms + packed.nValTerms > 0)
        val bt = FsrsGate.backtest(items, w, retention = 0.9)
        assertTrue("n=${bt.n}", bt.n > 0)
        assertTrue(bt.meanPredR > 0f && bt.meanPredR <= 1f)
        assertTrue(bt.meanInterval > 0f && bt.meanInterval <= FsrsGate.BACKTEST_MAX_INTERVAL_DAYS)
    }

    @Test fun `backtest of a NaN param vector stays non-finite so the gate can refuse it`() {
        val dirty = w.copyOf().also { it[20] = Double.NaN } // decay 坏了 → factor/interval 全 NaN
        val bt = FsrsGate.backtest(listOf(item(1L, rev(3, 0f, state = 0), rev(3, 10f))), dirty, 0.9)
        assertEquals(1, bt.n)
        assertTrue(bt.meanPredR.isNaN() || !bt.meanPredR.isFinite())
        val d = FsrsGate.decide(
            metrics(0.5f), metrics(0.4f), report(),
            FsrsGate.backtest(listOf(item(1L, rev(3, 0f, state = 0), rev(3, 10f))), w, 0.9), bt, emptyList(),
        )
        assertTrue(d.reasons().joinToString("；"), d is Decision.Manual)
    }
}
