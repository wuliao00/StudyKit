package com.studykit.ui.study

import com.studykit.data.entity.Word
import com.studykit.srs.Confidence
import com.studykit.srs.Fsrs
import com.studykit.srs.Rating
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 背单词检索优先改造的纯逻辑测试：阶段状态机、旧按钮到四档映射、
 * 走 FSRS 引擎的排期（Again 十分钟回炉）、记忆看板取数与百分比格式化。
 *
 * 只测无 Android 依赖的 [CardSessionLogic]，Compose UI 不在此覆盖范围。
 */
class CardSessionLogicTest {

    private val now = 1_700_000_000_000L

    private fun newWord(id: Long) = Word(
        id = id,
        uuid = "w$id",
        word = "word$id",
        meaning = "释义$id",
        example = "example $id",
    )

    private fun scheduledWord(id: Long, stability: Double, reps: Int, lastReviewAt: Long) = Word(
        id = id,
        uuid = "w$id",
        word = "word$id",
        meaning = "释义$id",
        example = "example $id",
        stability = stability,
        difficulty = 5.0,
        reps = reps,
        lapses = 0,
        lastReviewAt = lastReviewAt,
    )

    // ── 旧按钮到新四档的映射 ────────────────────────────────────────────

    @Test
    fun `legacy known button maps to GOOD`() {
        assertEquals(Rating.GOOD, CardSessionLogic.ratingForLegacy(known = true))
    }

    @Test
    fun `legacy unknown button maps to AGAIN`() {
        assertEquals(Rating.AGAIN, CardSessionLogic.ratingForLegacy(known = false))
    }

    // ── PRETEST 只对 reps==0 的新词出现 ─────────────────────────────────

    @Test
    fun `new word starts in pretest`() {
        assertEquals(CardPhase.PRETEST, CardSessionLogic.startingPhase(newWord(1)))
    }

    @Test
    fun `already studied word skips pretest and starts at recall`() {
        val studied = scheduledWord(id = 2, stability = 6.0, reps = 3, lastReviewAt = now)
        assertEquals(CardPhase.RECALL, CardSessionLogic.startingPhase(studied))
    }

    // ── 跳过的预测试仍进入 RECALL ───────────────────────────────────────

    @Test
    fun `skipped pretest still advances to recall`() {
        assertEquals(CardPhase.RECALL, CardSessionLogic.afterPretest(CardPhase.PRETEST, skipped = true))
    }

    @Test
    fun `attempted pretest also advances to recall`() {
        assertEquals(CardPhase.RECALL, CardSessionLogic.afterPretest(CardPhase.PRETEST, skipped = false))
    }

    @Test
    fun `confidence selection moves recall to answer`() {
        assertEquals(CardPhase.ANSWER, CardSessionLogic.afterConfidence(CardPhase.RECALL))
    }

    // ── Again 后 dueAt 落在 now + 10 分钟内 ─────────────────────────────

    @Test
    fun `again on new card is due within ten minutes`() {
        val outcome = CardSessionLogic.schedule(newWord(3), Rating.AGAIN, Confidence.GUESS, now)
        assertTrue(outcome.dueAt > now)
        assertTrue(outcome.dueAt <= now + Fsrs.RELEARN_MS)
        assertEquals(0, outcome.intervalDays)
        // firstRating(AGAIN)：稳定性极低，仍在短间隔学习档
        assertEquals(Word.STATUS_LEARNING, outcome.status)
    }

    @Test
    fun `again on scheduled card is due within ten minutes`() {
        val scheduled = scheduledWord(4, stability = 10.0, reps = 5, lastReviewAt = now - 3 * Fsrs.DAY_MS)
        val outcome = CardSessionLogic.schedule(scheduled, Rating.AGAIN, Confidence.VAGUE, now)
        assertTrue(outcome.dueAt > now)
        assertTrue(outcome.dueAt <= now + Fsrs.RELEARN_MS)
        assertEquals(0, outcome.intervalDays)
    }

    @Test
    fun `good on new card schedules a future day beyond today`() {
        val outcome = CardSessionLogic.schedule(newWord(5), Rating.GOOD, Confidence.SURE, now)
        assertTrue(outcome.dueAt > now + Fsrs.RELEARN_MS)
        assertTrue(outcome.intervalDays >= 1)
    }

    // ── 看板数字格式化与保留率百分比 ────────────────────────────────────

    @Test
    fun `retention percent rounds to whole percent`() {
        assertEquals(90, CardSessionLogic.retentionPercent(0.9))
        assertEquals(87, CardSessionLogic.retentionPercent(0.873))
        assertEquals(100, CardSessionLogic.retentionPercent(1.0))
        assertEquals(0, CardSessionLogic.retentionPercent(0.0))
    }

    @Test
    fun `board caption embeds scheduled count`() {
        assertEquals(
            "未来 14 天预测保留率走势 · 基于 12 个已排期单词",
            CardSessionLogic.boardCurveCaption(scheduledTotal = 12),
        )
    }

    @Test
    fun `unscheduled word has no predicted retention`() {
        assertNull(CardSessionLogic.predictedRetention(newWord(6), now))
        assertEquals(0, CardSessionLogic.overdueDays(newWord(6), now))
    }

    @Test
    fun `overdue word reports days past due`() {
        // 稳定性 1 天，上次复习 10 天前，到期远早于 now，欠账应为正
        val late = scheduledWord(7, stability = 1.0, reps = 4, lastReviewAt = now - 10 * Fsrs.DAY_MS)
        assertTrue(CardSessionLogic.overdueDays(late, now) > 0)
        val retention = CardSessionLogic.predictedRetention(late, now)
        assertTrue("拖了十天后预测保留率应很低", retention != null && retention < 0.5)
    }

    @Test
    fun `forgetting curve is non increasing and sized`() {
        val states = listOf(
            CardSessionLogic.wordToState(scheduledWord(8, stability = 5.0, reps = 3, lastReviewAt = now)),
            CardSessionLogic.wordToState(scheduledWord(9, stability = 2.0, reps = 2, lastReviewAt = now)),
        )
        val curve = CardSessionLogic.forgettingCurve(states, now, days = 14)
        assertEquals(14, curve.size)
        assertTrue("曲线整体应单调不增", curve.zipWithNext().all { (a, b) -> a >= b })
    }

    @Test
    fun `empty states give empty curve and zero percent`() {
        assertTrue(CardSessionLogic.forgettingCurve(emptyList(), now).isEmpty())
        assertEquals(0, CardSessionLogic.retentionPercent(0.0))
    }
}
