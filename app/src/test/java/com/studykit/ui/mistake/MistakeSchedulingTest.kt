package com.studykit.ui.mistake

import com.studykit.data.entity.Mistake
import com.studykit.data.memory.CardState
import com.studykit.data.memory.Confidence
import com.studykit.data.memory.FsrsKernel
import com.studykit.data.memory.HalfLifeKernel
import com.studykit.data.memory.KernelRating
import com.studykit.data.memory.KernelState
import com.studykit.data.memory.ReviewGrade
import com.studykit.data.memory.Scheduling
import com.studykit.data.memory.SchedulingKernel
import com.studykit.data.memory.kernelStateOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * 错题排期接管（v2.7 计划 B Task 14；spec §6）的纯函数层。
 *
 * 钉住的六件事，每件都对应一条会被用户直接察觉的行为：
 *  1. **算法写回 `review_at` 本身**（计划里的单列决策）：评一次分就在那一列上写出下一次到期，
 *     列表与提醒（`MistakeDao.getDueForReview`）零特判 —— 所以这里断言的是 `reviewAt` 那一列，
 *     不是任何新增的"算法专用列"（schema 冻结在 v7，也不许加列）。
 *  2. 首评走内核原生 `firstTime` 支（`fsrs_stability` 为 null 就是"从 S0 起步"，A-T3 教训）；
 *     AGAIN → RELEARNING + 当日再见（10 分钟），GOOD → REVIEW + 内核算出的间隔。
 *  3. 超纠正侧信道**只允许把时刻往前拉**（min），永远不许把已排好的间隔推后。
 *  4. 自动掌握判据不另造一套：仍走 [MistakeMastery]（最近两次判对且间隔 ≥3 天）。本轮没有
 *     逐次重做历史（`mistake_redos` 的 DAO 在 T15），间隔只能拿"**上次到期时刻 → 本次评分时刻**"
 *     这个代理值，见 [MistakeScheduling.grade] 的 KDoc。
 *  5. 喂内核的 Δt 是"**距上次评分**"，不是"距上次到期"：错题没有 `last_review_at` 列，所以那个
 *     数只能反推。它决定的是加固量，写错会让按时复习的人永远不涨间隔。
 *  6. 手动「覆盖排期」与算法写的是**同一个列**，覆盖只清连对计数，不动记忆状态。
 *
 * 为什么不测 `MistakeViewModel.gradeRedo`：它是 `AndroidViewModel` 里的挂起函数，要 Room +
 * Robolectric 才跑得起来，而这一整段的错全在"状态怎么算、写到哪一列"；接线本身照抄
 * `gradeCard` 的内核段，`KernelWiringTest` 已把同源那两枚映射钉住。
 */
class MistakeSchedulingTest {

    private val oneDayMs = TimeUnit.DAYS.toMillis(1)
    private val tenMinutesMs = TimeUnit.MINUTES.toMillis(10)

    /** 与 `MemoryScheduler.forSettings` 默认档一致的测试夹具：targetRecall=0.9 时 FSRS 的间隔恰为 S 天 */
    private val sched = Scheduling(targetRecall = 0.9, maxIntervalDays = 365.0)

    private fun mistake(
        reviewAt: Long? = null,
        correctStreak: Int = 0,
        reviewCount: Int = 0,
        mastered: Boolean = false,
        fsrsStability: Double? = null,
        fsrsDifficulty: Double? = null,
        fsrsState: Int = 1,
        createdAt: Long = 0L,
    ) = Mistake(
        id = 1L,
        uuid = "u1",
        source = Mistake.SOURCE_PRACTICE,
        subject = "数学",
        title = "题",
        content = "题干",
        reviewAt = reviewAt,
        mastered = mastered,
        createdAt = createdAt,
        fsrsStability = fsrsStability,
        fsrsDifficulty = fsrsDifficulty,
        fsrsState = fsrsState,
        correctStreak = correctStreak,
        reviewCount = reviewCount,
    )

    /** 只改"内核排几天"的假内核：把侧信道那一段单独剥出来测，不让 FSRS 公式的具体数值掺进来 */
    private class FixedIntervalKernel(private val days: Double) : SchedulingKernel {
        override val id = "FSRS"
        override fun recall(state: KernelState, elapsedDays: Double): Double = 0.9
        override fun review(
            state: KernelState,
            elapsedDays: Double,
            rating: KernelRating,
            conf: Confidence?,
        ): KernelState = state.copy(
            stability = state.stability ?: 1.0,
            cardState = if (rating == KernelRating.AGAIN) CardState.RELEARNING else CardState.REVIEW,
        )

        override fun nextIntervalDays(
            state: KernelState,
            rating: KernelRating,
            targetRecall: Double,
            maxIntervalDays: Double,
        ): Double = days

        override fun seedFromHalfLife(halfLifeDays: Double, difficulty: Double): KernelState =
            KernelState(stability = null, difficulty = difficulty, cardState = CardState.LEARNING)
    }

    /**
     * 记下"内核收到的 Δt 到底是几天"的假内核：专门守反推那一段。
     * 这一位不钉住的话，把错题的锚点退回"距上次到期"也能让其余用例全绿，
     * 而真实后果是按时复习的人永远不涨（Δt=0 → R=1 → 加固量 0）。
     */
    private class RecordingKernel(private val days: Double) : SchedulingKernel {
        var lastElapsedDays: Double? = null
        override val id = "FSRS"
        override fun recall(state: KernelState, elapsedDays: Double): Double = 0.9
        override fun review(
            state: KernelState,
            elapsedDays: Double,
            rating: KernelRating,
            conf: Confidence?,
        ): KernelState {
            lastElapsedDays = elapsedDays
            return state.copy(
                stability = state.stability ?: 1.0,
                cardState = if (rating == KernelRating.AGAIN) CardState.RELEARNING else CardState.REVIEW,
            )
        }

        // 与 FsrsKernel 同形：AGAIN 不返回间隔而是当日再见，反推那一段就是靠这个分支分辨的
        override fun nextIntervalDays(
            state: KernelState,
            rating: KernelRating,
            targetRecall: Double,
            maxIntervalDays: Double,
        ): Double = if (rating == KernelRating.AGAIN) 10.0 / 1440.0 else days

        override fun seedFromHalfLife(halfLifeDays: Double, difficulty: Double): KernelState =
            KernelState(stability = null, difficulty = difficulty, cardState = CardState.LEARNING)
    }

    // ── 首评：内核接管，写回同一列 ─────────────────────────────────────────

    @Test fun `first AGAIN goes relearning and comes back in ten minutes`() {
        val now = System.currentTimeMillis()
        val fresh = mistake(createdAt = now - 2 * oneDayMs)

        val graded = MistakeScheduling.grade(fresh, ReviewGrade.FORGET, null, FsrsKernel(), sched, now)

        // RELEARNING = ordinal 2 → 落库 3（与 words.fsrs_state 同一编码口径）
        assertEquals(3, graded.fsrsState)
        // 当日再见：10 分钟这个数与 FsrsKernel.TEN_MINUTES_IN_DAYS / Hypercorrection 同源
        // （内核算的是天再乘回毫秒，留 ±2ms 的浮点余量，钉的是"当日"不是那一个比特）
        assertEquals((now + tenMinutesMs).toDouble(), graded.reviewAt!!.toDouble(), 2.0)
        // 首评就写回了那一列，提醒与列表因此零特判
        assertNotNull(graded.reviewAt)
        assertEquals(1, graded.reviewCount)
        assertEquals(0, graded.correctStreak)
        assertFalse(graded.mastered)
        assertNotNull(graded.fsrsStability)
    }

    @Test fun `first GOOD goes review with the interval the kernel computed`() {
        val now = System.currentTimeMillis()
        val fresh = mistake(createdAt = now - 2 * oneDayMs)

        val graded = MistakeScheduling.grade(fresh, ReviewGrade.RECALL, null, FsrsKernel(), sched, now)

        assertEquals(2, graded.fsrsState) // REVIEW
        // targetRecall=0.9 时 FSRS 的间隔恰为 stability 天（0.9^(1/−0.5)−1 = FACTOR），
        // 所以"到期时刻"就是内核那一个数，不是页面重算一遍
        assertEquals(
            (now + (graded.fsrsStability!! * oneDayMs).toLong()).toDouble(),
            graded.reviewAt!!.toDouble(),
            2.0,
        )
        assertEquals(1, graded.correctStreak)
        assertFalse(graded.mastered)
    }

    @Test fun `fresh row starts from S0 not from a mirrored stability`() {
        val now = System.currentTimeMillis()
        // fsrs_stability 为 null 就是"FSRS 还没写过"（A-T3：首评必须走原生 S0 支）
        val fresh = mistake(createdAt = now)
        assertEquals(null, kernelStateOf(fresh).stability)

        val graded = MistakeScheduling.grade(fresh, ReviewGrade.RECALL, null, FsrsKernel(), sched, now)

        // 入本当场就答 → Δt=0 → R=1 → 加固量恰为 0，写回的就是 S0(GOOD) = w[2] = 2.3065 本体；
        // 拿镜像值起步会把首周复习量算错（A-T3 教训），那一条错就错在这个数不是 2.3065
        assertEquals(2.3065, graded.fsrsStability!!, 1e-9)
    }

    @Test fun `second grading hardens the memory and counts the chain`() {
        val now = System.currentTimeMillis()
        val first = MistakeScheduling.grade(
            mistake(createdAt = now), ReviewGrade.RECALL, null, FsrsKernel(), sched, now,
        )
        // 比到期时刻晚一小时：拿"恰好相等"比大小会踩浮点往返的坑（R 可能落在 0.9 另一侧）
        val secondNow = first.reviewAt!! + TimeUnit.HOURS.toMillis(1)
        val second = MistakeScheduling.grade(first, ReviewGrade.RECALL, null, FsrsKernel(), sched, secondNow)

        assertTrue(second.fsrsStability!! > first.fsrsStability!!)
        assertEquals(2, second.reviewCount)
        assertEquals(2, second.correctStreak)
    }

    // ── 距上次评分的反推（错题没有 last_review_at 列）─────────────────────

    @Test fun `the kernel is told the time since the last grading, not since the due`() {
        val now = System.currentTimeMillis()
        // 正是到期那一瞬间：距上次到期 = 0，上次排出的却是 5 天
        val row = mistake(reviewAt = now, fsrsStability = 5.0, fsrsState = 2)
        val kernel = RecordingKernel(days = 5.0)

        MistakeScheduling.grade(row, ReviewGrade.RECALL, null, kernel, sched, now)

        assertEquals(5.0, kernel.lastElapsedDays!!, 1e-9)
    }

    @Test fun `dragging a review adds the lateness onto the reconstructed interval`() {
        val now = System.currentTimeMillis()
        val row = mistake(reviewAt = now - 2 * oneDayMs, fsrsStability = 5.0, fsrsState = 2)
        val kernel = RecordingKernel(days = 5.0)

        MistakeScheduling.grade(row, ReviewGrade.RECALL, null, kernel, sched, now)

        // 拖了 2 天才答 → 内核收到 5 + 2 = 7 天，R 低于目准 → 加固量算得出来
        assertEquals(7.0, kernel.lastElapsedDays!!, 1e-9)
    }

    @Test fun `a relearning row is reconstructed as ten minutes not as its stability`() {
        val now = System.currentTimeMillis()
        // 上一次是 AGAIN（fsrs_state=3 RELEARNING）：那一次只排了 10 分钟，不是 S 天级别
        val row = mistake(reviewAt = now, fsrsStability = 5.0, fsrsState = 3)
        val kernel = RecordingKernel(days = 30.0)

        MistakeScheduling.grade(row, ReviewGrade.RECALL, null, kernel, sched, now)

        assertEquals(10.0 / 1440.0, kernel.lastElapsedDays!!, 1e-9)
    }

    @Test fun `a row the algorithm has never graded falls back to the intake time`() {
        val now = System.currentTimeMillis()
        // 只有手动「覆盖排期」过的新生行：没有上次评分可反推，只能拿入本时刻当起点
        val row = mistake(reviewAt = now + oneDayMs, createdAt = now - 3 * oneDayMs)
        val kernel = RecordingKernel(days = 5.0)

        MistakeScheduling.grade(row, ReviewGrade.RECALL, null, kernel, sched, now)

        assertEquals(3.0, kernel.lastElapsedDays!!, 1e-9)
    }

    // ── 超纠正侧信道：min 只往前拉 ─────────────────────────────────────────

    @Test fun `sure but forgotten pulls the due forward to ten minutes`() {
        val now = System.currentTimeMillis()
        val row = mistake(reviewAt = now - oneDayMs, fsrsStability = 5.0, fsrsState = 2)

        val graded = MistakeScheduling.grade(
            row, ReviewGrade.FORGET, Confidence.SURE, FixedIntervalKernel(days = 30.0), sched, now,
        )

        // 内核排了 30 天，侧信道只把它拉到今天：这是 min 的方向，不是覆盖内核
        assertEquals(now + tenMinutesMs, graded.reviewAt)
    }

    @Test fun `confidence is never collected leaves the kernel interval alone`() {
        val now = System.currentTimeMillis()
        val row = mistake(reviewAt = now - oneDayMs, fsrsStability = 5.0, fsrsState = 2)

        val graded = MistakeScheduling.grade(
            row, ReviewGrade.FORGET, null, FixedIntervalKernel(days = 30.0), sched, now,
        )

        assertEquals(now + 30 * oneDayMs, graded.reviewAt)
    }

    @Test fun `side channel never pushes a nearer due further away`() {
        val now = System.currentTimeMillis()
        val row = mistake(reviewAt = now - oneDayMs, fsrsStability = 5.0, fsrsState = 2)

        // 内核给 0 天（脏配置那类），地板 5 分钟比侧信道的 10 分钟更近 ⇒ 取近的
        val graded = MistakeScheduling.grade(
            row, ReviewGrade.FORGET, Confidence.SURE, FixedIntervalKernel(days = 0.0), sched, now,
        )

        assertEquals(now + TimeUnit.MINUTES.toMillis(5), graded.reviewAt)
    }

    @Test fun `sure and recalled does not touch the schedule`() {
        val now = System.currentTimeMillis()
        val row = mistake(reviewAt = now - oneDayMs, fsrsStability = 5.0, fsrsState = 2)

        val graded = MistakeScheduling.grade(
            row, ReviewGrade.RECALL, Confidence.SURE, FixedIntervalKernel(days = 30.0), sched, now,
        )

        assertEquals(now + 30 * oneDayMs, graded.reviewAt)
    }

    // ── 连对计数与自动掌握（MistakeMastery 口径）───────────────────────────

    @Test fun `forgetting resets the streak but keeps the review count`() {
        val now = System.currentTimeMillis()
        val row = mistake(reviewAt = now - 10 * oneDayMs, correctStreak = 4, reviewCount = 9)

        val graded = MistakeScheduling.grade(
            row, ReviewGrade.FORGET, null, FixedIntervalKernel(days = 1.0), sched, now,
        )

        assertEquals(0, graded.correctStreak)
        assertEquals(10, graded.reviewCount)
        assertFalse(graded.mastered)
    }

    @Test fun `vague counts as recalled so it keeps the chain`() {
        val now = System.currentTimeMillis()
        val row = mistake(reviewAt = now - oneDayMs, correctStreak = 1, reviewCount = 2)

        val graded = MistakeScheduling.grade(
            row, ReviewGrade.VAGUE, null, FixedIntervalKernel(days = 5.0), sched, now,
        )

        // 「模糊」走成功支（gradeCard 的 correct = grade != FORGET 同一口径），不清链
        assertEquals(2, graded.correctStreak)
        assertFalse(graded.mastered)
    }

    @Test fun `two correct at least three days apart auto masters`() {
        val now = System.currentTimeMillis()
        val row = mistake(
            reviewAt = now - 4 * oneDayMs, correctStreak = 1, reviewCount = 2,
            fsrsStability = 5.0, fsrsState = 2,
        )

        val graded = MistakeScheduling.grade(
            row, ReviewGrade.RECALL, null, FixedIntervalKernel(days = 8.0), sched, now,
        )

        assertTrue(graded.mastered)
        assertEquals(2, graded.correctStreak)
    }

    @Test fun `same-day double correct does not master`() {
        val now = System.currentTimeMillis()
        val row = mistake(reviewAt = now - oneDayMs / 1440, correctStreak = 1, reviewCount = 2)

        val graded = MistakeScheduling.grade(
            row, ReviewGrade.RECALL, null, FixedIntervalKernel(days = 8.0), sched, now,
        )

        // 间隔不到 3 天：MistakeMastery 的 ≥3 天这一档就是防同日两连对冒充掌握
        assertFalse(graded.mastered)
        assertEquals(2, graded.correctStreak)
    }

    @Test fun `a single correct without any previous due never masters`() {
        val now = System.currentTimeMillis()
        // 从没排期过的老错题（review_at 为 null，入本已经 30 天）：只有一次的连对，链长不够
        val row = mistake(createdAt = now - 30 * oneDayMs, correctStreak = 0, reviewCount = 0)

        val graded = MistakeScheduling.grade(
            row, ReviewGrade.RECALL, null, FixedIntervalKernel(days = 8.0), sched, now,
        )

        assertFalse(graded.mastered)
        assertEquals(1, graded.correctStreak)
    }

    @Test fun `manually mastered row stays mastered after a lapse`() {
        val now = System.currentTimeMillis()
        val row = mistake(reviewAt = now - oneDayMs, correctStreak = 1, mastered = true)

        val graded = MistakeScheduling.grade(
            row, ReviewGrade.FORGET, null, FixedIntervalKernel(days = 1.0), sched, now,
        )

        // 手动标记是用户说的，算法无权悄悄收回（spec §6：取消要走手动路径）
        assertTrue(graded.mastered)
    }

    // ── 半衰期内核下的错题：同一列，换算镜像 ──────────────────────────────

    @Test fun `half life kernel writes back fsrs columns as mirrored approximations`() {
        val now = System.currentTimeMillis()
        val fresh = mistake(createdAt = now - 2 * oneDayMs)

        val graded = MistakeScheduling.grade(fresh, ReviewGrade.RECALL, null, HalfLifeKernel(), sched, now)

        assertNotNull(graded.fsrsStability)
        assertTrue(graded.fsrsStability!! > 0.0)
        // fsrs_difficulty 永远是 FSRS 口径（列量纲固定，A-T9 终审），跑半衰期时也是折算过来的那一份
        assertTrue(graded.fsrsDifficulty!! in 1.0..10.0)
        assertNotNull(graded.reviewAt)
        assertTrue(graded.reviewAt!! > now)
        // 半衰期适配器不发明 cardState（生命周期属 FSRS 侧），所以新行仍停在 LEARNING
        assertEquals(1, graded.fsrsState)
        // 写回的行再读一次必须是自洽的：镜像 stability 反推回来的 h 与写进去的一致
        assertEquals(graded.fsrsStability!! * 243.0 / 19.0, kernelStateOf(graded).hDays!!, 1e-6)
    }

    // ── 手动覆盖：同一列，清连对防震荡 ────────────────────────────────────

    @Test fun `manual override writes the same column and clears the streak`() {
        val now = System.currentTimeMillis()
        val row = mistake(
            reviewAt = now + 8 * oneDayMs, correctStreak = 2, reviewCount = 5,
            fsrsStability = 5.0, fsrsDifficulty = 3.0, fsrsState = 2,
        )
        val overrideAt = now + 2 * oneDayMs

        val overridden = MistakeScheduling.overrideSchedule(row, overrideAt)

        // 覆盖就是改写同一个列，不另存"用户偏好"，列表与提醒照常读它
        assertEquals(overrideAt, overridden.reviewAt)
        // 清连对：手选的间隔不是算法间隔，留着会让下一次评分拿人工间隔去凑 ≥3 天
        assertEquals(0, overridden.correctStreak)
        // 记忆状态本身一个字都不动
        assertEquals(5.0, overridden.fsrsStability!!, 1e-9)
        assertEquals(3.0, overridden.fsrsDifficulty!!, 1e-9)
        assertEquals(2, overridden.fsrsState)
        assertEquals(5, overridden.reviewCount)
        assertFalse(overridden.mastered)
    }
}
