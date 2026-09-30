package com.studykit.srs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 复习日志落库的字段完整性测试。
 *
 * FSRS 的个体化参数优化要靠「评分 + 时间」的历史日志，
 * 只记 correct 就等于把将来做算法拟合的数据白白丢掉。
 */
class ReviewLogTest {

    private val t0 = 1_760_000_000_000L

    @Test
    fun `word review log stores the fsrs rating grade`() {
        val log = ReviewLog.wordReview(1L, Rating.EASY, null, 12.0, t0)
        assertEquals(4, log.rating)
        val again = ReviewLog.wordReview(1L, Rating.AGAIN, null, 0.4, t0)
        assertEquals(1, again.rating)
    }

    @Test
    fun `word review log stores the self assessed confidence`() {
        val log = ReviewLog.wordReview(1L, Rating.GOOD, Confidence.SURE, 5.0, t0)
        assertEquals(2, log.confidence)
        val unassessed = ReviewLog.wordReview(1L, Rating.GOOD, null, 5.0, t0)
        assertEquals("未自评要落成 -1 而不是瞎猜 0", -1, unassessed.confidence)
    }

    @Test
    fun `again rating is logged as a failed recall`() {
        assertFalse(ReviewLog.wordReview(1L, Rating.AGAIN, null, 0.4, t0).correct)
        assertTrue(ReviewLog.wordReview(1L, Rating.HARD, null, 3.0, t0).correct)
    }

    @Test
    fun `stability snapshot is kept so the forgetting curve can be replayed`() {
        val log = ReviewLog.wordReview(1L, Rating.GOOD, Confidence.VAGUE, 7.5, t0)
        assertEquals(7.5, log.stabilityAfter, 1e-9)
        assertEquals(t0, log.reviewedAt)
    }

    @Test
    fun `log carries a uuid for future sync`() {
        val first = ReviewLog.wordReview(1L, Rating.GOOD, null, 2.0, t0)
        val second = ReviewLog.wordReview(1L, Rating.GOOD, null, 2.0, t0)
        assertTrue(first.uuid.isNotBlank())
        assertTrue(first.uuid != second.uuid)
    }
}
