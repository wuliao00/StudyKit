package com.studykit.srs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FSRS（Free Spaced Repetition Scheduler）核心排期算法单元测试。
 *
 * 公式依据 awesome-fsrs 官方 wiki「The Algorithm」FSRS v4 一节：
 *   S0(G) = w[G-1]
 *   D0(G) = w4 - (G-3)·w5
 *   D'(D,G) = w7·D0(3) + (1-w7)·(D - w6·(G-3))        // 均值回归，防 ease hell
 *   R(t,S) = (1 + t/(9S))^-1                            // R=0.9 当 t=S
 *   I(r,S) = 9S·(1/r - 1)
 *   S'r = S·(e^w8·(11-D)·S^-w9·(e^(w10(1-R))-1)·[w15|G=2]·[w16|G=4] + 1)
 *   S'f = w11·D^-w12·((S+1)^w13 - 1)·e^(w14(1-R))
 * 默认参数取 wiki 给出的 v4 值。
 *
 * 精确断言只用在「规格无歧义」的地方（初始 S/D、遗忘曲线恒等式、间隔恒等式）；
 * 稳定性更新用性质断言（单调性、SInc≥1、过期复习增益更大），避免把实现细节锁死。
 */
class FsrsTest {

    private val dayMs = 24L * 60L * 60L * 1000L
    private val t0 = 1_700_000_000_000L

    private fun state(s: Double, d: Double, agoDays: Long = 0L) = MemoryState(
        stability = s,
        difficulty = d,
        lastReviewAt = t0 - agoDays * dayMs,
        reps = 1,
        lapses = 0,
    )

    // ── 初始状态：D0 与 S0 ───────────────────────────────────────────────

    @Test
    fun `first rating seeds stability from default parameters`() {
        assertEquals(0.4, Fsrs.firstRating(Rating.AGAIN, now = t0).stability, 1e-9)
        assertEquals(0.6, Fsrs.firstRating(Rating.HARD, now = t0).stability, 1e-9)
        assertEquals(2.4, Fsrs.firstRating(Rating.GOOD, now = t0).stability, 1e-9)
        assertEquals(5.8, Fsrs.firstRating(Rating.EASY, now = t0).stability, 1e-9)
    }

    @Test
    fun `first rating seeds difficulty as w4 minus grade offset`() {
        // D0(G) = 4.93 - (G-3)*0.94
        assertEquals(6.81, Fsrs.firstRating(Rating.AGAIN, now = t0).difficulty, 1e-9)
        assertEquals(5.87, Fsrs.firstRating(Rating.HARD, now = t0).difficulty, 1e-9)
        assertEquals(4.93, Fsrs.firstRating(Rating.GOOD, now = t0).difficulty, 1e-9)
        assertEquals(3.99, Fsrs.firstRating(Rating.EASY, now = t0).difficulty, 1e-9)
    }

    @Test
    fun `first rating records reps and lapse count`() {
        val again = Fsrs.firstRating(Rating.AGAIN, now = t0)
        assertEquals(1, again.reps)
        assertEquals(1, again.lapses)
        val good = Fsrs.firstRating(Rating.GOOD, now = t0)
        assertEquals(1, good.reps)
        assertEquals(0, good.lapses)
    }

    // ── 遗忘曲线与间隔恒等式 ─────────────────────────────────────────────

    @Test
    fun `retrievability is one at review time and ninety percent after one stability`() {
        val s = state(s = 10.0, d = 5.0)
        assertEquals(1.0, Fsrs.retrievability(s, t0), 1e-9)
        assertEquals(0.9, Fsrs.retrievability(s, t0 + 10 * dayMs), 1e-9)
        assertTrue(Fsrs.retrievability(s, t0 + 30 * dayMs) < Fsrs.retrievability(s, t0 + 10 * dayMs))
    }

    @Test
    fun `interval equals stability when desired retention is ninety percent`() {
        assertEquals(10, Fsrs.intervalDays(stability = 10.0, desiredRetention = 0.9))
    }

    @Test
    fun `lower desired retention yields longer interval`() {
        val loose = Fsrs.intervalDays(stability = 10.0, desiredRetention = 0.8)
        val strict = Fsrs.intervalDays(stability = 10.0, desiredRetention = 0.95)
        assertTrue("期望 $loose > $strict", loose > strict)
        assertTrue(Fsrs.intervalDays(stability = 10.0, desiredRetention = 0.9) > 0)
    }

    // ── 复习后的稳定性演化（性质断言）───────────────────────────────────

    @Test
    fun `successful review never reduces stability`() {
        for (rating in listOf(Rating.HARD, Rating.GOOD, Rating.EASY)) {
            val before = state(s = 5.0, d = 5.0, agoDays = 5)
            val after = Fsrs.review(before, rating, now = t0).state
            assertTrue("$rating 后稳定性应不下降：${after.stability} < ${before.stability}", after.stability >= before.stability)
        }
    }

    @Test
    fun `easy boosts stability more than good and good more than hard`() {
        val base = state(s = 5.0, d = 5.0, agoDays = 5)
        val hard = Fsrs.review(base, Rating.HARD, now = t0).state.stability
        val good = Fsrs.review(base, Rating.GOOD, now = t0).state.stability
        val easy = Fsrs.review(base, Rating.EASY, now = t0).state.stability
        assertTrue("easy($easy) 应大于 good($good)", easy > good)
        assertTrue("good($good) 应大于 hard($hard)", good > hard)
    }

    @Test
    fun `overdue review gains more stability than on-time review`() {
        val base = state(s = 5.0, d = 5.0)
        val onTime = Fsrs.review(base, Rating.GOOD, now = t0 + 5 * dayMs).state.stability
        val overdue = Fsrs.review(base, Rating.GOOD, now = t0 + 30 * dayMs).state.stability
        assertTrue("过期复习（间隔效应）应增益更大：$overdue <= $onTime", overdue > onTime)
    }

    @Test
    fun `hard items gain less stability than easy items`() {
        val base = state(s = 5.0, d = 5.0, agoDays = 5)
        val hardItem = Fsrs.review(base.copy(difficulty = 9.0), Rating.GOOD, now = t0).state.stability
        val easyItem = Fsrs.review(base.copy(difficulty = 2.0), Rating.GOOD, now = t0).state.stability
        assertTrue("高难度材料增幅应更小：$hardItem >= $easyItem", hardItem < easyItem)
    }

    @Test
    fun `lapse shortens stability and schedules same day relearn`() {
        val before = state(s = 20.0, d = 5.0, agoDays = 20)
        val result = Fsrs.review(before, Rating.AGAIN, now = t0)
        assertTrue("遗忘后稳定性应大幅缩短：${result.state.stability} >= ${before.stability}", result.state.stability < before.stability)
        assertEquals(0, result.intervalDays)
        assertEquals(1, result.state.lapses)
        assertTrue(result.dueAt > t0 && result.dueAt <= t0 + 30 * 60 * 1000)
    }

    @Test
    fun `difficulty stays within one to ten across repeated ratings`() {
        var s = Fsrs.firstRating(Rating.GOOD, now = t0)
        repeat(20) { i ->
            s = Fsrs.review(s, if (i % 2 == 0) Rating.AGAIN else Rating.EASY, now = t0 + i * dayMs).state
            assertTrue("D=${s.stability} 越界", s.difficulty in 1.0..10.0)
            assertTrue("S 必须为正", s.stability > 0.0)
        }
    }

    @Test
    fun `repeated again keeps difficulty from exploding`() {
        var s = Fsrs.firstRating(Rating.AGAIN, now = t0)
        repeat(6) { i -> s = Fsrs.review(s, Rating.AGAIN, now = t0 + (i + 1) * dayMs).state }
        assertTrue("连续 Again 后难度应被夹在 10 以内：${s.difficulty}", s.difficulty <= 10.0)
        assertTrue("连续 Again 后稳定性仍为正：${s.stability}", s.stability > 0.0)
    }

    // ── 检索优先流程：信心评级与超纠正 ──────────────────────────────────

    @Test
    fun `high confidence error gets higher review priority than low confidence error`() {
        val sure = ReviewPlanner.priority(state(s = 3.0, d = 6.0, agoDays = 3), Confidence.SURE, correct = false, now = t0)
        val guess = ReviewPlanner.priority(state(s = 3.0, d = 6.0, agoDays = 3), Confidence.GUESS, correct = false, now = t0)
        assertTrue("高置信错误应优先复习（hypercorrection）：sure=$sure guess=$guess", sure > guess)
    }

    @Test
    fun `overdue item outranks fresh item regardless of confidence`() {
        val overdue = ReviewPlanner.priority(state(s = 2.0, d = 5.0, agoDays = 10), Confidence.GUESS, correct = true, now = t0)
        val fresh = ReviewPlanner.priority(state(s = 20.0, d = 5.0), Confidence.SURE, correct = true, now = t0)
        assertTrue("到期欠账优先：overdue=$overdue fresh=$fresh", overdue > fresh)
    }

    @Test
    fun `high confidence error is scheduled sooner than high confidence success`() {
        val base = state(s = 5.0, d = 5.0, agoDays = 5)
        val wrong = ReviewPlanner.nextRetention(Confidence.SURE, correct = false)
        val right = ReviewPlanner.nextRetention(Confidence.VAGUE, correct = true)
        assertTrue("高置信错题的目标保留率应更高（更快复习）：wrong=$wrong right=$right", wrong > right)
        assertTrue(ReviewPlanner.review(base, Rating.GOOD, Confidence.SURE, correct = false, now = t0).intervalDays > 0)
    }

    // ── 记忆看板：到期与预测保留率 ──────────────────────────────────────

    @Test
    fun `memory board aggregates due today and tomorrow`() {
        val items = listOf(
            state(s = 2.0, d = 5.0, agoDays = 3),   // 今日已到期
            state(s = 5.0, d = 5.0, agoDays = 5),   // 今日到期（R=0.9）
            state(s = 3.0, d = 5.0, agoDays = 2),   // 明天到期
        )
        val board = ReviewPlanner.board(items, now = t0, desiredRetention = 0.9)
        assertEquals(2, board.dueToday)
        assertEquals(1, board.dueTomorrow)
        assertEquals(3, board.total)
        assertTrue("预测保留率应落在 0..1：${board.predictedRetention}", board.predictedRetention in 0.0..1.0)
    }

    @Test
    fun `memory board retention drops as items fall behind schedule`() {
        val onTime = ReviewPlanner.board(listOf(state(5.0, 5.0, 5)), now = t0, desiredRetention = 0.9)
        val behind = ReviewPlanner.board(listOf(state(5.0, 5.0, 40)), now = t0, desiredRetention = 0.9)
        assertTrue("落后时保留率应更低：onTime=${onTime.predictedRetention} behind=${behind.predictedRetention}", behind.predictedRetention < onTime.predictedRetention)
    }

    @Test
    fun `board is empty when nothing has been studied`() {
        val board = ReviewPlanner.board(emptyList(), now = t0, desiredRetention = 0.9)
        assertEquals(0, board.dueToday)
        assertEquals(0, board.dueTomorrow)
        assertEquals(0, board.total)
    }

    // ── 交错练习：跨学科/跨题型混排 ────────────────────────────────────

    @Test
    fun `interleaving alternates subjects instead of blocking them`() {
        val blocked = listOf("数学", "数学", "数学", "数学", "英语", "英语", "英语", "英语")
        val mixed = ReviewPlanner.interleave(blocked.mapIndexed { i, s -> i to s })
        val sameNeighbour = mixed.zipWithNext().count { it.first.second == it.second.second }
        assertTrue("交错后相邻同类应显著减少：$mixed", sameNeighbour <= blocked.size / 4)
    }

    @Test
    fun `interleaving keeps every item exactly once`() {
        val src = listOf("数学", "数学", "英语", "物理", "数学", "英语")
        val mixed = ReviewPlanner.interleave(src.mapIndexed { i, s -> i to s })
        assertEquals(src.size, mixed.size)
        assertEquals(src.sorted(), mixed.map { it.second }.sorted())
    }

    @Test
    fun `interleaving is skipped when only one subject exists`() {
        val src = listOf("数学", "数学", "数学")
        val mixed = ReviewPlanner.interleave(src.mapIndexed { i, s -> i to s })
        assertEquals(listOf(0, 1, 2), mixed.map { it.first })
    }
}
