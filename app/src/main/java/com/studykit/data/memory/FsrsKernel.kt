package com.studykit.data.memory

import kotlin.math.pow

/**
 * FSRS v6 公式组（open-spaced-repetition/py-fsrs 官方实现的真版本，Anki 25.07+ 同源）。
 *
 * 逐值对齐 `py-fsrs==6.3.2` 的 `Scheduler` 私有原语（`_initial_stability` / `_initial_difficulty`
 * / `_next_difficulty` / `_next_stability` / `_short_term_stability`），黄金值钉死在
 * `docs/evidence/fsrs6-golden.json` + `docs/evidence/2026-10-04-fsrs6-oracle.md`（预言机直算，非手推）。
 *
 * 相对基线"简化 FSRS-5"（16 权重、decay 固定 −0.5）的真实 v6 差异：
 *  - 参数向量补到 **21 项**：`w[16]`=easy_bonus、`w[17..19]`=intra-day 短期稳定性、`w[20]`=decay 可学习参数；
 *  - **retrievability 的 decay 不再固定**：`DECAY = -w[20] = -0.1542`，`FACTOR = 0.9^(1/DECAY)−1 ≈ 0.980346`；
 *  - **lapse 支**改用 `S'_f = min(长期项, S/e^(w17·w18))`（下限 `STABILITY_MIN=0.001`），不再是 v5 的"地板 1 天 / 上限=当前 S"；
 *  - **recall 支**新增 `easy_bonus = w[16]`（Easy 触发），hard 惩罚继续用 `w[15]`；
 *  - **均值回归目标** `arg1 = D0(Easy)` **不夹取**（= −4.7716…），与库 `clamp=False` 一致；
 *  - **intra-day 支**（`elapsedDays<1`）落 `short_term_stability`（Hard/Good/Easy 增幅抬到 ≥1，Again 不抬）。
 *
 * 契约保持：`review` 的分支装配沿用"首次落 S0/D0 不生长、非首次数值演化"的口径，
 * 公共签名与 [KernelState]/[CardState] 不变（StudyViewModel / MistakeScheduling 接线不受影响）。
 * `FSRS_HALF_OVER_S`（跨内核展示镜像比，A-T2 双审定调）与难度镜像 [halfDifficultyFromFsrs]
 * 是 StudyKit 侧约定，**与 v6 decay 无耦合，本文件不改**。
 *
 * h/S 比值常量定义在 SchedulingKernel.kt（单一归属）。
 */
class FsrsKernel(private val w: DoubleArray = FsrsModel.DEFAULT_PARAMS) : SchedulingKernel {

    override val id = "FSRS"

    override fun recall(state: KernelState, elapsedDays: Double): Double {
        val s = sanitizeStability(state.stability)
        val t = if (elapsedDays.isFinite()) elapsedDays.coerceAtLeast(0.0) else 0.0
        return FsrsModel.recall(w, s, t)
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
            KernelRating.EASY -> 4.0 // 本 App 极少产生（D5），但 v6 真实现保留 easy_bonus 语义
        }
        val t = if (elapsedDays.isFinite()) elapsedDays.coerceAtLeast(0.0) else 0.0

        val nextS: Double
        val nextD: Double
        if (firstTime) {
            // py-fsrs：首次评分只落 S0=w[G-1] 与 D0(G)（夹 [1,10]），不套增长/lapse 公式。
            nextS = FsrsModel.initStability(w, rating.ordinal + 1)
            nextD = FsrsModel.initDifficulty(w, r)
        } else {
            val s = sanitizeStability(state.stability)
            val d = sanitizeDifficulty(state.difficulty)
            nextS = if (t < 1.0) {
                FsrsModel.shortTermStability(w, s, r)
            } else {
                val rr = recall(KernelState(s, d, state.cardState), t)
                if (rating == KernelRating.AGAIN) FsrsModel.forgetStability(w, s, d, rr)
                else FsrsModel.recallStability(w, s, d, rr, rating.ordinal + 1)
            }
            nextD = FsrsModel.nextDifficulty(w, d, r)
        }
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
        // dr 夹取、raw 反解、脏上限兜底全在 FsrsModel.nextIntervalDays（与训练同式）
        return FsrsModel.nextIntervalDays(w, s, targetRecall, maxIntervalDays)
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

    private fun sanitizeStability(raw: Double?): Double =
        if (raw == null || !raw.isFinite() || raw <= 0.0) MIN_STABILITY_FLOOR else raw.coerceAtMost(MAX_STABILITY)

    /** 与 MemoryModel.clampDifficulty 同口径：脏值（含 NaN）回 1.0，不会把 NaN 带进公式 */
    private fun sanitizeDifficulty(raw: Double): Double =
        if (!raw.isFinite() || raw < 1.0) 1.0 else raw.coerceAtMost(10.0)

    companion object {
        /** v6 把 decay 变成可学习参数 `w[20]`（py-fsrs 默认 0.1542）；`DECAY = −w[20]` */
        const val DECAY_PARAM = 0.1542
        val DECAY = -DECAY_PARAM
        /** 由 decay 反推：`0.9^(1/DECAY) − 1`（v5 曾固定 19/81，v6 随 decay 参数化） */
        val FACTOR = 0.9.pow(1.0 / DECAY) - 1.0
        const val MIN_STABILITY_FLOOR = 0.001 // 对齐 py-fsrs STABILITY_MIN
        const val MAX_STABILITY = 36500.0
        /** 非有限上限的兜底：与 MemoryParams.absoluteMaxIntervalDays 同值——"排太远≈永久消失"是在位者解释过的保险丝 */
        const val MAX_INTERVAL_FALLBACK = 365.0
        const val TEN_MINUTES_IN_DAYS = 10.0 / 1440.0
    }
}
