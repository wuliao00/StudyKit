package com.studykit.data.memory

import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import java.time.LocalDate

/**
 * 一次自我评分。三档是墨墨那套（认识 / 模糊 / 忘记），
 * 而不是 StudyKit 原来的两态布尔 —— 只有"对/错"时模型无法区分
 * "秒答"和"想了半分钟才想起来"，而这两种对记忆强度的贡献差别很大。
 */
enum class ReviewGrade { RECALL, VAGUE, FORGET }

/**
 * 一个记忆单元的模型状态。
 *
 * 只有两个自由度：**半衰期 h**（回忆概率掉到 50% 要多少天）和**难度 d**。
 * 时间戳不在里面 —— 那是 `words.last_review_at` 的事，模型只管"这段记忆本身有多硬"。
 */
data class MemoryState(
    /** 半衰期，单位：天 */
    val halfLifeDays: Double,
    /** 难度，≥1，封顶 [MemoryParams.maxDifficulty] */
    val difficulty: Double,
) {
    companion object {
        /**
         * 冷启动：新词按"半天就忘"起步。
         *
         * 这个数字不是猜的 —— 它决定第一次复习排在哪：h=0.5、target=0.9 时
         * 首复习 ≈ 0.5×log2(1/0.9) ≈ 1.8 小时后，正好落在"当天内再碰一次"。
         */
        val NEW = MemoryState(halfLifeDays = 0.5, difficulty = 1.0)
    }
}

/**
 * 模型参数。默认值逐条抄自墨墨 KDD 2022 论文《A Stochastic Shortest Path Algorithm for
 * Optimizing Spaced Repetition Scheduling》的公开中文解析
 * （https://memodocs.maimemo.com/docs/2022_KDD）。
 *
 * 全部做成可注入：这组系数是在**千万用户**的数据上回归出来的，
 * 单个人身上未必最优。校准（见 MemoryCalibration）只调这里的数字，不改公式结构。
 */
data class MemoryParams(
    /** 成功支：h' = h·(exp(successBias)·d^successD·h^successH·(1−p)^successP + 1) */
    val successBias: Double = 3.81,
    val successD: Double = -0.534,
    val successH: Double = -0.127,
    val successP: Double = 0.970,
    /**
     * 失败支：h' = exp(failBias)·d^failD·h^failH·(1−p)^failP
     *
     * 注意它**不是**"把 h 乘一个小于 1 的系数"，而是直接回归出一个新的 h。
     * 因此 h 很小时"忘记"反而会略微抬高 h —— 这是发布参数的固有行为
     * （不动点 ≈ 1.2 天，见 [MemoryState] 与单测），不是 bug，别在代码里偷偷修正。
     */
    val failBias: Double = -0.041,
    val failD: Double = -0.041,
    val failH: Double = 0.377,
    val failP: Double = -0.227,
    /** 一次忘记给难度加多少 */
    val difficultyStep: Double = 0.1,
    val maxDifficulty: Double = 10.0,
    /** 半衰期的物理边界：20 分钟 ~ 20 年 */
    val minHalfLifeDays: Double = 0.02,
    val maxHalfLifeDays: Double = 7300.0,
    /** 目标准确率兜底（没有考试日期、也没设严格度时） */
    val defaultTargetRecall: Double = 0.9,
    /** 间隔硬上限（天）：Smolen 2016 的"过长间隔同样无效"落到工程上的保险丝 */
    val absoluteMaxIntervalDays: Double = 365.0,
)

/**
 * 半衰期式遗忘模型。纯函数、无 Android 依赖，所以能在 JVM 单测里跑黄金轨迹。
 *
 * ## 三条公式
 * ```
 * 回忆概率   p = 2^(−Δt / h)
 * 成功后     h' = h · (exp(θ1) · d^θ2 · h^θ3 · (1−p)^θ4 + 1)
 * 失败后     h' = exp(φ1) · d^φ2 · h^φ3 · (1−p)^φ4
 * 下次复习   Δt_next = h' · log2(1 / target)
 * ```
 *
 * 成功支里 `(1−p)^0.970` 是**间隔效应的数学体现**：越是在快要忘掉的时候想起来的，
 * 这次提取对记忆的加固越强。所以"卡了一下才答对"不该被惩罚。
 *
 * ## 为什么不用 SM-2 / FSRS
 * SM-2 的 ease factor 是乘性自增、没有概率语义，也拿不到公开拟合系数；
 * FSRS 要在线拟合上千条样本才有意义。这套解析式三个数就能算，
 * 且代入冷启动后能复现墨墨真机上"认识 → 35 天后"的量级（见单测
 * `reproduces_the_interval_printed_on_momo_buttons`）。
 */
object MemoryModel {

    private val LN2 = ln(2.0)

    /**
     * 距上次复习 [gapDays] 天后，还能想起来的概率。
     *
     * h ≤ 0 或非法值一律回 0（当作全忘了）而不是抛异常 —— 数据库里只要有一条脏 h
     * 就足以让整个复习队列崩掉，而"这条排到今天来"是可接受的降级。
     */
    fun recallProbability(gapDays: Double, halfLifeDays: Double, now: Double = 0.0): Double {
        if (!gapDays.isFinite() || !halfLifeDays.isFinite() || halfLifeDays <= 0.0) return 0.0
        val dt = max(0.0, gapDays - now)
        if (dt == 0.0) return 1.0
        // 2^(−dt/h) = exp(−dt/h·ln2)；指数下溢时 exp 自然给 0，不用再判
        return exp(-dt / halfLifeDays * LN2).coerceIn(0.0, 1.0)
    }

    /** 把 h 拉回物理边界，顺手吃掉 NaN/Inf */
    private fun clampHalfLife(h: Double, params: MemoryParams, fallback: Double): Double {
        if (!h.isFinite() || h <= 0.0) return fallback.coerceIn(params.minHalfLifeDays, params.maxHalfLifeDays)
        return h.coerceIn(params.minHalfLifeDays, params.maxHalfLifeDays)
    }

    private fun clampDifficulty(d: Double, params: MemoryParams): Double =
        if (!d.isFinite() || d < 1.0) 1.0 else min(d, params.maxDifficulty)

    /**
     * 评一次分，得到新的记忆状态。
     *
     * @param gapDays 本次复习距上次多少天（模型的自变量，必须来自真实历史）
     * @param grade   认识 / 模糊 / 忘记
     *
     * 模糊的处理：**h 走成功支、d 走失败支**。想起来了就该加固记忆（Roediger & Karpicke
     * 的提取练习效应），但犹豫说明这个条目更难，难度要涨。
     * 直接当成"忘记"会把犹豫的用户一路打回 10 分钟一复习，那是最容易被骂的错法。
     */
    fun update(state: MemoryState, gapDays: Double, grade: ReviewGrade, params: MemoryParams): MemoryState {
        val h = clampHalfLife(state.halfLifeDays, params, MemoryState.NEW.halfLifeDays)
        val d = clampDifficulty(state.difficulty, params)
        val p = recallProbability(gapDays, h)
        // 概率不能真取到 1，否则 (1−p)^θ4 = 0 会让成功支退化成 h'=h（复习完全不涨）
        val oneMinusP = (1.0 - p).coerceAtLeast(1e-3)

        val recalled = grade != ReviewGrade.FORGET
        val nextH = if (recalled) {
            val gain = exp(params.successBias) *
                d.pow(params.successD) *
                h.pow(params.successH) *
                oneMinusP.pow(params.successP)
            h * (gain + 1.0)
        } else {
            exp(params.failBias) *
                d.pow(params.failD) *
                h.pow(params.failH) *
                oneMinusP.pow(params.failP)
        }

        val nextD = if (grade == ReviewGrade.RECALL) d else clampDifficulty(d + params.difficultyStep, params)
        return MemoryState(
            halfLifeDays = clampHalfLife(nextH, params, h),
            difficulty = nextD,
        )
    }

    /**
     * 该在多少天后复习：让预测回忆概率正好掉到 [targetRecall]。
     *
     * `Δt = h·log2(1/target)`。target=0.9 → 0.152h；target=0.8 → 0.322h。
     * 也就是说"严格度"这个用户能听懂的开关，实际是在缩放 h。
     */
    fun intervalDays(halfLifeDays: Double, targetRecall: Double, params: MemoryParams = MemoryParams()): Double {
        val h = clampHalfLife(halfLifeDays, params, MemoryState.NEW.halfLifeDays)
        val target = if (targetRecall.isFinite()) targetRecall.coerceIn(0.5, 0.99) else params.defaultTargetRecall
        val raw = h * (ln(1.0 / target) / LN2)
        return min(raw, params.absoluteMaxIntervalDays)
    }

    /**
     * 忘记时的兜底间隔：至少 10 分钟，别让用户当场再错第二次；
     * 但也不超过 [intervalDays] 算出来的量 —— 失败支给出的 h 本来就小。
     */
    fun intervalDaysAfterForget(state: MemoryState, targetRecall: Double, params: MemoryParams = MemoryParams()): Double {
        val scheduled = intervalDays(state.halfLifeDays, targetRecall, params)
        return max(10.0 / (60.0 * 24.0), min(scheduled, 1.0))
    }

    /**
     * 由"还要保持多久"反推目标准确率与间隔上限。
     *
     * 方向必须是**越远的目标越宽松**：
     * 下周就考，忘一次的成本极高，所以不许它掉（target 0.95）；
     * 半年后才用得上，让它掉到 0.75 再复习反而更省总时间 ——
     * 快忘掉时才提取，这一次提取的加固效果最强（间隔效应，Cepeda 2006；
     * 以及"合意困难"那一支文献）。同时最优间隔随保持期线性放大，
     * 所以间隔上限也按保持期的 1/5 给。
     */
    fun targetRecallFor(retentionDays: Int, params: MemoryParams = MemoryParams()): Double {
        if (retentionDays <= 0) return params.defaultTargetRecall
        val r = min(retentionDays, 720)
        // 每多保持 60 天放宽 0.05，落在 [0.75, 0.95]：≤60 天=0.95、120=0.90、180=0.85、≥240=0.75
        val relaxed = 0.95 - 0.05 * floor(r / 60.0)
        return relaxed.coerceIn(0.75, 0.95)
    }

    /** 间隔上限（天）：经验带"约为保持期的 1/5"，并夹在 [3, absoluteMaxIntervalDays] */
    fun maxIntervalDaysFor(retentionDays: Int, params: MemoryParams = MemoryParams()): Double {
        if (retentionDays <= 0) return params.absoluteMaxIntervalDays
        val capped = min(retentionDays * 0.2, params.absoluteMaxIntervalDays)
        return max(3.0, capped)
    }

    /**
     * 一次调度：给评分后的状态算出"多少天后复习"，并把间隔上限套上。
     * 返回天数，交给调用方换算时间戳（模型不碰时钟）。
     */
    fun schedule(
        state: MemoryState,
        grade: ReviewGrade,
        targetRecall: Double,
        maxIntervalDays: Double,
        params: MemoryParams = MemoryParams(),
    ): Double {
        val raw = if (grade == ReviewGrade.FORGET) {
            intervalDaysAfterForget(state, targetRecall, params)
        } else {
            intervalDays(state.halfLifeDays, targetRecall, params)
        }
        val cap = if (maxIntervalDays.isFinite()) maxIntervalDays else params.absoluteMaxIntervalDays
        return min(raw, max(0.007, cap))
    }

    /**
     * 三个按钮各自会把这个词排到多久以后。
     *
     * v2.7（A-T9）起生产侧调用者已换到内核预览（CardStudyScreen 走 KernelHub），
     * 本函数保留作半衰期参考实现与回归证人（MemorySchedulerTest 的 3 个用例在盯它）。
     *
     * 墨墨把这个数字直接印在按钮上（"认识 · 35 天后"），这是整套改造里最值的 UI 借鉴：
     * 用户不需要理解半衰期，但他看得见"我说认识，它就敢排 35 天"，
     * 也能在排得离谱的当场就知道模型错了。**别把它藏进设置页。**
     *
     * @param gapDays 必须传**这个词真实的已拖时间**（现在减上次复习），不能传"理想排期"。
     *                真机踩过：一个拖了 22.8 小时的词，按理想排期预览算出「今日」，
     *                实际点完却排到 2.8 天后 —— 因为越接近遗忘点提取，加固越强（间隔效应）。
     *                预览低估只会让用户意外地少复习（方向安全），但按钮上的数字就是不许骗人。
     */
    fun preview(
        state: MemoryState,
        gapDays: Double,
        targetRecall: Double,
        maxIntervalDays: Double,
        params: MemoryParams = MemoryParams(),
    ): GradePreview {
        val days = ReviewGrade.entries.associateWith { grade ->
            schedule(update(state, gapDays, grade, params), grade, targetRecall, maxIntervalDays, params)
        }
        return GradePreview(
            recallDays = days.getValue(ReviewGrade.RECALL),
            vagueDays = days.getValue(ReviewGrade.VAGUE),
            forgetDays = days.getValue(ReviewGrade.FORGET),
            predictedRecall = recallProbability(gapDays, state.halfLifeDays),
        )
    }
}

/** 评分按钮上要印的三个间隔（天），以及当前这条记忆的预测回忆概率 */
data class GradePreview(
    val recallDays: Double,
    val vagueDays: Double,
    val forgetDays: Double,
    val predictedRecall: Double,
)

/** 用户在设置页选的复习严格度；AUTO = 跟随考试日期 */
enum class ReviewStrictness { AUTO, RELAXED, STANDARD, STRICT }

/** 一轮会话生效的调度参数 */
data class Scheduling(val targetRecall: Double, val maxIntervalDays: Double)

/**
 * 把"设置页的两个输入"翻译成"模型要用的两个数"。
 *
 * 单独抽出来是因为这一步最容易出错（考试日期过期、时区、空值），
 * 而它一旦错了，表现是"所有人的复习量都暴涨"这种全局症状。
 *
 * v3.0-B 起有两个入口：[forSettings]（全局口径，一个日期）与 [forSubject]（按学科，考试清单）。
 * **算术只住在 [forSubject] 一处** —— 前者摊成一场全学科考试后分派过去，两边不可能各说一套。
 */
object MemoryScheduler {

    /** 没填考试日期时，按"要保持半年"算 —— 与学生场景（期末/考级）的中位数相符 */
    private const val DEFAULT_RETENTION_DAYS = 180

    private val STRICTNESS_TARGET = mapOf(
        ReviewStrictness.RELAXED to 0.80,
        ReviewStrictness.STANDARD to 0.88,
        ReviewStrictness.STRICT to 0.95,
    )

    /**
     * 全局口径：取「最近那场考试」算一份 `Scheduling`（不看学科）。
     *
     * 签名一字不改 —— 它今天被 4 个调用点用（学习首页排期初始化、统计预测、复习提醒 worker，
     * 以及错题侧的旧路径），那三处聚合视图**本来就只有一个数字**，不该按学科分裂。
     * 实现收成分派到 [forSubject]：两个入口共用一套算术，杜绝"两处各说一套"。
     *
     * 单个日期在这里摊成一场**全学科**考试（`subjects` 空，窗口与冲刺值取 [ExamEntry] 的默认
     * 30 天 / 0.95），与 `AppSettings.examList` 对旧键的合成同一个形状 —— 两处形状不一样，
     * 就是这套改造最忌讳的"两个入口各说一套"。
     *
     * 摊过去之后有两处与旧算式不同，都是 v3.0-B T3 有意改的，不是漏网：
     *  1. 考试**当天**：旧代码 `days <= 0` 把它当作"没填"（0.9 / 36 天），现在是最紧的一档
     *     （1 天 horizon → 间隔地板 3 天，retention 由 ramp 打满）。整套功能要的就是"临考越来越严"，
     *     旧那天的宽松是"只有一个全局日期、背后没有 ramp"的副作用，不是设计。
     *  2. **手动档 + 窗口内**：旧代码对手动档完全不看考试日；现在 ramp 在临窗 30 天里把
     *     `RELAXED`(0.80) / `STANDARD`(0.88) 线性抬向冲刺值。只抬不降，`AUTO` 与 `STRICT` 在这段
     *     里 base 本就等于 0.95，数值分毫不动。
     * 其余各档逐值不变：没填（`examEpochDay <= 0`）、已过期、窗口外（剩余 >30 天）都不进 ramp，
     * 而 horizon 与旧算式同一个天数（由 [ExamPolicy.nearestExamDaysLeft] 给出，不再自己减日期）。
     *
     * @param examEpochDay `AppSettings.examEpochDay`，0 表示没填
     * @param today        今天的本地日期（由调用方给，测试里可注入）
     */
    fun forSettings(
        strictness: ReviewStrictness,
        examEpochDay: Long,
        today: LocalDate,
        params: MemoryParams = MemoryParams(),
    ): Scheduling {
        val exams = if (examEpochDay > 0L) listOf(ExamEntry(name = ExamEntry.DEFAULT_NAME, epochDay = examEpochDay)) else emptyList()
        return forSubject(strictness = strictness, exams = exams, subject = null, today = today, params = params)
    }

    /**
     * v3.0-B：某个学科这一轮生效的调度参数 = 「考试 horizon」+「临窗 ramp」。
     *
     * 三段决定，顺序不许换（`ExamPolicyTest` / `MemorySchedulerTest` 分别钉着）：
     *  1. **horizon**：这个学科有没有还没考的未来考试（[ExamPolicy.nearestExamDaysLeft]，**含当天**）。
     *     没有（或 `subject` 传 null 而清单里全是关联学科考试）就退回"要保持半年"那条默认，
     *     于是**英语不会被数学的考试日拖紧** —— 文档「非关联学科不受影响」的字面落地。
     *  2. **base**：`manual ?: targetRecallFor(horizon)`，与旧 `forSettings` 同一式子。
     *  3. **ramp**：[ExamPolicy.effectiveRetention] 在窗口内把 base 线性抬到冲刺值，封顶 0.97。
     *     只抬不降，所以 `STRICT`（0.95）不会被一场低冲刺值的考试反过来放松。
     *
     * `maxIntervalDays` **不进 ramp**：它量的是"这门记忆最多该隔多久"，锚在考试剩余天数上
     * （经验带 ≈1/5 保持期）。目标保留率变严会自动让排出的间隔变短，不需要再削上限；
     * 两头一起收等于对同一个约束罚两次。
     *
     * 多场考试同时命中一个学科时，两边各取各的：**rate 取最紧的那一场**（`effectiveRetention` 内部
     * 逐场算完取 max），**horizon 取最近的那一场**（`nearestExamDaysLeft` 取 min）。这两件不总是
     * 同一场 —— 于可能出现"远的那场贡献了冲刺率、近的那场贡献了间隔上限"的配对。可接受，
     * 因为配对只往严的方向偏、且封顶 0.97，而反过来（rate 与 horizon 都取最近那场）会让
     * 一门外科考试在窗内时把这科放松，那才是错的读法。
     *
     * horizon 上那道 `coerceAtLeast(1)` 是必须的，不是防御性装饰：[ExamPolicy.nearestExamDaysLeft]
     * 考试当天返回 0，而 [MemoryModel.targetRecallFor] 与 [MemoryModel.maxIntervalDaysFor] 拿到 0
     * 都走"无信息"分支（当作没有考试，后者直接给 365 天上限）。0 天原样喂过去，症状是**最紧急的
     * 那一天反而成为最宽松的一档** —— 复习量在考前爆掉，正是这个功能要修的事。ramp 不受影响：
     * 它读的是 `exams` 里真实的剩余天数，当天照样打满。
     */
    fun forSubject(
        strictness: ReviewStrictness,
        exams: List<ExamEntry>,
        subject: String?,
        today: LocalDate,
        params: MemoryParams = MemoryParams(),
    ): Scheduling {
        val manual = STRICTNESS_TARGET[strictness]
        val days = ExamPolicy.nearestExamDaysLeft(subject, today, exams)?.coerceAtLeast(1)
        if (days == null) {
            // 没填/已考完/这门科不沾这场考试：用默认准确率，但间隔上限仍按"半年保持期"收着，
            // 否则一个 h=2000 天的老词会被排到一年后，等于永久消失
            return Scheduling(
                targetRecall = manual ?: params.defaultTargetRecall,
                maxIntervalDays = MemoryModel.maxIntervalDaysFor(DEFAULT_RETENTION_DAYS, params),
            )
        }
        val base = manual ?: MemoryModel.targetRecallFor(days, params)
        return Scheduling(
            targetRecall = ExamPolicy.effectiveRetention(subject, today, exams, base),
            maxIntervalDays = MemoryModel.maxIntervalDaysFor(days, params),
        )
    }
}
