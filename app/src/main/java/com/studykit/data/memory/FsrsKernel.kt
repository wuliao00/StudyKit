package com.studykit.data.memory

import kotlin.math.exp
import kotlin.math.pow

/**
 * FSRS-5 公式组（幂律遗忘曲线，decay 固定 −0.5）。
 *
 * 公式形式逐条对齐 `fsrs-rs src/model_v6.rs:28-88` 的已核实锚点（reverse-ref 竞品 02，A 级），
 * 分支守卫取 **v5 口径**：失败支 `S' = clip(w11·D^-w12·((S+1)^w13−1)·e^{(1−R)·w14}, 1, S)`；
 * v6 的 `new_s_min = S/e^{w17·w18}` 同日短支守卫不使用（无优化器、无第四档，spec §2.2/D5）。
 *
 * 权重 w[0..15] 取 open-spaced-repetition/py-fsrs@9446cb0 README 默认表前 16 项
 * （v5/v6 两表这 16 个值一致）。诚实声明：不声称与 Anki 结果相等，黄金轨迹钉的是本实现的定义。
 *
 * h/S 比值用包级常量 `FSRS_HALF_OVER_S`（定义在 SchedulingKernel.kt，A-T2 双审定调的单一归属）。
 */
class FsrsKernel : SchedulingKernel {

    override val id = "FSRS"

    private val w = doubleArrayOf(
        0.212, 1.2931, 2.3065, 8.2956, 6.4133, 0.8334, 3.0194, 0.001,
        1.8722, 0.1666, 0.796, 1.4835, 0.0614, 0.2629, 1.6483, 0.6014,
    )

    override fun recall(state: KernelState, elapsedDays: Double): Double {
        val s = sanitizeStability(state.stability)
        val t = if (elapsedDays.isFinite()) elapsedDays.coerceAtLeast(0.0) else 0.0
        return (1.0 + FACTOR * t / s).pow(DECAY)
    }

    override fun review(
        state: KernelState, elapsedDays: Double, rating: KernelRating, conf: Confidence?,
    ): KernelState {
        // 首次判据只有一个：FSRS 还没写过这份状态（stability=null）。
        // cardState==LEARNING 不算 —— 迁移/镜像回填的半衰期行也可能是 LEARNING，
        // 把它们的 S 顶成 S0 会让首周复习量翻倍（A-T3 controller 修正，测试有防呆用例）。
        val firstTime = state.stability == null
        val r = when (rating) {
            KernelRating.AGAIN -> 1.0
            KernelRating.HARD -> 2.0
            KernelRating.GOOD -> 3.0
            KernelRating.EASY -> 4.0 // 本 App 不产生（D5），保留语义
        }
        val s0 = w[rating.ordinal.coerceIn(0, 3)] // AGAIN/HARD/GOOD/EASY → w[0..3]
        val s = if (firstTime) s0 else sanitizeStability(state.stability)
        val d = if (firstTime) initDifficulty(r) else sanitizeDifficulty(state.difficulty)
        val t = if (elapsedDays.isFinite()) elapsedDays.coerceAtLeast(0.0) else 0.0
        val rr = recall(KernelState(s, d, state.cardState), t)

        val nextS = if (rating == KernelRating.AGAIN) {
            val raw = w[11] * d.pow(-w[12]) * ((s + 1.0).pow(w[13]) - 1.0) * exp((1.0 - rr) * w[14])
            // v5 守卫：lapse 不会涨稳定性，地板 1 天。
            // 链式而不是 coerceIn(1.0, s)：新词首次评分 s=S0=w[0]=0.212 < 1 时后者是空区间会抛异常。
            // s≥1 时两种写法逐比特相同；s<1 时退化为"停在 s 不涨"，语义仍是 lapse 只降不升。
            raw.coerceAtLeast(1.0).coerceAtMost(s)
        } else {
            val hard = if (rating == KernelRating.HARD) w[15] else 1.0
            val inc = exp(w[8]) * (11.0 - d) * s.pow(-w[9]) * (exp((1.0 - rr) * w[10]) - 1.0) * hard
            (s * (inc + 1.0)).coerceAtMost(MAX_STABILITY)
        }
        val nextD = nextDifficulty(d, r)
        return KernelState(
            stability = nextS,
            difficulty = nextD,
            cardState = when (rating) {
                KernelRating.AGAIN -> CardState.RELEARNING
                else -> CardState.REVIEW
            },
            hDays = nextS * FSRS_HALF_OVER_S, // 镜像回填（近似，spec §2.1 双写口径）
            // 仅供切内核读数与展示换算；**禁止**直接喂半衰期标定的阈值（如 MASTERED_HALF_LIFE_DAYS），status 判据见 Task 9 按活跃间隔天数重写
        )
    }

    override fun nextIntervalDays(
        state: KernelState, rating: KernelRating, targetRecall: Double, maxIntervalDays: Double,
    ): Double {
        if (rating == KernelRating.AGAIN) return TEN_MINUTES_IN_DAYS
        val s = sanitizeStability(state.stability)
        val dr = if (targetRecall.isFinite()) targetRecall.coerceIn(0.5, 0.99) else 0.9
        val raw = s / FACTOR * (dr.pow(1.0 / DECAY) - 1.0)
        // 上限本身可能来自脏配置（负数/NaN）——coerceIn 遇空区间会抛，这里按本仓
        // "脏值降级不异常"的口径夹：非有限→回在位者保险丝 MAX_INTERVAL_FALLBACK（365d，"排太远≈永久消失"），负/零→退到 10 分钟地板
        val cap = when {
            !maxIntervalDays.isFinite() -> MAX_INTERVAL_FALLBACK
            else -> maxIntervalDays.coerceAtLeast(TEN_MINUTES_IN_DAYS)
        }
        return raw.coerceIn(0.0, cap)
    }

    override fun seedFromHalfLife(halfLifeDays: Double, difficulty: Double): KernelState {
        val h = if (halfLifeDays.isFinite() && halfLifeDays > 0.0) halfLifeDays else MemoryState.NEW.halfLifeDays
        return KernelState(
            stability = (h / FSRS_HALF_OVER_S).coerceAtLeast(MIN_STABILITY_FLOOR),
            // 入参是半衰期口径难度，此处只做脏值防线，不换算（换算在落库，spec §2.4）
            difficulty = sanitizeDifficulty(difficulty),
            cardState = CardState.REVIEW,
            hDays = h,
        )
    }

    private fun initDifficulty(r: Double): Double =
        (w[4] - exp(w[5] * (r - 1.0)) + 1.0).coerceIn(1.0, 10.0)

    /** D' = d + ΔD·(10−d)/9，随后向 D0(4) 均值回归（w[7]），夹在 [1,10] */
    private fun nextDifficulty(d: Double, r: Double): Double {
        val delta = -w[6] * (r - 3.0)
        val updated = d + (10.0 - d) / 9.0 * delta
        return (w[7] * initDifficulty(4.0) + (1.0 - w[7]) * updated).coerceIn(1.0, 10.0)
    }

    private fun sanitizeStability(raw: Double?): Double =
        if (raw == null || !raw.isFinite() || raw <= 0.0) MIN_STABILITY_FLOOR else raw.coerceAtMost(MAX_STABILITY)

    /** 与 MemoryModel.clampDifficulty 同口径：脏值（含 NaN）回 1.0，不会把 NaN 带进公式 */
    private fun sanitizeDifficulty(raw: Double): Double =
        if (!raw.isFinite() || raw < 1.0) 1.0 else raw.coerceAtMost(10.0)

    companion object {
        const val DECAY = -0.5
        /** 0.9^(1/DECAY) − 1 = 19/81，恰为有理数 */
        const val FACTOR = 19.0 / 81.0
        const val MIN_STABILITY_FLOOR = 0.01
        const val MAX_STABILITY = 36500.0
        /** 非有限上限的兜底：与 MemoryParams.absoluteMaxIntervalDays 同值——"排太远≈永久消失"是在位者解释过的保险丝 */
        const val MAX_INTERVAL_FALLBACK = 365.0
        const val TEN_MINUTES_IN_DAYS = 10.0 / 1440.0
    }
}
