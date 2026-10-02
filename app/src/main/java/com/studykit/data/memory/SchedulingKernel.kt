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
 *  - FSRS 用 [stability]（天）与 [difficulty]；
 *  - Half-Life 用 [hDays]（半衰期，天）与 [difficulty]。
 *
 * [difficulty] 量纲归属：**存的是当前活跃内核自己的难度**，不是某个统一量纲。
 * 跑 FSRS 时它是 FSRS 难度（1..10），跑半衰期时它是 halfD（≥1）。跨内核换算
 * （spec §2.4 的 `fsrsD = 5.0 + (halfD−1)·0.5`）只在落库时做一次（Task 9），
 * 适配器内部绝不静默换算。
 *
 * [stability] 为 null 的含义是：活跃 FSRS 内核还没写过这份状态。[HalfLifeKernel]
 * 的镜像写入只会把它碰过的那些行填成非 null（近似值），FSRS 侧的 null 仍然照常存在。
 *
 * 镜像语义见 spec §2.1：复习只原生更新活跃内核字段，另一字段按换算回填（近似值）。
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

/**
 * v2.6 半衰期内核的适配器：行为必须与直接调 MemoryModel 逐比特一致（黄金轨迹测试守）。
 *
 * 本文件钉住的是"适配器 ≡ 委托"这一层——HalfLifeKernel 每个方法都等于按 v2.6 口径直接调
 * [MemoryModel]；[MemoryModel] 自身公式漂移由 MemoryModelTest 负责，不在这里守。
 */
class HalfLifeKernel : SchedulingKernel {

    override val id = "HALF_LIFE"
    private val params = MemoryParams()

    private fun toState(s: KernelState): MemoryState =
        MemoryState(halfLifeDays = s.hDays ?: MemoryState.NEW.halfLifeDays, difficulty = s.difficulty)

    private fun grade(r: KernelRating): ReviewGrade = when (r) {
        KernelRating.AGAIN -> ReviewGrade.FORGET
        KernelRating.HARD -> ReviewGrade.VAGUE
        KernelRating.GOOD, KernelRating.EASY -> ReviewGrade.RECALL // EASY 折叠进认识是休眠支（D5），不是遗漏分支
    }

    override fun recall(state: KernelState, elapsedDays: Double): Double =
        MemoryModel.recallProbability(elapsedDays, state.hDays ?: MemoryState.NEW.halfLifeDays)

    override fun review(
        state: KernelState, elapsedDays: Double, rating: KernelRating, conf: Confidence?,
    ): KernelState {
        val after = MemoryModel.update(toState(state), elapsedDays, grade(rating), params)
        // 镜像字段：FSRS 语义下的 stability 只是换算近似（spec §2.1 双写口径）
        // cardState 原样带回：生命周期属于 FSRS 侧，半衰期适配器不发明它
        return state.copy(
            hDays = after.halfLifeDays,
            difficulty = after.difficulty,
            stability = after.halfLifeDays / FSRS_HALF_OVER_S,
        )
    }

    override fun nextIntervalDays(
        state: KernelState, rating: KernelRating, targetRecall: Double, maxIntervalDays: Double,
    ): Double = MemoryModel.schedule(
        toState(state), grade(rating), targetRecall, maxIntervalDays, params,
    )

    override fun seedFromHalfLife(halfLifeDays: Double, difficulty: Double): KernelState =
        KernelState(stability = halfLifeDays / FSRS_HALF_OVER_S, difficulty = difficulty,
            cardState = CardState.REVIEW, hDays = halfLifeDays)
}

/**
 * 幂律族 (decay=-0.5) 下 h/S 的常数比值，本文件里这个换算系数的唯一归属。
 *
 * decay=−0.5 时 h/S = (0.5^(1/decay)−1)/(0.9^(1/decay)−1) = 3/(19/81) = 243/19
 * ≈ 12.789473684210526（spec §2.4；reverse-ref 竞品02 台账同值）
 */
const val FSRS_HALF_OVER_S = 243.0 / 19.0
