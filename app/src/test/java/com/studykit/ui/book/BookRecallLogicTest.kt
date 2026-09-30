package com.studykit.ui.book

import com.studykit.srs.Fsrs
import com.studykit.srs.MemoryState
import com.studykit.srs.Rating
import com.studykit.tips.StudyTips
import com.studykit.tips.TipEvent
import com.studykit.tips.TipId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 检索式笔记（合书回忆自测 + 精加工提问 + 书摘入队）的纯逻辑测试。
 *
 * 覆盖「先答后评」「自评三档→FSRS 排期单调性」「章末判定」「入队判定」
 * 「检索练习次数展示」与事件提示映射；证据文案一律引用 StudyTips，不允许测试端自编。
 */
class BookRecallLogicTest {

    private val t0 = 1_700_000_000_000L

    // ── 自评三档 → FSRS 评分映射 ─────────────────────────────────────────

    @Test
    fun `self score maps to fsrs rating`() {
        assertEquals(Rating.AGAIN, BookRecallLogic.ratingForSelfScore(0))
        assertEquals(Rating.HARD, BookRecallLogic.ratingForSelfScore(1))
        assertEquals(Rating.GOOD, BookRecallLogic.ratingForSelfScore(2))
    }

    @Test
    fun `out of range self score is coerced into three levels`() {
        assertEquals(Rating.AGAIN, BookRecallLogic.ratingForSelfScore(-3))
        assertEquals(Rating.GOOD, BookRecallLogic.ratingForSelfScore(9))
    }

    // ── 下次到期计算：排期单调性 ─────────────────────────────────────────

    @Test
    fun `complete recall is scheduled further out than partial recall`() {
        val partial = BookRecallLogic.nextReviewAt(selfScore = 1, now = t0)
        val complete = BookRecallLogic.nextReviewAt(selfScore = 2, now = t0)
        assertTrue("完整(2)应比部分(1)排得更远：complete=$complete partial=$partial", complete > partial)
    }

    @Test
    fun `partial recall is scheduled further out than forgotten recall`() {
        val forgotten = BookRecallLogic.nextReviewAt(selfScore = 0, now = t0)
        val partial = BookRecallLogic.nextReviewAt(selfScore = 1, now = t0)
        assertTrue("部分(1)应比没想起来(0)排得更远：partial=$partial forgotten=$forgotten", partial > forgotten)
    }

    @Test
    fun `zero self score returns to queue within the same day`() {
        val due = BookRecallLogic.nextReviewAt(selfScore = 0, now = t0)
        assertTrue("AGAIN 应排到当天（重学窗口），不能推到明天", due > t0 && due - t0 < Fsrs.DAY_MS)
        assertTrue("重学间隔应等于 RELEARN_MS", due - t0 == Fsrs.RELEARN_MS)
    }

    @Test
    fun `excerpt first review uses first rating path and later reviews use review path`() {
        val neverScheduled = MemoryState(stability = 0.0, difficulty = 5.0, lastReviewAt = t0, reps = 0, lapses = 0)
        val first = BookRecallLogic.scheduleExcerpt(neverScheduled, Rating.GOOD, now = t0)
        assertEquals(1, first.state.reps)
        assertEquals(2.4, first.state.stability, 1e-9)   // S0(GOOD) = w2
        assertTrue(first.dueAt > t0)

        val carried = MemoryState(stability = 5.0, difficulty = 5.0, lastReviewAt = t0 - 5 * Fsrs.DAY_MS, reps = 3, lapses = 0)
        val again = BookRecallLogic.scheduleExcerpt(carried, Rating.AGAIN, now = t0)
        assertEquals(4, again.state.reps)                 // 走 Fsrs.review，次数递增
        assertEquals(1, again.state.lapses)
        assertTrue("Again 当天回队", again.dueAt - t0 == Fsrs.RELEARN_MS)
    }

    // ── 先答后评：未写回忆不允许自评 ────────────────────────────────────

    @Test
    fun `blank answer blocks self assessment`() {
        assertFalse("空白回忆不允许自评", BookRecallLogic.canSelfAssess(""))
        assertFalse("只有空格也不允许自评", BookRecallLogic.canSelfAssess("   \n "))
    }

    @Test
    fun `written answer unlocks self assessment`() {
        assertTrue(BookRecallLogic.canSelfAssess("作者认为驯养就是建立联系。"))
    }

    // ── 章末判定 ─────────────────────────────────────────────────────────

    @Test
    fun `advancing a full chapter step counts as crossing a chapter`() {
        assertTrue(BookRecallLogic.crossedChapter(fromPage = 30, toPage = 40, totalPages = 200))
        assertTrue(BookRecallLogic.crossedChapter(fromPage = 30, toPage = 45, totalPages = 200))
    }

    @Test
    fun `reaching the end of the book counts as crossing a chapter`() {
        assertTrue(BookRecallLogic.crossedChapter(fromPage = 195, toPage = 200, totalPages = 200))
    }

    @Test
    fun `small forward steps and backward steps do not trigger chapter tip`() {
        assertFalse(BookRecallLogic.crossedChapter(fromPage = 30, toPage = 35, totalPages = 200))
        assertFalse(BookRecallLogic.crossedChapter(fromPage = 40, toPage = 30, totalPages = 200))
        assertFalse("总页数未知时不误报", BookRecallLogic.crossedChapter(fromPage = 0, toPage = 0, totalPages = 0))
    }

    // ── 书摘入队判定 ─────────────────────────────────────────────────────

    @Test
    fun `excerpt enters review queue only when checked and non empty`() {
        assertTrue(BookRecallLogic.shouldEnqueueExcerpt(enqueue = true, content = "真正重要的东西用眼睛是看不见的。"))
        assertFalse(BookRecallLogic.shouldEnqueueExcerpt(enqueue = false, content = "有内容但没勾选"))
        assertFalse(BookRecallLogic.shouldEnqueueExcerpt(enqueue = true, content = "   "))
    }

    // ── 检索练习次数展示格式化 ───────────────────────────────────────────

    @Test
    fun `recall count label replaces the excerpt total metric`() {
        assertEquals("还没有检索练习", BookRecallLogic.recallCountLabel(0))
        assertEquals("还没有检索练习", BookRecallLogic.recallCountLabel(-1))
        assertEquals("12 次检索练习", BookRecallLogic.recallCountLabel(12))
    }

    // ── 事件提示：有书摘零检索 ───────────────────────────────────────────

    @Test
    fun `excerpts without a single recall raise the excerpt only event`() {
        val event = BookRecallLogic.excerptOnlyEvent(excerptCount = 3, recallCount = 0)
        assertEquals(TipEvent.ExcerptOnlyNoRecall, event)
        val tip = BookRecallLogic.tipFor(event)
        assertNotNull("事件必须能取到文案", tip)
        assertEquals(TipId.RECALL_NOTES, tip!!.id)
        assertTrue("证据必须复用 StudyTips 的引用", tip.evidence.contains("Dunlosky"))
    }

    @Test
    fun `event disappears once at least one recall exists`() {
        assertNull(BookRecallLogic.excerptOnlyEvent(excerptCount = 3, recallCount = 1))
        assertNull("还没有书摘时无需提示", BookRecallLogic.excerptOnlyEvent(excerptCount = 0, recallCount = 0))
    }

    @Test
    fun `chapter crossing maps to the elaborative questioning tip`() {
        val tip = BookRecallLogic.tipFor(BookRecallLogic.chapterEvent(crossed = true))
        assertNotNull(tip)
        assertEquals(TipId.EXPLAIN_WHY, tip!!.id)
        assertTrue(tip.evidence.contains("Dunlosky"))
        assertNull("未跨章不给提示", BookRecallLogic.chapterEvent(crossed = false))
    }

    // ── 精加工提问模板：纯数据 ───────────────────────────────────────────

    @Test
    fun `recall question templates are plain non blank data`() {
        val templates = BookRecallLogic.RECALL_QUESTION_TEMPLATES
        assertTrue("模板至少 4 条", templates.size >= 4)
        templates.forEach {
            assertTrue("label 不能为空：$it", it.label.isNotBlank())
            assertTrue("问题文本不能为空：$it", it.text.isNotBlank())
        }
        assertTrue("应包含『为什么』类精加工提问", templates.any { it.text.contains("为什么") })
        assertTrue("应包含『解决什么问题』类提问", templates.any { it.text.contains("解决") })
    }

    @Test
    fun `default tip source stays on the shared tips library`() {
        // UI 不允许自写引用：与 StudyTips.forEvent 的输出逐字一致
        assertEquals(
            StudyTips.forEvent(TipEvent.ExcerptOnlyNoRecall),
            BookRecallLogic.tipFor(BookRecallLogic.excerptOnlyEvent(2, 0)),
        )
    }
}
