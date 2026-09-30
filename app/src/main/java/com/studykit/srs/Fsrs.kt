package com.studykit.srs

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/** 复习评分四档，对应 FSRS 的 Grade 1..4 */
enum class Rating(val grade: Int) {
    AGAIN(1),
    HARD(2),
    GOOD(3),
    EASY(4),
}

/** 用户在作答前对「我到底记不记得」的自评，用于元认知校准与超纠正排期 */
enum class Confidence(val level: Int) {
    GUESS(0),
    VAGUE(1),
    SURE(2),
}

/**
 * 记忆状态：FSRS 用稳定性 S（天）与难度 D（1..10）两个量刻画一条记忆。
 *
 * S 的定义是「保留率降到 90% 所需的天数」，因此它可以直接当作排期基准。
 */
data class MemoryState(
    val stability: Double,
    val difficulty: Double,
    val lastReviewAt: Long,
    val reps: Int,
    val lapses: Int,
)

/** 一次复习的排期结果 */
data class SchedulingResult(
    val state: MemoryState,
    val dueAt: Long,
    val intervalDays: Int,
    val desiredRetention: Double,
)

/**
 * FSRS（Free Spaced Repetition Scheduler）v4 实现。
 *
 * 公式取自 open-spaced-repetition/awesome-fsrs 官方 wiki「The Algorithm」FSRS v4 一节：
 * - 初始稳定性 S0(G) = w[G-1]
 * - 初始难度 D0(G) = w4 - (G-3)·w5
 * - 复习后难度 D' = w7·D0(3) + (1-w7)·(D - w6·(G-3))（线性阻尼 + 均值回归，防「ease hell」）
 * - 遗忘曲线 R(t,S) = (1 + t/(9S))^-1，即 t=S 时 R=90%
 * - 目标保留率 r 的间隔 I(r,S) = 9S·(1/r - 1)
 * - 成功复习后 S'r = S·(e^w8·(11-D)·S^-w9·(e^(w10(1-R))-1)·hardPenalty·easyBonus + 1)
 * - 遗忘之后   S'f = w11·D^-w12·((S+1)^w13 - 1)·e^(w14(1-R))
 *
 * 取代原先「认识 +1 天 / +3 天」的固定阶梯：间隔由模型按材料与复习历史逐条推算，
 * 这也是分布式练习（间隔效应）在工程上的落点。
 */
object Fsrs {

    /** FSRS v4 默认参数（wiki 给出） */
    val DEFAULT_W: DoubleArray = doubleArrayOf(
        0.4, 0.6, 2.4, 5.8,          // w0..w3 初始稳定性（Again/Hard/Good/Easy）
        4.93, 0.94,                   // w4 初始难度基准，w5 评分偏移
        0.86, 0.01,                   // w6 难度阻尼，w7 均值回归权重
        1.49, 0.14, 0.94,             // w8..w10 成功稳定性更新
        2.18, 0.05, 0.34, 1.26,       // w11..w14 遗忘稳定性更新
        0.29, 2.61,                   // w15 Hard 惩罚，w16 Easy 奖励
    )

    const val DAY_MS = 24L * 60L * 60L * 1000L
    const val RELEARN_MS = 10L * 60L * 1000L

    /** 默认目标保留率 90%（FSRS 的 S 定义点，也是「最优复习量」的常用折中） */
    const val DEFAULT_RETENTION = 0.9

    private const val MIN_STABILITY = 0.1
    private const val MIN_INTERVAL_DAYS = 1

    /** 稳定性下限，避免间隔归零 */
    private fun Double.clampStability(): Double = this.coerceAtLeast(MIN_STABILITY)

    /** 难度必须落在 [1,10] */
    private fun Double.clampDifficulty(): Double = this.coerceIn(1.0, 10.0)

    private fun initStability(rating: Rating): Double = DEFAULT_W[rating.grade - 1]

    private fun initDifficulty(rating: Rating): Double =
        (DEFAULT_W[4] - (rating.grade - 3) * DEFAULT_W[5]).clampDifficulty()

    /** 首次评分后的初始记忆状态 */
    fun firstRating(rating: Rating, now: Long): MemoryState = MemoryState(
        stability = initStability(rating).clampStability(),
        difficulty = initDifficulty(rating),
        lastReviewAt = now,
        reps = 1,
        lapses = if (rating == Rating.AGAIN) 1 else 0,
    )

    /** 距上次复习 [elapsedDays] 天后（或 [at] 时刻）的预测保留率 */
    fun retrievability(elapsedDays: Double, stability: Double): Double {
        if (stability <= 0.0) return 0.0
        val t = elapsedDays.coerceAtLeast(0.0)
        // R = (1 + t/(9S))^-1
        return (1.0 + t / (9.0 * stability)).pow(-1.0)
    }

    fun retrievability(state: MemoryState, at: Long): Double =
        retrievability((at - state.lastReviewAt).toDouble() / DAY_MS, state.stability)

    /** 在目标保留率 r 下，稳定性 S 对应的间隔天数（至少 1 天） */
    fun intervalDays(stability: Double, desiredRetention: Double): Int {
        if (stability <= 0.0) return MIN_INTERVAL_DAYS
        val r = desiredRetention.coerceIn(0.5, 0.99)
        // I = 9S(1/r - 1)
        val raw = 9.0 * stability * (1.0 / r - 1.0)
        return raw.roundToInt().coerceAtLeast(MIN_INTERVAL_DAYS)
    }

    /** 复习后的难度（线性阻尼 + 均值回归） */
    fun nextDifficulty(difficulty: Double, rating: Rating): Double {
        val damped = difficulty - DEFAULT_W[6] * (rating.grade - 3)
        val reverted = DEFAULT_W[7] * initDifficulty(Rating.GOOD) + (1 - DEFAULT_W[7]) * damped
        return reverted.clampDifficulty()
    }

    /** 成功 recall 后的新稳定性 */
    fun nextStabilitySuccess(
        stability: Double,
        difficulty: Double,
        retrievability: Double,
        rating: Rating,
    ): Double {
        val hardPenalty = if (rating == Rating.HARD) DEFAULT_W[15] else 1.0
        val easyBonus = if (rating == Rating.EASY) DEFAULT_W[16] else 1.0
        val increment = exp(DEFAULT_W[8]) *
            (11.0 - difficulty) *
            stability.pow(-DEFAULT_W[9]) *
            (exp(DEFAULT_W[10] * (1.0 - retrievability)) - 1.0) *
            hardPenalty *
            easyBonus
        // increment >= 0，因此新稳定性不会低于原稳定性（SInc >= 1）
        return (stability * (increment + 1.0)).clampStability()
    }

    /** 遗忘（Again）后的新稳定性；不会高于遗忘前 */
    fun nextStabilityLapse(
        stability: Double,
        difficulty: Double,
        retrievability: Double,
    ): Double {
        val lapse = DEFAULT_W[11] *
            difficulty.pow(-DEFAULT_W[12]) *
            ((stability + 1.0).pow(DEFAULT_W[13]) - 1.0) *
            exp(DEFAULT_W[14] * (1.0 - retrievability))
        return lapse.coerceAtMost(stability).clampStability()
    }

    /**
     * 处理一次复习，返回新的记忆状态与下次到期时间。
     *
     * Again 走当日重学（默认 10 分钟后回到队列），其余按目标保留率排到未来某天。
     */
    fun review(
        state: MemoryState,
        rating: Rating,
        now: Long,
        desiredRetention: Double = DEFAULT_RETENTION,
    ): SchedulingResult {
        val elapsedDays = ((now - state.lastReviewAt).toDouble() / DAY_MS).coerceAtLeast(0.0)
        val r = retrievability(elapsedDays, state.stability)
        val newDifficulty = nextDifficulty(state.difficulty, rating)
        val newStability = if (rating == Rating.AGAIN) {
            nextStabilityLapse(state.stability, newDifficulty, r)
        } else {
            nextStabilitySuccess(state.stability, newDifficulty, r, rating)
        }
        val interval = if (rating == Rating.AGAIN) 0 else intervalDays(newStability, desiredRetention)
        val dueAt = if (rating == Rating.AGAIN) now + RELEARN_MS else now + interval * DAY_MS
        return SchedulingResult(
            state = MemoryState(
                stability = newStability,
                difficulty = newDifficulty,
                lastReviewAt = now,
                reps = state.reps + 1,
                lapses = state.lapses + if (rating == Rating.AGAIN) 1 else 0,
            ),
            dueAt = dueAt,
            intervalDays = interval,
            desiredRetention = desiredRetention,
        )
    }

    /** 由已有记忆状态推断「下次到期时间」，用于历史数据迁移（旧版只有 next_review_at） */
    fun dueAt(state: MemoryState, desiredRetention: Double = DEFAULT_RETENTION): Long =
        state.lastReviewAt + intervalDays(state.stability, desiredRetention) * DAY_MS

    /** 保留率低于该阈值即视为「欠账」，优先排队 */
    const val OVERDUE_THRESHOLD = 0.9

    /** ln(0.9)/ln(r) 形式在幂曲线下不需要，保留常量以便将来切换 4.5/5 的 DECAY 时复用 */
    internal val DECAY: Double = -1.0
    internal val FACTOR: Double = 1.0 / 9.0
}
