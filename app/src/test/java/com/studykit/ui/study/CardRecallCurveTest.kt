package com.studykit.ui.study

import com.studykit.data.memory.CardState
import com.studykit.data.memory.FsrsModel
import com.studykit.data.memory.KernelRating
import com.studykit.data.memory.KernelState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task 9 的 ⛔ 一票否决：单卡遗忘曲线的「到期标记」必须与**调度器自己排出来的间隔**逐值相等。
 *
 * 真实调度入口是 [SchedulingKernel.nextIntervalDays]（FSRS 实现内部委托
 * [FsrsModel.nextIntervalDays]，非 AGAIN 档只用 [KernelState.stability]，夹取走
 * FsrsKernel 的 sanitizeStability）。这里固定走 FSRS 内核、把三种「忆起系」评分
 * （HARD/GOOD/EASY，调度函数对它们同一条支）× 多档稳定度 × 多档目标保留率扫一遍，
 * delta 用 0.0 —— 要求**比特级逐值相等**，不是"差不多"。
 * AGAIN 档是调度器自己的 10 分钟专支，不在相等性主张内（有单独的说明用例钉住）。
 */
class CardRecallCurveTest {

    private val w = FsrsModel.DEFAULT_PARAMS

    private fun reviewState(s: Double?) =
        KernelState(stability = s, difficulty = 5.0, cardState = CardState.REVIEW)

    // ── ⛔ 一致性硬闸 ────────────────────────────────────────────────

    @Test
    fun `到期标记与调度器给的间隔逐值相等`() {
        val kernel = KernelHub.forId("FSRS")
        for (s in listOf(0.5, 1.0, 2.3, 7.0, 33.0, 120.0, 900.0)) {
            for (r in listOf(0.80, 0.88, 0.90, 0.95)) {
                val curve = CardRecallCurve.dueMarkerDays(w, s, r)
                for (rating in listOf(KernelRating.HARD, KernelRating.GOOD, KernelRating.EASY)) {
                    val sched = kernel.nextIntervalDays(reviewState(s), rating, r, 3650.0)
                    assertEquals("S=$s R=$r rating=$rating", sched, curve, 0.0)
                }
            }
        }
    }

    @Test
    fun `到期标记与调度器在任意排期上限下逐值相等`() {
        val kernel = KernelHub.forId("FSRS")
        for (cap in listOf(3.0, 21.0, 365.0, 3650.0)) {
            for (s in listOf(2.0, 40.0, 800.0)) {
                val curve = CardRecallCurve.dueMarkerDays(w, s, 0.9, maxIntervalDays = cap)
                val sched = kernel.nextIntervalDays(reviewState(s), KernelRating.GOOD, 0.9, cap)
                assertEquals("cap=$cap S=$s", sched, curve, 0.0)
            }
        }
        // 脏上限（非有限）走 FsrsModel 的兜底保险丝，两处必须同落点
        val curveNaN = CardRecallCurve.dueMarkerDays(w, 120.0, 0.9, maxIntervalDays = Double.NaN)
        val schedNaN = kernel.nextIntervalDays(reviewState(120.0), KernelRating.GOOD, 0.9, Double.NaN)
        assertEquals(schedNaN, curveNaN, 0.0)
    }

    @Test
    fun `脏稳定度与未评分卡走同一夹取`() {
        val kernel = KernelHub.forId("FSRS")
        for (s in listOf(0.0, -4.0, Double.NaN)) {
            val sched = kernel.nextIntervalDays(reviewState(s), KernelRating.GOOD, 0.9, 3650.0)
            assertEquals("S=$s", sched, CardRecallCurve.dueMarkerDays(w, s, 0.9), 0.0)
        }
        // 新卡：内核拿到的是 stability=null，曲线侧还没有数 —— 两边都必须落到同一个
        // MIN_STABILITY_FLOOR 夹取，不许一边算出间隔、一边凭空造出另一个数
        val schedNull = kernel.nextIntervalDays(reviewState(null), KernelRating.GOOD, 0.9, 3650.0)
        assertEquals(schedNull, CardRecallCurve.dueMarkerDays(w, 0.0, 0.9), 0.0)
    }

    @Test
    fun `AGAIN 是调度器的十分钟专支不在相等性主张内`() {
        val kernel = KernelHub.forId("FSRS")
        val schedAgain = kernel.nextIntervalDays(reviewState(7.0), KernelRating.AGAIN, 0.9, 3650.0)
        assertEquals(FsrsModel.TEN_MINUTES_IN_DAYS, schedAgain, 0.0)
        assertNotEquals(schedAgain, CardRecallCurve.dueMarkerDays(w, 7.0, 0.9), 0.0)
    }

    // ── 曲线几何 ────────────────────────────────────────────────────

    @Test
    fun `曲线在到期标记处正好等于目标保留率`() {
        for (r in listOf(0.80, 0.88, 0.90, 0.95)) {
            val t = CardRecallCurve.dueMarkerDays(w, 10.0, r)
            assertEquals(r, FsrsModel.recall(w, 10.0, t), 1e-6)
        }
    }

    @Test
    fun `曲线单调不增且逐日从第1天排起`() {
        val pts = CardRecallCurve.points(w, 10.0, days = 40)
        assertEquals(40, pts.size)
        assertEquals(1.0, pts.first().first, 0.0)
        assertEquals(40.0, pts.last().first, 0.0)
        assertTrue(pts.zipWithNext().all { (a, b) -> a.second >= b.second })
        assertTrue(pts.all { (_, r) -> r > 0.0 && r <= 1.0 })
    }

    @Test
    fun `零稳定度与空天数区间退化为空曲线不抛`() {
        assertTrue(CardRecallCurve.points(w, 0.0, days = 10).isNotEmpty())
        assertTrue(CardRecallCurve.points(w, 10.0, days = 0).isEmpty())
        assertTrue(CardRecallCurve.points(w, 10.0, days = -5).isEmpty())
        val marker = CardRecallCurve.dueMarkerDays(w, 0.0, 0.9)
        assertTrue(marker.isFinite() && marker >= 0.0)
    }
}
