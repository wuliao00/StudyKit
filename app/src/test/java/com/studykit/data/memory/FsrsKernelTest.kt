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

    @Test fun `golden trace GOOD after 2 days from S0=w2 D0=2_118`() {
        val before = KernelState(stability = 2.3065, difficulty = 2.118104, cardState = CardState.REVIEW)
        val after = k.review(before, elapsedDays = 2.0, rating = KernelRating.GOOD, conf = null)
        assertEquals(10.757465, after.stability!!, 1e-4)
        assertEquals(2.116986, after.difficulty, 1e-4)
        assertEquals(13.360266, k.nextIntervalDays(after, KernelRating.GOOD, 0.88, 36500.0), 1e-4)
    }

    @Test fun `golden trace HARD after 2 days`() {
        val before = KernelState(stability = 2.3065, difficulty = 2.118104, cardState = CardState.REVIEW)
        val after = k.review(before, elapsedDays = 2.0, rating = KernelRating.HARD, conf = null)
        assertEquals(7.388910, after.stability!!, 1e-4)
        assertEquals(4.758630, after.difficulty, 1e-4)
    }

    @Test fun `golden trace AGAIN clamps stability to 1 and marks relearning`() {
        val before = KernelState(stability = 2.3065, difficulty = 2.118104, cardState = CardState.REVIEW)
        val after = k.review(before, elapsedDays = 2.0, rating = KernelRating.AGAIN, conf = null)
        assertEquals(1.0, after.stability!!, 1e-4)          // v5 支：clip 下限 1 触发
        assertEquals(7.400274, after.difficulty, 1e-4)
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
    }

    @Test fun `seed from half life divides by power law ratio`() {
        val seeded = k.seedFromHalfLife(halfLifeDays = 30.0, difficulty = 2.0)
        assertEquals(2.345674, seeded.stability!!, 1e-4)
        assertEquals(30.0, seeded.hDays!!, 1e-9) // 镜像字段按 spec §2.1 回填
    }

    @Test fun `non finite inputs degrade to safe defaults`() {
        val s = KernelState(stability = Double.NaN, difficulty = -3.0, cardState = CardState.REVIEW)
        assertEquals(
            (1.0 + FsrsKernel.FACTOR * 5.0 / FsrsKernel.MIN_STABILITY_FLOOR).pow(-0.5),
            k.recall(s, elapsedDays = 5.0), 1e-9,
        )
        val after = k.review(s, elapsedDays = -2.0, rating = KernelRating.GOOD, conf = null)
        check(after.stability!!.isFinite() && after.stability!! > 0.0)
        check(after.difficulty in 1.0..10.0)
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
}
