package com.studykit.optimize

import com.studykit.data.memory.FsrsModel
import java.util.Locale
import kotlin.math.abs

/**
 * 校准分桶里的一桶：Rust 侧把验证集的预测按 `floor(pred × 10)` 归入 0..9 桶
 * （`fsrsjni/src/core.rs::evaluate`），再回传桶内的 pred 均值、obs（实测召回）均值与计数。
 *
 * 空桶（count==0）的 pred/obs 由 JNI 写成 [MetricsParser.EMPTY_SENTINEL]（-1.0），
 * 不是"预测值为 -1"，故判桶一律先看 [n]。
 */
data class Bin(val pred: Float, val obs: Float, val n: Int) {
    /** 计数为 0 即"这桶没有可看的样本"，哨兵值不得参与偏差计算。 */
    val isEmpty: Boolean get() = n <= 0
}

/** 一份参数在验证集上的表现：logloss + 10 个校准分桶 + 参与评估的 term 数。 */
data class EvalMetrics(val logloss: Float, val bins: List<Bin>, val nTerms: Int = 0)

/**
 * `nativeTrain` 的产物（[MetricsParser.parseTrain] 解析而来）。
 *
 * [params] 是 JNI 侧的 21 参 `FloatArray`；要喂给推理/回测的 [FsrsModel] 请用 [paramsAsDoubles]
 * （设置层与调度器存的是 `DoubleArray`，见 [com.studykit.data.Settings.fsrsActiveParams]）。
 */
data class TrainReport(
    val params: FloatArray,
    val lossFirst: Float,
    val lossLast: Float,
    val nItems: Int,
    val nTerms: Int,
    val ms: Long,
) {
    /** 21 参向量转 `DoubleArray`（逐值窄→宽，不做任何夹取）。 */
    fun paramsAsDoubles(): DoubleArray = DoubleArray(params.size) { params[it].toDouble() }

    /** 任一参数非有限（NaN/±Inf）→ 训练崩了，门禁必须 Reject。 */
    fun hasNonFiniteParams(): Boolean = params.any { !it.isFinite() }

    // data class 默认的 equals 对 FloatArray 比的是引用，两份"内容相同"的报告永不相等；
    // 这里按内容重写，方便 T11/T12 的断言与快照对照。
    override fun equals(other: Any?): Boolean =
        this === other ||
            (
                other is TrainReport &&
                    lossFirst == other.lossFirst && lossLast == other.lossLast &&
                    nItems == other.nItems && nTerms == other.nTerms && ms == other.ms &&
                    params.contentEquals(other.params)
                )

    override fun hashCode(): Int {
        var h = params.contentHashCode()
        h = 31 * h + lossFirst.hashCode()
        h = 31 * h + lossLast.hashCode()
        h = 31 * h + nItems
        h = 31 * h + nTerms
        return 31 * h + ms.hashCode()
    }
}

/**
 * 回测重放的汇总：换核后"平均会排多远"与"平均预测保留率"。
 *
 * [n] 是可重放的 term 数（首评与同天复习不计入）。[n] 为 0 时两个均值恒为 **0f**（不是 NaN），
 * 门禁据此判"回测不可比"，绝不做 0/0。
 */
data class Backtest(val meanInterval: Float, val meanPredR: Float, val n: Int = 0)

/**
 * 门禁结论。语义与文档 §②阶段 4 清单一一对应：
 * - [Adopt]：硬门槛 + 软门槛全过，允许换核；
 * - [Manual]：硬门槛过了但软门槛存疑——**不自动换**，弹窗交人工确认（附 [sliceNotes]）；
 * - [Reject]：硬门槛不过，保留旧参数，只 toast。
 */
sealed class Decision {
    object Adopt : Decision()

    /** 软门槛存疑，转人工确认弹窗。 */
    data class Manual(val reasons: List<String>) : Decision()

    /** 硬门槛不过，直接不换核。 */
    data class Reject(val reasons: List<String>) : Decision()
}

/**
 * 把 [FsrsNative] 的两个 `FloatArray` 返回拆成上面的数据类。**纯下标搬运，不做任何夹取**。
 *
 * 布局逐值取自 `fsrsjni/src/lib.rs`（**不是**文档 §②示例里的 19 参版本）：
 * - `nativeTrain`（lib.rs 第 110-111 行）：`FloatArray[21+5]` =
 *   `params[0..20]` + `[lossFirst, lossLast, nItems, nTerms, ms]`
 * - `nativeEvaluate`（lib.rs 第 134-140 行）：`FloatArray[2+30]` =
 *   `[logloss, nTerms]` + 10 桶 × `[predMean, obsMean, count]`，桶序按下标 0→9（即预测值由低到高）；
 *   空桶的 pred/obs 由 Rust 写成 `-1.0`
 *
 * 失败约定：Rust 侧任何 panic / 解析越界都返回**空数组**。
 * [parseTrain] 据此回 `null`（调用方必须保留旧参数）；[parseEvaluate] 回一份 `logloss = NaN` 的
 * 空桶指标——NaN 会让 [FsrsGate.decide] 走"数值异常"拒绝路，脏输入不可能被误判成 Adopt。
 */
object MetricsParser {

    /** 校准分桶数（与 `core.rs::evaluate` 的 `vec![...; 10]` 同）。 */
    const val BIN_COUNT = 10

    /** JNI 给空桶写的哨兵值。 */
    const val EMPTY_SENTINEL = -1f

    /** `nativeEvaluate` 的返回长度：`[logloss, nTerms] + 10×3`。 */
    const val EVAL_OUT_LEN = 2 + BIN_COUNT * 3

    /** `nativeTrain` 的返回长度：`params[21] + 5`。 */
    const val TRAIN_OUT_LEN = FsrsModel.NP + 5

    /** 解析 `nativeEvaluate` 的返回。长度不足（含空数组=失败）→ NaN 指标。 */
    fun parseEvaluate(out: FloatArray): EvalMetrics {
        if (out.size < EVAL_OUT_LEN) return EvalMetrics(Float.NaN, List(BIN_COUNT) { Bin(EMPTY_SENTINEL, EMPTY_SENTINEL, 0) }, 0)
        val bins = ArrayList<Bin>(BIN_COUNT)
        for (i in 0 until BIN_COUNT) {
            val base = 2 + i * 3
            bins += Bin(pred = out[base], obs = out[base + 1], n = out[base + 2].toInt())
        }
        return EvalMetrics(logloss = out[0], bins = bins, nTerms = out[1].toInt())
    }

    /** 解析 `nativeTrain` 的返回。空/短数组（JNI 失败信号）→ null。 */
    fun parseTrain(out: FloatArray): TrainReport? {
        if (out.size < TRAIN_OUT_LEN) return null
        return TrainReport(
            params = out.copyOfRange(0, FsrsModel.NP),
            lossFirst = out[FsrsModel.NP],
            lossLast = out[FsrsModel.NP + 1],
            nItems = out[FsrsModel.NP + 2].toInt(),
            nTerms = out[FsrsModel.NP + 3].toInt(),
            ms = out[FsrsModel.NP + 4].toLong(),
        )
    }
}

/** 回测重放里的一张卡状态（只存公式真正要用的 S 与 D）。 */
data class BacktestState(val stability: Double, val difficulty: Double)

/**
 * 回测所需的"内核"三件事：读召回、排间隔、推状态。
 *
 * 把它做成接口而不是直接写死 [FsrsModel]，是为了让单测能塞替身、并让"回测用的公式 == 推理用的公式"
 * 这件事由**唯一的默认实现** [FsrsModelCore] 承担（与 T3 的口径一致测试同向）。
 */
interface BacktestCore {
    fun recall(w: DoubleArray, st: BacktestState, elapsedDays: Double): Double
    fun interval(w: DoubleArray, st: BacktestState, retention: Double): Double
    fun update(w: DoubleArray, st: BacktestState?, rv: LogPacker.Rev): BacktestState
}

/**
 * [BacktestCore] 的生产实现：逐值调 [FsrsModel]，分支装配与
 * [com.studykit.data.memory.FsrsKernel.review] 同口径——
 * 首评只落 `S0=w[G-1] / D0(G)`；`elapsed<1` 走 intra-day 短期支；否则按 AGAIN 走 lapse 支、
 * 其余走 recall 支，`nextD` 恒用**旧** S、D 同批算完再一起推进。
 *
 * 脏 S/D 的兜底与推理一致（[sanitize]）；但**参数本身**的 NaN 不做兜底——那是门禁该拦的事，
 * 悄悄夹掉只会让坏参数看起来无害。
 */
object FsrsModelCore : BacktestCore {

    override fun recall(w: DoubleArray, st: BacktestState, elapsedDays: Double): Double =
        FsrsModel.recall(w, sanitize(st.stability), if (elapsedDays.isFinite()) elapsedDays.coerceAtLeast(0.0) else 0.0)

    override fun interval(w: DoubleArray, st: BacktestState, retention: Double): Double =
        FsrsModel.nextIntervalDays(w, sanitize(st.stability), retention, FsrsGate.BACKTEST_MAX_INTERVAL_DAYS)

    override fun update(w: DoubleArray, st: BacktestState?, rv: LogPacker.Rev): BacktestState {
        val g = rv.rating.toDouble()
        if (st == null) {
            return BacktestState(FsrsModel.initStability(w, rv.rating), FsrsModel.initDifficulty(w, g))
        }
        val s = sanitize(st.stability)
        val d = if (st.difficulty.isFinite()) st.difficulty.coerceIn(1.0, 10.0) else 1.0
        val t = if (rv.elapsedDays.isFinite()) rv.elapsedDays.toDouble().coerceAtLeast(0.0) else 0.0
        val nextS = if (t < 1.0) {
            FsrsModel.shortTermStability(w, s, g)
        } else {
            val rr = FsrsModel.recall(w, s, t)
            if (rv.rating <= 1) FsrsModel.forgetStability(w, s, d, rr) else FsrsModel.recallStability(w, s, d, rr, rv.rating)
        }
        return BacktestState(nextS, FsrsModel.nextDifficulty(w, d, g))
    }

    /** 与 [com.studykit.data.memory.FsrsKernel] 同式的 stability 兜底。 */
    private fun sanitize(raw: Double): Double =
        if (!raw.isFinite() || raw <= 0.0) FsrsModel.MIN_STABILITY_FLOOR else raw.coerceAtMost(FsrsModel.MAX_STABILITY)
}

/**
 * 「不过门槛不换核」。
 *
 * 这一层是**纯逻辑**：不调 `external fun`、不读库、不碰时间，输入全是已解析好的
 * [EvalMetrics] / [TrainReport] / [Backtest]，所以能在 JVM 上逐条钉测（CI 唯一执行器
 * `testDebugUnitTest` 加载不了 arm 的 `.so`）。真正的编排（打包 → 训练 → 评估 → 回测 → 门禁 →
 * 快照）在 Task 11 的 `CalibrateViewModel`。
 *
 * 门槛分两类，与文档 §②阶段 4 清单一致：
 * - **硬门槛**（数据量 / 数值有效性 / logloss 相对改善）任一不过 → [Decision.Reject]，直接不换；
 * - **软门槛**（校准偏差 / 回测保留率漂移 / 平均间隔比）任一不过 → [Decision.Manual]，
 *   换核动作交给人工确认，绝不自动点头。
 */
object FsrsGate {

    /** 低于这么多 term（可产生损失的复习项）就不训练、不换核。 */
    const val MIN_TERMS = 1000

    /** 验证集 logloss 的**相对**改善下限：`(cur - cand) / cur ≥ 1%`。 */
    const val LOSS_IMPROVE_MIN = 0.01f

    /** 单个分桶（样本够多时）允许的预测-实测最大偏差。 */
    const val BIN_MAX_DEV = 0.08f

    /** 各分桶偏差的均值上限。 */
    const val BIN_MEAN_DEV = 0.05f

    /** 偏差统计只看样本数 ≥ 这么多条的桶（文档 §②`it.n >= 20`）。 */
    const val BIN_MIN_N = 20

    /** 回测预测保留率相对在位者的漂移上限：2 个百分点。 */
    const val RET_DRIFT_PP = 0.02f

    /** 回测平均间隔比（cand/cur）允许区间下界。 */
    const val INTERVAL_RATIO_LO = 0.85f

    /** 回测平均间隔比（cand/cur）允许区间上界。 */
    const val INTERVAL_RATIO_HI = 1.25f

    /**
     * 回测算"平均间隔"时给 [FsrsModel.nextIntervalDays] 的上限（天）。
     * 取 [FsrsModel.MAX_INTERVAL_FALLBACK]（= `MemoryParams.absoluteMaxIntervalDays` 默认 365），
     * **不套**政策层那个"约半个周期"的更紧上限（`MemoryModel.maxIntervalDaysFor`）——
     * 那条政策夹取对所有内核一视同仁，会把两个参数的差异夹平，正好抹掉这里要看的信号。
     */
    const val BACKTEST_MAX_INTERVAL_DAYS = FsrsModel.MAX_INTERVAL_FALLBACK

    /** 回测的排期目标保留率（FSRS 的 requested_retention 默认 0.9）。 */
    const val BACKTEST_RETENTION = 0.9

    /**
     * 门禁判定。阈值全部是本对象上的常量，改一处即改全流程。
     *
     * @param cur 在位（活跃）参数在同一验证集上的指标
     * @param cand 候选（刚训出来的）参数在同一验证集上的指标
     * @param rep 训练报告；候选参数向量在此（[TrainReport.params]）
     * @param btCur / btCand 同一份 items 用两组参数分别 [backtest] 的结果
     * @param sliceNotes 切片样本不足等**只附注不拦截**的说明，原样拼进 [Decision.Manual] 的理由
     */
    fun decide(
        cur: EvalMetrics,
        cand: EvalMetrics,
        rep: TrainReport,
        btCur: Backtest,
        btCand: Backtest,
        sliceNotes: List<String> = emptyList(),
    ): Decision {
        // ── 硬门槛：不过就 Reject，一眼都不看软门槛 ──────────────
        if (rep.nTerms < MIN_TERMS) {
            return Decision.Reject(listOf("数据不足：${rep.nTerms}/$MIN_TERMS"))
        }
        val dirtyBin = cand.bins.any { it.n >= BIN_MIN_N && (!it.pred.isFinite() || !it.obs.isFinite()) }
        if (rep.hasNonFiniteParams() || !cur.logloss.isFinite() || !cand.logloss.isFinite() || dirtyBin) {
            return Decision.Reject(listOf("数值异常"))
        }
        if (cur.logloss <= 0f) {
            // 相对改善的分母不可用（0 或负 loss 只可能来自坏输入），不做比值
            return Decision.Reject(listOf("基准损失不可用"))
        }
        val improve = (cur.logloss - cand.logloss) / cur.logloss
        if (improve < LOSS_IMPROVE_MIN) {
            return Decision.Reject(listOf("改善不足 ${pct1(improve * 100f)}%"))
        }

        // ── 软门槛：任何一条不过 → Manual（弹窗人工确认）────────
        val soft = mutableListOf<String>()

        val devs = cand.bins.filter { it.n >= BIN_MIN_N }.map { abs(it.pred - it.obs) }
        if (devs.isEmpty()) {
            soft.add("校准桶样本过少")
        } else {
            val maxDev = devs.max()
            val meanDev = devs.average()
            if (maxDev > BIN_MAX_DEV || meanDev > BIN_MEAN_DEV) {
                soft.add("校准曲线偏差偏大（最大 ${pct1(maxDev * 100f)}%）")
            }
        }

        val comparable = btCur.n > 0 && btCand.n > 0 &&
            btCur.meanInterval > 0f &&
            btCur.meanInterval.isFinite() && btCand.meanInterval.isFinite() &&
            btCur.meanPredR.isFinite() && btCand.meanPredR.isFinite()
        if (!comparable) {
            soft.add("回测不可比（可重放的复习项为 0 或数值异常）")
        } else {
            val drift = abs(btCand.meanPredR - btCur.meanPredR)
            if (drift > RET_DRIFT_PP) {
                soft.add("预计保留率变化 ${pct1(drift * 100f)}pp")
            }
            val ratio = btCand.meanInterval / btCur.meanInterval
            if (ratio < INTERVAL_RATIO_LO || ratio > INTERVAL_RATIO_HI) {
                soft.add("平均间隔变化 ${signedPct0((ratio - 1f) * 100f)}%")
            }
        }

        return if (soft.isEmpty()) Decision.Adopt else Decision.Manual(soft + sliceNotes)
    }

    /** 首评稳定度的四个档位（rating 1..4 → `w[0..3]`），常识校验就看这一小段。 */
    private val S0_LABELS = listOf("Again", "Hard", "Good", "Easy")

    /**
     * **常识校验**（文档 §阶段5「护栏兜底」里那条最便宜的）：首评稳定度必须随评分单调不降，
     * 即 `S0(Again) ≤ S0(Hard) ≤ S0(Good) ≤ S0(Easy)`。因为 [FsrsModel.initStability] 就是
     * 直接取 `w[G-1]`，这四个数一旦错序，含义是「按 Again 反而记得更久」——用户按错反而被罚得更轻，
     * 排期会把人往错误的按钮上推。
     *
     * 为什么门禁的 loss 门槛拦不住它：训练器优化的是**验证集拟合**，一段小样本能把这四档拟合反；
     * loss 只要降了就会一路 Adopt，而这条序**不参与 loss**，所以必须单独查。
     *
     * 返回 null = 顺序成立（或没法判）；非 null = 一句能说清违反了哪一档的人话，
     * 调用方（[com.studykit.ui.optimize.CalibrateViewModel]）据此把结论按 [Decision.Reject] 处理。
     *
     * 两处刻意**不**管：
     *  - 数组长度 ≠ [FsrsModel.NP]：那是残缺向量，交给 [decide] 的"数值异常"与设置层的脏值口径，
     *    本函数只在长度够读这四档时才发言；
     *  - 四档里有 NaN/Inf：也交给"数值异常"（[TrainReport.hasNonFiniteParams] 那条），免得同一次失败给出两条理由。
     */
    fun s0OrderViolation(w: DoubleArray): String? {
        if (w.size < FsrsModel.NP) return null
        val s0 = (0 until S0_LABELS.size).map { w[it] }
        if (s0.any { !it.isFinite() }) return null
        for (i in 0 until S0_LABELS.size - 1) {
            if (s0[i] > s0[i + 1]) {
                return "首评稳定性顺序不成立：S0(${S0_LABELS[i]})=${fmt3(s0[i])} > S0(${S0_LABELS[i + 1]})=${fmt3(s0[i + 1])}"
            }
        }
        return null
    }

    /**
     * 回测：把真实日志按 [params] **重放**一遍，看换核后平均会排多远、平均预测保留率是多少。
     *
     * 与文档 §②的写法一致：逐卡从"无状态"起步，每条**非首评且 elapsed≥1 天**的复习都先在旧状态上
     * 记一次 `recall`（预测保留率）与 `interval`（该状态按目标保留率会排出的间隔），再推进状态；
     * 首评与同天（elapsed<1）的复习只推进状态、不计入均值。
     * 状态推进走 [FsrsModelCore]，与线上推理同一份公式，故"回测说的"和"换完核以后实际排出来的"
     * 不会分叉。
     *
     * 确定性：纯重放，无 RNG、无 wall-clock。空安全：一条都不可重放时返回 `(0f, 0f, n=0)`，
     * 绝不出现 0/0（[decide] 会把它判成"回测不可比"）。
     */
    fun backtest(
        items: List<LogPacker.Item>,
        params: DoubleArray,
        retention: Double = BACKTEST_RETENTION,
        core: BacktestCore = FsrsModelCore,
    ): Backtest {
        var sumInterval = 0.0
        var sumRecall = 0.0
        var n = 0
        for (it in items) {
            var st: BacktestState? = null
            for (rv in it.reviews) {
                val prev = st
                if (prev != null && rv.elapsedDays >= 1f) {
                    sumRecall += core.recall(params, prev, rv.elapsedDays.toDouble())
                    sumInterval += core.interval(params, prev, retention)
                    n++
                }
                st = core.update(params, prev, rv)
            }
        }
        if (n == 0) return Backtest(meanInterval = 0f, meanPredR = 0f, n = 0)
        return Backtest(meanInterval = (sumInterval / n).toFloat(), meanPredR = (sumRecall / n).toFloat(), n = n)
    }

    private fun pct1(v: Float): String = String.format(Locale.US, "%.1f", v)

    private fun signedPct0(v: Float): String = String.format(Locale.US, "%+.0f", v)

    /** 首评稳定度是"天数"量纲，三位小数够看清错序，又不会把一长串浮点尾差印进人话里。 */
    private fun fmt3(v: Double): String = String.format(Locale.US, "%.3f", v)
}
