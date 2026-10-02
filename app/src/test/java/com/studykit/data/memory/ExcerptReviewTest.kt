package com.studykit.data.memory

import com.studykit.data.entity.Excerpt
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 检索式书摘排期的纯逻辑单测（v2.7 计划 B Task 18）。
 *
 * 钉三件事：
 *  1. `kernelStateOf(excerpt)` 从只有 stability 一维的行里推出正确的卡生命周期；
 *  2. `ExcerptReview.schedule` 走真内核（FSRS / 半衰期）产出「S 变硬、reviewCount+1、下一次在未来」；
 *  3. 超纠正 min-only：SURE×未忆起只把时刻往前拉，且 recalled 时 SURE 完全不改变排期。
 */
class ExcerptReviewTest {

    private val now = 1_700_000_000_000L

    private fun excerpt(stability: Double? = null, reviewCount: Int = 0) = Excerpt(
        uuid = "e-1",
        bookId = 1L,
        content = "摘录",
        createdAt = now - TimeUnit.DAYS.toMillis(3),
        stability = stability,
        reviewCount = reviewCount,
    )

    // ── kernelStateOf(excerpt) ────────────────────────────────────────────

    @Test
    fun `没评过 FSRS 的书摘是 LEARNING 且 stability 保持空`() {
        val state = kernelStateOf(excerpt(stability = null))
        assertNull(state.stability)
        assertEquals(CardState.LEARNING, state.cardState)
        assertEquals(5.0, state.difficulty, 0.0)
    }

    @Test
    fun `评过的书摘带真实稳定度进 REVIEW`() {
        val state = kernelStateOf(excerpt(stability = 12.0))
        assertEquals(12.0, state.stability!!, 0.0)
        assertEquals(CardState.REVIEW, state.cardState)
    }

    // ── schedule：FSRS 支 ─────────────────────────────────────────────────

    @Test
    fun `认识一次把稳定性写实并把下一次排到未来`() {
        val r = ExcerptReview.schedule(FsrsKernel(), excerpt(), ReviewGrade.RECALL, conf = null, now = now)
        assertEquals(1, r.reviewCount)
        assertNotNull(r.stability)
        assertTrue("首评后 S 应为正", r.stability!! > 0.0)
        // GOOD 首评 S0≈2.31 天 → 下一次至少一天开外
        assertTrue(r.nextReviewAt > now + TimeUnit.DAYS.toMillis(1))
    }

    @Test
    fun `忘记一次排到十分钟后再见`() {
        val r = ExcerptReview.schedule(FsrsKernel(), excerpt(), ReviewGrade.FORGET, conf = null, now = now)
        // AGAIN → 内核给 10 分钟地板；无信心时不叠侧信道，恰好 now+10min
        assertEquals(now + TimeUnit.MINUTES.toMillis(10), r.nextReviewAt)
    }

    @Test
    fun `reviewCount 在已有计数上再加一`() {
        val r = ExcerptReview.schedule(
            FsrsKernel(), excerpt(reviewCount = 4), ReviewGrade.VAGUE, conf = null, now = now,
        )
        assertEquals(5, r.reviewCount)
    }

    // ── 超纠正 min-only ───────────────────────────────────────────────────

    @Test
    fun `非常确定却忘记只把时刻往前拉不推后`() {
        val plain = ExcerptReview.schedule(FsrsKernel(), excerpt(), ReviewGrade.FORGET, conf = null, now = now)
        val sure = ExcerptReview.schedule(FsrsKernel(), excerpt(), ReviewGrade.FORGET, conf = Confidence.SURE, now = now)
        // min-only：侧信道只可能让下次更早或持平，绝不更晚
        assertTrue(sure.nextReviewAt <= plain.nextReviewAt)
    }

    @Test
    fun `忆起时信心档不改变排期`() {
        // 超纠正只在 SURE×未忆起 触发；recalled 时 conf=SURE 应与 conf=null 完全一致
        val plain = ExcerptReview.schedule(FsrsKernel(), excerpt(), ReviewGrade.RECALL, conf = null, now = now)
        val sure = ExcerptReview.schedule(FsrsKernel(), excerpt(), ReviewGrade.RECALL, conf = Confidence.SURE, now = now)
        assertEquals(plain.nextReviewAt, sure.nextReviewAt)
        assertEquals(plain.stability, sure.stability)
    }

    // ── 半衰期内核也走得通（KernelHub 可能挑到它）──────────────────────────

    @Test
    fun `半衰期内核同样产出正稳定度镜像与未来到期`() {
        val r = ExcerptReview.schedule(HalfLifeKernel(), excerpt(), ReviewGrade.RECALL, conf = null, now = now)
        assertEquals(1, r.reviewCount)
        assertNotNull(r.stability)
        assertTrue(r.nextReviewAt > now)
    }

    // ── excerptFrontHalf：回忆闸门只露前半截 ─────────────────────────────

    @Test
    fun `短书摘整条就是前半不遮蔽`() {
        assertEquals("很短的一句话", excerptFrontHalf("很短的一句话"))
    }

    @Test
    fun `长书摘只留前 40 字并以省略号收尾`() {
        val long = "记".repeat(60)
        val front = excerptFrontHalf(long)
        assertEquals("记".repeat(40) + "…", front)
        assertTrue(front.length < long.length)
    }
}
