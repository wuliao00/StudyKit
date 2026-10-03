package com.studykit.data.memory

import com.studykit.data.entity.Excerpt
import java.util.concurrent.TimeUnit

/**
 * 检索式书摘的排期（v2.7 计划 B Task 18 / spec §7.2）。
 *
 * 与错题（计划 A Task 14）同一把内核尺子，但书摘行只带了 `stability` 这一维记忆量
 * （`excerpts` 表没有 `fsrs_difficulty` / `fsrs_state` 列，spec 决定不加、避免为这一个入口动 schema）。
 * 于是这份文件把「行 → 内核状态」和「评一次分 → 新稳定性/下一次到期」抽成**纯函数**，
 * JVM 直接可测（`ExcerptReviewTest`），不依赖 Room 也不依赖 Android。
 *
 * 落库时只回写三样：`stability`（FSRS 的 S）、`review_count`（自评次数，主指标就数它）、
 * `next_review_at`（下一次到期）。`next_review_at = 0` 是「未启用」哨兵（每摘 opt-out），
 * 排期函数本身不判启用与否——调用方决定要不要把算出的正数写回。
 */

/**
 * 书摘行的中性 FSRS 难度。`excerpts` 没有难度列，第一次评分前给一个中位（FSRS D∈[1,10] 的 5.0），
 * 与 `MIGRATION_6_7` 回填 `fsrs_difficulty` 时的默认口径同一量纲（`5.0 + (halfD−1)·0.5` 在 halfD=1 时即 5.0）。
 */
private const val NEUTRAL_DIFFICULTY = 5.0

/** 书摘复习的目标可提取性与上限间隔：沿用词卡那套默认（90% / 一年），不为书摘另造一档。 */
const val EXCERPT_TARGET_RECALL = 0.9
const val EXCERPT_MAX_INTERVAL_DAYS = 365.0

/**
 * 回忆闸门用的「前半截」：只露出书摘开头一段，逼用户先在脑子里把剩下的补完，再展开全文自评。
 * 与词卡的 recall-before-grade 同一意图（Roediger & Karpicke），只是这里没有卡片两面，
 * 用「先遮后半」代替「先别看答案」。默认取前 40 个字符；不足 40 字符的书摘整条就是前半（不遮）。
 */
fun excerptFrontHalf(content: String, limit: Int = 40): String =
    if (content.length <= limit) content else content.take(limit).trimEnd() + "…"

/**
 * `excerpts` 行 → 内核状态（镜像 `kernelStateOf(word)`，只是书摘只有 stability 这一维）。
 *
 * `stability == null` 即「FSRS 还没写过这条书摘」—— 首评走原生 S0 支（`FsrsKernel.review` 的 firstTime 判据），
 * 与词卡同口径，不拿 0 冒充「记得一点」。`cardState` 由 stability 是否为空推出，别无处可取。
 */
fun kernelStateOf(excerpt: Excerpt): KernelState = KernelState(
    stability = excerpt.stability,
    difficulty = NEUTRAL_DIFFICULTY,
    cardState = if (excerpt.stability == null) CardState.LEARNING else CardState.REVIEW,
)

/** 一次书摘自评排完要落回去的那三列。纯值，不落库、不碰 Android。 */
data class ExcerptReviewResult(
    val stability: Double?,
    val reviewCount: Int,
    val nextReviewAt: Long,
)

object ExcerptReview {

    private const val ONE_DAY_MS = 24L * 3_600_000L

    /**
     * 评一次分出新排期。与 `StudyViewModel.gradeCard` 同一套内核调用序，只是：
     *  - 锚点用 [Excerpt.createdAt]（书摘没有 last-review 时间戳，间隔按「距建摘」折算，见下 KDoc），
     *  - 只回写 stability / reviewCount / nextReviewAt 三列。
     *
     * 诚实口径：`excerpts` 不带 `last_review_at`，所以重复复习的 gapDays 量的是「距当初建摘」而非
     * 「距上次复习」——这是「不为一个入口动 schema」这条约束换来的近似，写死在这里而不是偷偷算。
     *
     * 超纠正侧信道与词卡同律：只有 `conf == SURE × 未忆起(FORGET)` 才额外问一句要不要当日再见，
     * 且只允许把时刻**往前拉**（min），无权把已排好的间隔推后。
     */
    fun schedule(
        kernel: SchedulingKernel,
        excerpt: Excerpt,
        grade: ReviewGrade,
        conf: Confidence?,
        now: Long,
    ): ExcerptReviewResult {
        val before = kernelStateOf(excerpt)
        val gapDays = (now - excerpt.createdAt).coerceAtLeast(0L) / ONE_DAY_MS.toDouble()
        val rating = grade.toKernelRating()
        val after = kernel.review(before, gapDays, rating, conf)
        val days = kernel.nextIntervalDays(after, rating, EXCERPT_TARGET_RECALL, EXCERPT_MAX_INTERVAL_DAYS)
        val scheduledAt =
            now + (days * ONE_DAY_MS).toLong().coerceAtLeast(TimeUnit.MINUTES.toMillis(5))
        val retestMinutes = Hypercorrection.retestDelayMinutes(conf, recalled = grade != ReviewGrade.FORGET)
        val nextReviewAt = if (retestMinutes == null) {
            scheduledAt
        } else {
            minOf(scheduledAt, now + TimeUnit.MINUTES.toMillis(retestMinutes))
        }
        return ExcerptReviewResult(
            stability = after.stability,
            reviewCount = excerpt.reviewCount + 1,
            nextReviewAt = nextReviewAt,
        )
    }
}
