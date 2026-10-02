package com.studykit.data.memory

/** 用户翻面/提交前采集的信心三档；null = 跳过（不阻塞，也不参与超纠正判定）。spec §2.3 */
enum class Confidence { GUESS, FAIR, SURE }

/** 内核视角的评分。AGAIN=忘记/HARD=模糊/GOOD=认识；EASY 存在但本 App 永不产生（spec D5） */
enum class KernelRating { AGAIN, HARD, GOOD, EASY }

/** 卡片生命周期，语义对齐 py-fsrs State（LEARNING=未定 S 的新词） */
enum class CardState { LEARNING, REVIEW, RELEARNING }

/**
 * 一条记忆的内核状态。
 *
 * 一个类同时服务两个内核，字段谁用谁认：
 *  - FSRS 用 [stability]（天，null=还没首次评分）与 [difficulty]（1..10）；
 *  - Half-Life 用 [hDays]（半衰期，天）与 [difficulty]。
 *  镜像语义见 spec §2.1：复习只原生更新活跃内核字段，另一字段按换算回填（近似值）。
 */
data class KernelState(
    val stability: Double?,
    val difficulty: Double,
    val cardState: CardState,
    val hDays: Double? = null,
)

/** 排期内核接口（spec §2.1）。全部纯函数，JVM 可测，零 Android 依赖 */
interface SchedulingKernel {
    /** 预测可提取性 R∈[0,1]。[elapsedDays] 距上次复习的真实天数 */
    fun recall(state: KernelState, elapsedDays: Double): Double

    /** 评一次分出新状态。[conf] 仅用于超纠正侧信道，内核本身可忽略 */
    fun review(
        state: KernelState,
        elapsedDays: Double,
        rating: KernelRating,
        conf: Confidence?,
    ): KernelState

    /** 该排到几天后 */
    fun nextIntervalDays(
        state: KernelState,
        rating: KernelRating,
        targetRecall: Double,
        maxIntervalDays: Double,
    ): Double

    /** 迁移/适配：从半衰期 (h,d) 造状态。FSRS 实现按 spec §2.4 换算，HalfLife 原样带回 */
    fun seedFromHalfLife(halfLifeDays: Double, difficulty: Double): KernelState

    val id: String // "FSRS" | "HALF_LIFE"
}

/** v2.6 半衰期内核的适配器：行为必须与直接调 MemoryModel 逐比特一致（黄金轨迹测试守） */
class HalfLifeKernel : SchedulingKernel {

    override val id = "HALF_LIFE"
    private val params = MemoryParams()

    private fun toState(s: KernelState): MemoryState =
        MemoryState(halfLifeDays = s.hDays ?: MemoryState.NEW.halfLifeDays, difficulty = s.difficulty)

    private fun grade(r: KernelRating): ReviewGrade = when (r) {
        KernelRating.AGAIN -> ReviewGrade.FORGET
        KernelRating.HARD -> ReviewGrade.VAGUE
        KernelRating.GOOD, KernelRating.EASY -> ReviewGrade.RECALL
    }

    override fun recall(state: KernelState, elapsedDays: Double): Double =
        MemoryModel.recallProbability(elapsedDays, state.hDays ?: MemoryState.NEW.halfLifeDays)

    override fun review(
        state: KernelState, elapsedDays: Double, rating: KernelRating, conf: Confidence?,
    ): KernelState {
        val after = MemoryModel.update(toState(state), elapsedDays, grade(rating), params)
        // 镜像字段：FSRS 语义下的 stability 只是换算近似（spec §2.1 双写口径）
        return state.copy(
            hDays = after.halfLifeDays,
            difficulty = after.difficulty,
            stability = after.halfLifeDays / FSRS_SEED_RATIO,
            cardState = if (rating == KernelRating.AGAIN) CardState.RELEARNING else CardState.REVIEW,
        )
    }

    override fun nextIntervalDays(
        state: KernelState, rating: KernelRating, targetRecall: Double, maxIntervalDays: Double,
    ): Double = MemoryModel.schedule(
        toState(state), grade(rating), targetRecall, maxIntervalDays, params,
    )

    override fun seedFromHalfLife(halfLifeDays: Double, difficulty: Double): KernelState =
        KernelState(stability = halfLifeDays / FSRS_SEED_RATIO, difficulty = difficulty,
            cardState = CardState.REVIEW, hDays = halfLifeDays)

    companion object {
        /** 幂律族 (decay=-0.5) 下 h/S 常数比值，spec §2.4 */
        const val FSRS_SEED_RATIO = 12.789473684210526
    }
}
