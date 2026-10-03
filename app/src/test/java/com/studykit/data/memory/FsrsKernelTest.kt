package com.studykit.data.memory

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.pow

class FsrsKernelTest {

    private val k = FsrsKernel()

    @Test fun `retrievability anchor R(t=S) is 0_9`() {
        val s = KernelState(stability = 10.0, difficulty = 3.0, cardState = CardState.REVIEW)
        assertEquals(0.9, k.recall(s, elapsedDays = 10.0), 1e-9) // S 的定义：R=90% 的间隔
    }

    @Test fun `interval at desired 0_9 equals stability exactly`() {
        val s = KernelState(stability = 10.757465, difficulty = 2.117, cardState = CardState.REVIEW)
        assertEquals(10.757465, k.nextIntervalDays(s, KernelRating.GOOD, 0.9, 36500.0), 1e-6)
    }

    // 逐值钉死 docs/evidence/fsrs6-golden.json（py-fsrs 6.3.2 直算）；v5→v6 黄金值变化见预言机 §5.5
    @Test fun `golden trace GOOD after 2 days from S0=w2 D0=2_118`() {
        val before = KernelState(stability = 2.3065, difficulty = 2.118104, cardState = CardState.REVIEW)
        val after = k.review(before, elapsedDays = 2.0, rating = KernelRating.GOOD, conf = null)
        assertEquals(10.964332335820698, after.stability!!, 1e-4)
        assertEquals(2.111214235785395, after.difficulty, 1e-4)
        assertEquals(14.439282874696593, k.nextIntervalDays(after, KernelRating.GOOD, 0.88, 36500.0), 1e-4)
        // 钉住镜像契约：hDays 恒等于 stability × FSRS_HALF_OVER_S（spec §2.1 双写口径）
        assertEquals(after.stability!! * FSRS_HALF_OVER_S, after.hDays!!, 1e-9)
    }

    @Test fun `golden trace HARD after 2 days`() {
        val before = KernelState(stability = 2.3065, difficulty = 2.118104, cardState = CardState.REVIEW)
        val after = k.review(before, elapsedDays = 2.0, rating = KernelRating.HARD, conf = null)
        assertEquals(7.513320366762569, after.stability!!, 1e-4)   // hard_penalty = w[15]
        assertEquals(4.752858488532557, after.difficulty, 1e-4)
    }

    @Test fun `golden trace AGAIN applies v6 lapse min long short cap`() {
        val before = KernelState(stability = 2.3065, difficulty = 2.118104, cardState = CardState.REVIEW)
        val after = k.review(before, elapsedDays = 2.0, rating = KernelRating.AGAIN, conf = null)
        // v6 lapse：S'_f = min(w11·D^-w12·((S+1)^w13−1)·e^((1−R)w14), S/e^(w17·w18))——不再是 v5 的地板 1 天
        assertEquals(0.6075801062519337, after.stability!!, 1e-4)
        assertEquals(7.394502741279718, after.difficulty, 1e-4)
        assertEquals(CardState.RELEARNING, after.cardState)
        // "当日再见"地板 = 10 分钟（与旧 intervalDaysAfterForget 同一语义）
        assertEquals(10.0 / 1440.0, k.nextIntervalDays(after, KernelRating.AGAIN, 0.9, 36500.0), 1e-9)
    }

    @Test fun `first review of a learning card takes S0 branch not lapse formula`() {
        // 迁移后的半衰期行（stability 有镜像值）不得被当成"首次评分"——
        // 否则一次 AGAIN 会把 h 折算出的 S 替换成 S0=0.212，首周复习量翻倍（controller 修正，spec §2.1）
        val seeded = k.seedFromHalfLife(halfLifeDays = 30.0, difficulty = 2.0)
        val after = k.review(seeded, elapsedDays = 2.0, rating = KernelRating.AGAIN, conf = null)
        val raw = after.stability!!
        check(raw != 0.212) { "seeded 行被误判成首次评分，S 被 S0 顶掉了" }
        check(raw <= seeded.stability!!) // lapse 只降不升
        // 真新词（stability=null，无镜像）才走 S0：AGAIN → w[0]
        val fresh = KernelState(stability = null, difficulty = 1.0, cardState = CardState.LEARNING)
        val a2 = k.review(fresh, elapsedDays = 0.0, rating = KernelRating.AGAIN, conf = null)
        assertEquals(0.212, a2.stability!!, 1e-9)
        // 可达的毒行形态：HALF_LIFE 镜像回填过 stability，但 fsrs_state 列还是默认 1=LEARNING。
        // 旧判据 cardState==LEARNING 会把它当首次评分，S 被顶成 S0=0.212（首周复习量翻倍）
        val mirroredLearning = KernelState(
            stability = 30.0 / FSRS_HALF_OVER_S, difficulty = 3.0,
            cardState = CardState.LEARNING, hDays = 30.0,
        )
        val m = k.review(mirroredLearning, elapsedDays = 2.0, rating = KernelRating.AGAIN, conf = null)
        check(m.stability!! > 0.3) { "LEARNING+stability≠null 被误判首次，S 被 S0 顶掉：${m.stability}" }
    }

    @Test fun `seed from half life divides by power law ratio`() {
        val seeded = k.seedFromHalfLife(halfLifeDays = 30.0, difficulty = 2.0)
        assertEquals(2.345674, seeded.stability!!, 1e-4)
        assertEquals(30.0, seeded.hDays!!, 1e-9) // 镜像字段按 spec §2.1 回填
    }

    @Test fun `non finite inputs degrade to safe defaults`() {
        val s = KernelState(stability = Double.NaN, difficulty = -3.0, cardState = CardState.REVIEW)
        assertEquals(
            (1.0 + FsrsKernel.FACTOR * 5.0 / FsrsKernel.MIN_STABILITY_FLOOR).pow(FsrsKernel.DECAY),
            k.recall(s, elapsedDays = 5.0), 1e-9,
        )
        val after = k.review(s, elapsedDays = -2.0, rating = KernelRating.GOOD, conf = null)
        check(after.stability!!.isFinite() && after.stability!! > 0.0)
        check(after.difficulty in 1.0..10.0)
        // 脏难度（NaN）穿过 coerceIn 会同时毒化 D→S→h 三元组，全部得是有限值
        val s2 = KernelState(stability = 10.0, difficulty = Double.NaN, cardState = CardState.REVIEW)
        val after2 = k.review(s2, elapsedDays = 2.0, rating = KernelRating.GOOD, conf = null)
        check(after2.stability!!.isFinite() && after2.difficulty.isFinite() && after2.hDays!!.isFinite())
    }

    @Test fun `dirty maxIntervalDays degrades without throwing`() {
        val s = KernelState(stability = 10.0, difficulty = 3.0, cardState = CardState.REVIEW)
        // 负上限 → 10 分钟地板（当日再见），不抛
        val neg = k.nextIntervalDays(s, KernelRating.GOOD, 0.9, -5.0)
        assertEquals(10.0 / 1440.0, neg, 1e-12)
        // NaN 上限 → 不设上限，回正常间隔
        val nan = k.nextIntervalDays(s, KernelRating.GOOD, 0.9, Double.NaN)
        assertEquals(10.0, nan, 1e-9)
        check(neg.isFinite() && nan.isFinite())
    }

    // ==== 以下用例逐值钉死 docs/evidence/fsrs6-golden.json（py-fsrs 6.3.2 直算），容差 1e-4 ====

    @Test fun `v6 first review stores initial stability and difficulty per rating`() {
        // py-fsrs：首次评分落 S0=w[G-1] 与 D0(G)（夹 [1,10]），不套增长公式
        fun first(r: KernelRating) = k.review(
            KernelState(stability = null, difficulty = 1.0, cardState = CardState.LEARNING),
            elapsedDays = 0.0, rating = r, conf = null,
        )
        assertEquals(0.212, first(KernelRating.AGAIN).stability!!, 1e-6)   // w[0]
        assertEquals(6.4133, first(KernelRating.AGAIN).difficulty, 1e-6)   // D0(Again)=w[4]-e^0+1
        assertEquals(1.2931, first(KernelRating.HARD).stability!!, 1e-6)   // w[1]
        assertEquals(5.112170705601056, first(KernelRating.HARD).difficulty, 1e-6)
        assertEquals(2.3065, first(KernelRating.GOOD).stability!!, 1e-6)   // w[2]
        assertEquals(2.118103970459016, first(KernelRating.GOOD).difficulty, 1e-6)
        assertEquals(8.2956, first(KernelRating.EASY).stability!!, 1e-6)   // w[3]
        assertEquals(1.0, first(KernelRating.EASY).difficulty, 1e-6)       // D0(Easy) 未夹 -4.7716 → 夹到 1
    }

    @Test fun `v6 golden EASY long term applies easy bonus w16`() {
        val before = KernelState(stability = 2.3065, difficulty = 2.118104, cardState = CardState.REVIEW)
        val after = k.review(before, elapsedDays = 2.0, rating = KernelRating.EASY, conf = null)
        assertEquals(18.52175418175859, after.stability!!, 1e-4)   // easy_bonus = w[16]
        assertEquals(1.0, after.difficulty, 1e-4)                  // next_difficulty(Easy) → 夹到地板 1
    }

    @Test fun `v6 golden next difficulty mean reversion w7 to unclamped D0 Easy`() {
        // 均值回归 arg1 = D0(Easy) 未夹取（-4.7716…）；从 D=5 出发的四档见 fsrs6-golden.json
        fun dAfter(r: KernelRating) = k.review(
            KernelState(stability = 10.0, difficulty = 5.0, cardState = CardState.REVIEW),
            elapsedDays = 2.0, rating = r, conf = null,
        ).difficulty
        assertEquals(8.341762369296838, dAfter(KernelRating.AGAIN), 1e-6)
        assertEquals(6.665995369296838, dAfter(KernelRating.HARD), 1e-6)
        assertEquals(4.9902283692968386, dAfter(KernelRating.GOOD), 1e-6)
        assertEquals(3.3144613692968385, dAfter(KernelRating.EASY), 1e-6)
    }

    @Test fun `v6 golden short term stability intra day path w17 w18 w19`() {
        val before = KernelState(stability = 2.3065, difficulty = 2.118104, cardState = CardState.REVIEW)
        // elapsedDays<1 走 intra-day 支：Good/Hard increase<1 被抬到 1 → S 不变；Easy 涨；Again 不抬地板 → S 降
        assertEquals(2.3065, k.review(before, 0.5, KernelRating.GOOD, null).stability!!, 1e-6)
        assertEquals(2.3065, k.review(before, 0.5, KernelRating.HARD, null).stability!!, 1e-6)
        assertEquals(3.946054067969477, k.review(before, 0.5, KernelRating.EASY, null).stability!!, 1e-4)
        assertEquals(0.7750839828558984, k.review(before, 0.5, KernelRating.AGAIN, null).stability!!, 1e-4)
        // intra-day 难度仍走 next_difficulty
        assertEquals(2.111214235785395, k.review(before, 0.5, KernelRating.GOOD, null).difficulty, 1e-6)
    }
}
