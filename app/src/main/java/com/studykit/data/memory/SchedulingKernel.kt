package com.studykit.data.memory

import com.studykit.data.entity.Word

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

/**
 * DB 行 → 内核状态（Task 9 接线层唯一的取入口）。
 *
 * 谁用谁认：FSRS 读 [KernelState.stability]，半衰期读 [KernelState.hDays]，两个内核各自
 * 都能从同一行拿到自己要的那份，互不冒领（spec §2.1）。所以这一份映射**不含内核偏好**
 * ——要按活跃内核挑难度量纲的排期用法走 [kernelStateFor]。
 *
 * [KernelState.stability] 为 null 即"FSRS 未评过"（新词、或 `MIGRATION_6_7` 没回填的老行），
 * LEARNING 由 `fsrs_state` 列携带。`fsrs_difficulty` 为空时**直传** `words.difficulty`：
 * 那是"HALF_LIFE 行没被 FSRS 写过"的情形，量纲是半衰期口径，FSRS 内部的
 * sanitizeDifficulty 会把它夹进 [1,10] 再算，不会因此抛异常，
 * 但这条行第一次被 FSRS 评分时本来就会走 `firstTime` 原生支（A-T3 判据）。
 */
fun kernelStateOf(word: Word): KernelState = KernelState(
    stability = word.fsrsStability,
    difficulty = word.fsrsDifficulty ?: word.difficulty.coerceIn(1.0, 10.0),
    cardState = CardState.entries.getOrElse(word.fsrsState - 1) { CardState.LEARNING },
    hDays = word.halfLifeDays,
)

/**
 * **活跃内核**视角的行状态：排期（[SchedulingKernel.review] / [SchedulingKernel.nextIntervalDays]）
 * 与按钮预览都必须用它，不许两处各造一份状态 —— 预览与实排分家是 `MemoryModel.preview` 的注释专门警告过的错。
 *
 * 与 [kernelStateOf] 只差一维：难度两个内核各自的量纲不同（spec §2.1：`words.difficulty`
 * 存的永远是**当前活跃内核自己**的那份，跨内核换算只在落库做一次），所以跑半衰期时
 * 必须读 `words.difficulty`，不能借 `fsrs_difficulty`：后者是 FSRS 口径
 * （迁移与落库都按 [fsrsDifficultyFromHalfLife] 折算过），喂进半衰期公式会把间隔排错。
 */
fun kernelStateFor(kernel: SchedulingKernel, word: Word): KernelState {
    val state = kernelStateOf(word)
    return if (kernel.id == "HALF_LIFE") state.copy(difficulty = word.difficulty) else state
}

/**
 * 读数用的可提取性：活跃内核**原生写过**这条才用它自己的曲线。
 *
 * FSRS 还没写过（[KernelState.stability] 为 null：新词、或 `MIGRATION_6_7` 只给有历史的行
 * 回填过）时不许拿空状态去算——`sanitizeStability` 会把 null 当 0.01 天稳定度，于是
 * "没记过 FSRS 账"被读成"只记得 14%"，那是凭空造出来的坏消息（同 [HalfLifeKernel] 的镜像
 * 只碰过它自己写过的那些行）。这种行退回半衰期镜像列：`half_life_days` 才是这条记忆在
 * v2.6 口径下的真身，也正是 FSRS 未写时唯一可信的那份读数。
 *
 * 只用于**展示与提醒**（明日预告、通知正文、评分按钮上那行"还记得 X%"、`p_at_review`）。
 * 评分路径不能这么退：`stability == null` 在 FSRS 里就是"从 S0 起步"的正确语义（A-T3）。
 */
fun recallForDisplay(kernel: SchedulingKernel, state: KernelState, elapsedDays: Double): Double =
    if (kernel.id == "FSRS" && state.stability == null) {
        MemoryModel.recallProbability(elapsedDays, state.hDays ?: MemoryState.NEW.halfLifeDays)
    } else {
        kernel.recall(state, elapsedDays)
    }

/** 页面上的三档 → 内核的四档（EASY 本 App 永不产生，spec D5）。与 [HalfLifeKernel] 内部的折叠口径一致 */
fun ReviewGrade.toKernelRating(): KernelRating = when (this) {
    ReviewGrade.FORGET -> KernelRating.AGAIN
    ReviewGrade.VAGUE -> KernelRating.HARD
    ReviewGrade.RECALL -> KernelRating.GOOD
}

/**
 * 半衰期口径难度 → FSRS 口径（spec §2.4 的线性映射），**只在落库时做一次**：
 * 内核内部绝不静默换算（见 [KernelState] 的 [KernelState.difficulty] 量纲说明），
 * 式子与 `AppDatabase.MIGRATION_6_7` 回填 `fsrs_difficulty` 的那条 SQL 同式，两处必须一致。
 */
fun fsrsDifficultyFromHalfLife(halfLifeDifficulty: Double): Double = 5.0 + (halfLifeDifficulty - 1.0) * 0.5
