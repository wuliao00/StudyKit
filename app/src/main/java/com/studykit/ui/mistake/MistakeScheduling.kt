package com.studykit.ui.mistake

import com.studykit.data.entity.Mistake
import com.studykit.data.memory.CardState
import com.studykit.data.memory.Confidence
import com.studykit.data.memory.Hypercorrection
import com.studykit.data.memory.KernelRating
import com.studykit.data.memory.KernelState
import com.studykit.data.memory.ReviewGrade
import com.studykit.data.memory.Scheduling
import com.studykit.data.memory.SchedulingKernel
import com.studykit.data.memory.fsrsDifficultyFromHalfLife
import com.studykit.data.memory.kernelStateFor
import com.studykit.data.memory.toKernelRating
import java.util.concurrent.TimeUnit

/**
 * 错题排期接管（v2.7 计划 B Task 14；spec §6，app.docx 模块3 P0）的纯逻辑。
 *
 * 为什么单独成一个文件、而不是写在 `MistakeViewModel` 里：这一整段全是"给定一行错题 + 一次评分
 * → 该写哪几个字段"的算术，与 Room、协程、Compose 无关；留在 VM 里就只能靠 Robolectric 点，
 * 抽到这里才能像 [MistakeMastery]、`RedoFlow` 那样逐条钉进 `MistakeSchedulingTest`。
 *
 * ## 单列决策（计划 B Task 14 正文）
 * 算法排期**写回 `mistakes.review_at` 本身**，"手动覆盖"只是用户往同一个列里改写一次。
 * 于是 `MistakeDao.getDueForReview`（`review_at IS NOT NULL AND review_at <= now`）与列表的
 * 「待复习」侧一个字都不用改，也不会出现"页面显示的到期与提醒用的到期是两列"这种分家。
 * 新入本的错题 `review_at` 仍是 null（`MistakeRepository.add` 不预排），`fsrs_state` 取列默认值
 * 1=LEARNING、`fsrs_stability` 留 null —— 首评因此走内核的 firstTime 支（A-T3）。
 *
 * ## 两个“隔了多久”，不能合并
 * 错题表除了 `correct_streak`/`review_count` 这两个计数器之外没有任何逐次历史，
 * 也没有 `last_review_at`（schema 冻结在 v7），所以同一件事得从两个角度各取一个量：
 *  - **喂内核的那一个**（距上次**评分**）：拿不到就只能反推 ——
 *    `review_at` = 上次评分时刻 + 那一次排出的间隔，于是
 *    「距上次评分」≈「距上次到期」+「上次排出的间隔」，后一项拿行上留存的
 *    `fsrs_stability`/`fsrs_state` 过同一个 `nextIntervalDays` 再算一遍。
 *    不反推会直接碎：按时答的那一次「距上次到期」= 0 → R=1 → 加固量归零 →
 *    稳定性永远不涨、间隔永远不伸（词那边有 `last_review_at`，不会踩到）。
 *  - **掌握判据那一个**（距上次**到期**）：计划定的就是这一位。
 *    它只会低估“两次尝试隔了多久”（按时复习读 0），所以自动掌握偏保守；
 *    T15 接上 `mistake_redos` 之后应跟内核那位合流，统一拿真实的上次评分时刻。
 *
 * 反推的两个已知偏差（偏的是加固量，不是到期时刻）：用户在两次评分之间改过
 * 严格度/考试日，或者手动「覆盖排期」改写过 `review_at`，反推出的间隔就不是当时那一个。
 * 前者取的是本次设置（与本轮重排一致）；后者会往两个方向偏：把日期往后推、或让时钟
 * 回跳时算出来可能偏小甚至为负，一律 `coerceAtLeast(0)` 兜住 —— 宁可不涨，也不让人手选
 * 一次“明天”就骗到一个长间隔。反过来，在**已被算法排过期的行**上把 `review_at` 往前拉、
 * 再按新的到期时刻来评分，反推会**高估** Δt（Over-count）：真实只隔了一天，也会被读成
 * “上次排出的那个长间隔”，加固量因此虚涨。这一向不是钳位能修的 —— `mistake_redos` 表
 * B15 起已在写逐次重做轨迹，但评分路径（[grade]）尚未消费那份真实历史来替换反推，
 * 在接上之前，往前拉造成的高估会一直在。
 */
internal object MistakeScheduling {

    private val ONE_DAY_MS: Long = TimeUnit.DAYS.toMillis(1)

    /** 到期时刻的地板，与 `StudyViewModel.gradeCard` 的 5 分钟同值：内核排出 0 天也不许排到过去 */
    private val MIN_DUE_MS: Long = TimeUnit.MINUTES.toMillis(5)

    /**
     * 评一次重做，算出"这一行接下来该长成什么样"。
     *
     * 结构照抄 `StudyViewModel.gradeCard` 的内核段：取活跃内核 → `kernelStateFor` →
     * `review` → `nextIntervalDays` → 最后单独问一句侧信道要不要提前。
     * 与词那侧的三处差别，都在注释里标了原因（锚点、难度列量纲、历史缺失）。
     *
     * @param kernel **活跃内核**，由调用方经 `KernelHub.forId(settings.schedulingKernel)` 取，
     *               不在这里读设置：保持本函数零 Android 依赖、可在 JVM 单测里直取。
     * @param sched  与词侧同一份 `MemoryScheduler.forSettings(...)` 结果（目标准确率、间隔上限），
     *               所以用户把复习调严时，错题也跟着变密，两套记忆不会各排一套。
     * @param now    本次评分时刻（毫秒）；测试注入，生产传 `System.currentTimeMillis()`。
     */
    fun grade(
        mistake: Mistake,
        grade: ReviewGrade,
        conf: Confidence?,
        kernel: SchedulingKernel,
        sched: Scheduling,
        now: Long,
    ): Mistake {
        val before = kernelStateFor(kernel, mistake)

        // 距上次到期：从没排过期的行（新入本）退回入本时刻，与词侧 `lastReviewAt ?: createdAt`
        // 同一个理由 —— 两个量都不许是负数（提前复习、或设备时钟往回跳过都会给负值）。
        val gapSinceDue = elapsedDays(mistake.reviewAt ?: mistake.createdAt, now)
        // 距上次评分：见过一次算法评分（`fsrs_stability` 非空）才反推得动，否则只有一位可用
        val gapSinceGrade = if (mistake.reviewAt == null || mistake.fsrsStability == null) {
            elapsedDays(mistake.createdAt, now)
        } else {
            gapSinceDue + previousScheduledIntervalDays(kernel, before, sched)
        }

        val rating = grade.toKernelRating()
        val after = kernel.review(before, gapSinceGrade, rating, conf)
        val days = kernel.nextIntervalDays(after, rating, sched.targetRecall, sched.maxIntervalDays)

        // 「判对」取 grade != FORGET，与 gradeCard 落库的 `correct = grade != ReviewGrade.FORGET`
        // 同一口径：「模糊」走的是成功支（半衰期/stability 照涨），它不是"半个错"，不清连对链。
        val correct = grade != ReviewGrade.FORGET
        val streak = if (correct) mistake.correctStreak + 1 else 0

        // 排期先算完，再问侧信道要不要提前：min 只允许把时刻往前拉，
        // 侧信道无权把内核已经排出的间隔推后（与 gradeCard 逐字同序）
        val scheduledAt = now + (days * ONE_DAY_MS).toLong().coerceAtLeast(MIN_DUE_MS)
        val retestMinutes = Hypercorrection.retestDelayMinutes(conf, recalled = correct)
        val dueAt = if (retestMinutes == null) {
            scheduledAt
        } else {
            minOf(scheduledAt, now + TimeUnit.MINUTES.toMillis(retestMinutes))
        }

        // 自动掌握：判据仍然只在 [MistakeMastery] 里，这里只负责把"能拿到的两项"喂进去。
        // `streak >= 2` 是本轮的替代证人（历史里"上一次也判对"这一位），第一项的间隔无意义传 0；
        // 间隔那一取的是**距上次到期**（计划的口径，偏保守），不是喂内核的那一位。
        val mastered = mistake.mastered ||
            (
                correct && streak >= 2 &&
                    MistakeMastery.isMastered(listOf(true to 0.0, true to gapSinceDue))
                )

        return mistake.copy(
            fsrsStability = after.stability,
            // 列量纲固定（A-T9 终审）：`fsrs_difficulty` 永远是 FSRS 口径那一份。
            // 错题没有半衰期难度列，所以跑半衰期时在这里折算一次（词的写法同式），不留半新半旧。
            fsrsDifficulty = if (kernel.id == "HALF_LIFE") {
                fsrsDifficultyFromHalfLife(after.difficulty)
            } else {
                after.difficulty
            },
            fsrsState = after.cardState.ordinal + 1,
            correctStreak = streak,
            reviewCount = mistake.reviewCount + 1,
            reviewAt = dueAt,
            mastered = mastered,
        )
    }

    /**
     * 手动覆盖排期：改写同一个列（[Mistake.reviewAt]），记忆状态一个字不动。
     *
     * 清 `correct_streak` 是防震荡：下一次评分的间隔会变成"人工挑的那天 → 再答"，
     * 那个间隔既不是算法给的、也不受算法约束，留着连对计数就等于让人手挑一次"三天后"
     * 就能凑满 [MistakeMastery.MIN_GAP_DAYS] 把题自动判成掌握。
     */
    fun overrideSchedule(mistake: Mistake, reviewAt: Long): Mistake =
        mistake.copy(reviewAt = reviewAt, correctStreak = 0)

    /** 两个时刻之间隔了几天（往回跳的时钟一律当 0，不给内核负 Δt） */
    private fun elapsedDays(from: Long, to: Long): Double =
        (to - from).coerceAtLeast(0L) / ONE_DAY_MS.toDouble()

    /**
     * 反推「上一次评分排出了多长间隔」：拿行上留存的那一份状态（= 上次评分落库的结果）
     * 过同一个 `nextIntervalDays` 再算一遍，与当时写进 `review_at` 的那一个数是同一个式子。
     *
     * RELEARNING 必须按 AGAIN 问：那一支 FSRS 不返回间隔而是「当日再见」（10 分钟），
     * 拿 GOOD 去问会得到一个 S 天级别的长间隔，于是把“上次只隔 10 分钟”读成“上次隔了一个月”。
     * 词侧不需要这一位（直接存了 `last_review_at`），所以这段只活在错题这一侧。
     */
    private fun previousScheduledIntervalDays(
        kernel: SchedulingKernel,
        before: KernelState,
        sched: Scheduling,
    ): Double {
        val rating = if (before.cardState == CardState.RELEARNING) KernelRating.AGAIN else KernelRating.GOOD
        return kernel.nextIntervalDays(before, rating, sched.targetRecall, sched.maxIntervalDays)
            .coerceAtLeast(0.0)
    }
}

// ── 文案 ────────────────────────────────────────────────────────────────
// 与 `RedoFlow` 同一手法：页面要说的话收在纯函数里，才能钉进测试。
// `MistakeDetailRenderTest` 那组守卫钉的就是这一组：v2.5 §2.4 的旧承诺
// 「复习时间由你自己定，这里没有算法排期。」已被本轮退役，不许以任何形式回到页面上。

/** 排期区标题（原来是「复习提醒」，现在这一格说的是系统排出来的时刻） */
internal fun reviewScheduleTitle(): String = "复习排期"

/** 主位那一行：spec §6 的口径，算法负责排、人只保留覆盖权 */
internal fun systemSchedulingNote(): String = "系统按遗忘曲线排期，你可以覆盖"

/** 次级入口：三档手选时间从主菜单降级到这里 */
internal fun scheduleOverrideLabel(): String = "覆盖排期"

/**
 * 下一次到期那一行。[formattedDue] 为 null 只有一种读法：这道错题**还没被评过分**
 * （新入本、算法还没排），所以不假装"已设置"，也不催用户去手点。
 */
internal fun nextReviewLine(formattedDue: String?): String =
    if (formattedDue == null) {
        "还没排上复习时间，重做过一次后由系统安排"
    } else {
        "下次复习：$formattedDue"
    }

/**
 * 覆盖排期对话框里的三档（标签 → 距今天数）。
 *
 * 保留这三档是因为它们确实是常用值，但**不再占主位**（spec §6）；
 * 顺序即页面上的显示顺序，撤掉任何一档都会让用户少一个真在用的选项。
 */
internal fun manualOverrideOptions(): List<Pair<String, Int>> = listOf(
    "明天" to 1,
    "三天后" to 3,
    "一周后" to 7,
)
