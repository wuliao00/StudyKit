package com.studykit.data.memory

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * FSRS v6 状态转移的**单一公式事实源**（21 参，纯 Double 函数）。
 *
 * 本对象是从上线的 [FsrsKernel] 里**逐比特原样搬出**的 v6 公式组（open-spaced-repetition/
 * py-fsrs 6.3.2 官方实现，Anki 25.07+ 同源），黄金值钉死在
 * `docs/evidence/fsrs6-golden.json` + `docs/evidence/2026-10-04-fsrs6-oracle.md`。
 *
 * v2.9 抽出它的原因：推理（[FsrsKernel]）与训练（`optimize/FsrsLearner`）必须共用**同一份
 * 公式实现**，否则训出的参数与排期用的曲线会悄悄分叉。每个纯函数首位统一收 `w: DoubleArray`
 * （21 项参数向量），rating 用 `Int(1..4)`（AGAIN=1/HARD=2/GOOD=3/EASY=4）传参，避免依赖枚举。
 *
 * ⚠ 迁移纪律：所有数值、夹取、下限与 [FsrsKernel] 改前完全一致——本文件只做"抽取"，不做"改数值"。
 * `decay`/`factor` 随 `w[20]` 参数化（v6 相对 v5 的真实差异），不再是固定 −0.5 / 19/81。
 */
object FsrsModel {

    /** v6 参数维度 */
    const val NP = 21

    /** FSRS-6 默认参数向量（21 项），逐值取自 py-fsrs 6.3.2 `DEFAULT_PARAMETERS`。 */
    val DEFAULT_PARAMS = doubleArrayOf(
        0.212, 1.2931, 2.3065, 8.2956, 6.4133, 0.8334, 3.0194, 0.001,
        1.8722, 0.1666, 0.796, 1.4835, 0.0614, 0.2629, 1.6483, 0.6014,
        1.8729, 0.5425, 0.0912, 0.0658, 0.1542,
    )

    const val MIN_STABILITY_FLOOR = 0.001 // 对齐 py-fsrs STABILITY_MIN
    const val MAX_STABILITY = 36500.0
    /** 非有限上限的兜底：与 MemoryParams.absoluteMaxIntervalDays 同值——"排太远≈永久消失"是在位者解释过的保险丝 */
    const val MAX_INTERVAL_FALLBACK = 365.0
    const val TEN_MINUTES_IN_DAYS = 10.0 / 1440.0

    /** v6 把 decay 变成可学习参数 `w[20]`（py-fsrs 默认 0.1542）；`DECAY = −w[20]` */
    fun decay(w: DoubleArray): Double = -w[20]

    /** 由 decay 反推：`0.9^(1/DECAY) − 1`（v5 曾固定 19/81，v6 随 decay 参数化） */
    fun factor(w: DoubleArray): Double = 0.9.pow(1.0 / decay(w)) - 1.0

    /** 可提取性 `R = (1 + FACTOR·t/S)^DECAY`（输入须已 sanitize） */
    fun recall(w: DoubleArray, stability: Double, elapsedDays: Double): Double =
        (1.0 + factor(w) * elapsedDays / stability).pow(decay(w))

    /** 首次评分的稳定度 `S0 = w[G-1]`（下限 MIN_STABILITY_FLOOR） */
    fun initStability(w: DoubleArray, g: Int): Double =
        w[(g - 1).coerceIn(0, 3)].coerceAtLeast(MIN_STABILITY_FLOOR)

    /** D0(G) = w[4] − e^(w[5]·(G−1)) + 1，落库口径夹 [1,10] */
    fun initDifficulty(w: DoubleArray, g: Double): Double =
        (w[4] - exp(w[5] * (g - 1.0)) + 1.0).coerceIn(1.0, 10.0)

    /** D0(Easy) 不夹取——v6 均值回归的目标项（= −4.7716…），与库 `clamp=False` 一致 */
    fun initDifficultyRaw(w: DoubleArray, g: Double): Double =
        w[4] - exp(w[5] * (g - 1.0)) + 1.0

    /**
     * D' = clamp( w[7]·D0(Easy)_raw + (1−w[7])·(D + (10−D)·ΔD/9) ), 1, 10 )
     * 其中 ΔD = −w[6]·(G−3)。均值回归目标用**未夹取**的 D0(Easy)，与 py-fsrs 逐比特一致。
     */
    fun nextDifficulty(w: DoubleArray, d: Double, g: Double): Double {
        val delta = -w[6] * (g - 3.0)
        val arg2 = d + (10.0 - d) * delta / 9.0
        val arg1 = initDifficultyRaw(w, 4.0)
        return (w[7] * arg1 + (1.0 - w[7]) * arg2).coerceIn(1.0, 10.0)
    }

    /**
     * 成功支（Hard/Good/Easy）长期稳定性：
     * S·(1 + e^w[8]·(11−D)·S^−w[9]·(e^((1−R)·w[10])−1)·hard_penalty·easy_bonus)。
     * hard_penalty=w[15]（仅 HARD,g=2）｜ easy_bonus=w[16]（仅 EASY,g=4）——v6 相对 v5 多了后者。
     */
    fun recallStability(w: DoubleArray, s: Double, d: Double, rr: Double, rating: Int): Double {
        val hard = if (rating == 2) w[15] else 1.0
        val easy = if (rating == 4) w[16] else 1.0
        val inc = exp(w[8]) * (11.0 - d) * s.pow(-w[9]) * (exp((1.0 - rr) * w[10]) - 1.0) * hard * easy
        return (s * (inc + 1.0)).coerceAtMost(MAX_STABILITY).coerceAtLeast(MIN_STABILITY_FLOOR)
    }

    /**
     * lapse 支（AGAIN）v6 口径：`min(长期项, S/e^(w17·w18))`，下限 `STABILITY_MIN`。
     * 长期项 = w[11]·D^−w[12]·((S+1)^w[13]−1)·e^((1−R)·w[14])。
     * 短上限保证 lapse 一定让 S 下降（`S/e^(w17·w18) < S`），语义仍是"忘记只降不升"。
     */
    fun forgetStability(w: DoubleArray, s: Double, d: Double, rr: Double): Double {
        val long = w[11] * d.pow(-w[12]) * ((s + 1.0).pow(w[13]) - 1.0) * exp((1.0 - rr) * w[14])
        val shortCap = s / exp(w[17] * w[18])
        return min(long, shortCap).coerceAtLeast(MIN_STABILITY_FLOOR)
    }

    /**
     * intra-day 短期稳定性（`elapsedDays < 1`）：
     * `increase = e^(w[17]·(G−3+w[18]))·S^−w[19]`；Hard/Good/Easy 增幅抬到 ≥1（不降），Again(g=1) 不抬。
     */
    fun shortTermStability(w: DoubleArray, s: Double, g: Double): Double {
        var inc = exp(w[17] * (g - 3.0 + w[18])) * s.pow(-w[19])
        if (g >= 2.0) inc = max(inc, 1.0)
        return (s * inc).coerceAtLeast(MIN_STABILITY_FLOOR)
    }

    /**
     * 目标召回 `target` 下的间隔天数（输入 stability 须已 sanitize）。
     * 上限本身可能来自脏配置（负数/NaN）——coerceIn 遇空区间会抛，这里按本仓
     * "脏值降级不异常"的口径夹：非有限→回在位者保险丝 MAX_INTERVAL_FALLBACK（365d），负/零→退到 10 分钟地板。
     */
    fun nextIntervalDays(w: DoubleArray, s: Double, target: Double, maxIntervalDays: Double): Double {
        val dr = if (target.isFinite()) target.coerceIn(0.5, 0.99) else 0.9
        val raw = s / factor(w) * (dr.pow(1.0 / decay(w)) - 1.0)
        val cap = when {
            !maxIntervalDays.isFinite() -> MAX_INTERVAL_FALLBACK
            else -> maxIntervalDays.coerceAtLeast(TEN_MINUTES_IN_DAYS)
        }
        return raw.coerceIn(0.0, cap)
    }
}
