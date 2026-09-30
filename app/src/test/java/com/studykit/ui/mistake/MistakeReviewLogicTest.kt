package com.studykit.ui.mistake

import com.studykit.data.entity.Mistake
import com.studykit.srs.Confidence
import com.studykit.srs.Fsrs
import com.studykit.srs.Rating
import com.studykit.srs.ReviewPlanner
import com.studykit.tips.StudyTips
import com.studykit.tips.TipEvent
import com.studykit.tips.TipId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * 错题复习纯逻辑测试：重做遮罩、隔天数、掌握状态转移、算法排期与钉住、
 * 队列优先级排序键、变体标记与错因提示。
 *
 * 依据：Roediger & Karpicke 2006（检索练习优于重读）、Metcalfe 2011（超纠正）、
 * Bjork 1994（必要难度）、Dunlosky 2013（自我解释）。
 * 时间一律取当地正午，避免时区/夏令时把日历日差算成 0 或 2 天。
 */
class MistakeReviewLogicTest {

    private val dayMs = Fsrs.DAY_MS

    /** 以 2026-03-01 为第 0 天，按当地正午构造时间戳 */
    private fun at(day: Int, hour: Int = 12): Long =
        LocalDate.of(2026, 3, 1)
            .plusDays(day.toLong())
            .atTime(hour, 0)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

    private fun mistake(
        id: Long = 1L,
        reviewAt: Long? = null,
        mastered: Boolean = false,
        cause: String = "",
        correctStreak: Int = 0,
        pinned: Boolean = false,
        stability: Double = 0.0,
        difficulty: Double = 5.0,
        reps: Int = 0,
        lapses: Int = 0,
        lastReviewAt: Long = 0L,
        highConfidenceError: Boolean = false,
        note: String = "",
        content: String = SAMPLE_CONTENT,
    ) = Mistake(
        id = id,
        uuid = "m-$id",
        source = Mistake.SOURCE_PRACTICE,
        subject = "数学",
        title = "第 $id 道错题",
        content = content,
        note = note,
        reviewAt = reviewAt,
        mastered = mastered,
        createdAt = at(0),
        cause = cause,
        correctStreak = correctStreak,
        pinned = pinned,
        stability = stability,
        difficulty = difficulty,
        reps = reps,
        lapses = lapses,
        lastReviewAt = lastReviewAt,
        highConfidenceError = highConfidenceError,
    )

    // ── 隔天数：按日历日，同天重做为零 ──────────────────────────────────

    @Test
    fun `first ever redo has no gap`() {
        assertEquals(0, MistakeReviewLogic.gapDays(lastRedoAt = null, now = at(0)))
        assertEquals(0, MistakeReviewLogic.gapDays(lastRedoAt = 0L, now = at(0)))
    }

    @Test
    fun `same calendar day redo counts zero gap days`() {
        assertEquals("早上做过、晚上再做不算跨间隔", 0, MistakeReviewLogic.gapDays(at(0, 8), at(0, 21)))
    }

    @Test
    fun `gap days follow calendar days instead of whole twenty four hour blocks`() {
        assertEquals(1, MistakeReviewLogic.gapDays(at(0, 23), at(1, 1)))
        assertEquals(7, MistakeReviewLogic.gapDays(at(0), at(7)))
    }

    @Test
    fun `a clock skew into the future never yields a negative gap`() {
        assertEquals(0, MistakeReviewLogic.gapDays(at(5), at(0)))
    }

    // ── 掌握状态转移：跨间隔连对两次才算掌握 ────────────────────────────

    @Test
    fun `same day second correct answer does not master the item`() {
        val plan = MistakeReviewLogic.planRedo(
            mistake = mistake(correctStreak = 1),
            correct = true,
            confidence = Confidence.VAGUE,
            hintLevel = 0,
            lastRedoAt = at(0, 9),
            now = at(0, 20),
        )
        assertEquals(2, plan.correctStreak)
        assertEquals("同天再做间隔为 0", 0, plan.lastGapDays)
        assertFalse("连对两次但没跨间隔，不能算掌握", plan.mastered)
    }

    @Test
    fun `two correct answers across a spaced gap master the item`() {
        val plan = MistakeReviewLogic.planRedo(
            mistake = mistake(correctStreak = 1),
            correct = true,
            confidence = Confidence.VAGUE,
            hintLevel = 0,
            lastRedoAt = at(0),
            now = at(2),
        )
        assertEquals(2, plan.correctStreak)
        assertEquals(2, plan.lastGapDays)
        assertTrue("跨间隔连对 ${MistakeMastery.REQUIRED_CONSECUTIVE_CORRECT} 次应转掌握", plan.mastered)
    }

    @Test
    fun `a wrong redo resets the streak and unmasters the item`() {
        val plan = MistakeReviewLogic.planRedo(
            mistake = mistake(correctStreak = 4),
            correct = false,
            confidence = Confidence.VAGUE,
            hintLevel = 3,
            lastRedoAt = at(0),
            now = at(3),
        )
        assertEquals(0, plan.correctStreak)
        assertFalse(plan.mastered)
        assertEquals(Rating.AGAIN, MistakeReviewLogic.ratingFor(correct = false, confidence = Confidence.SURE, hintLevel = 0))
    }

    @Test
    fun `rating maps hints and confidence onto the four fsrs grades`() {
        assertEquals(Rating.EASY, MistakeReviewLogic.ratingFor(correct = true, confidence = Confidence.SURE, hintLevel = 0))
        assertEquals(Rating.GOOD, MistakeReviewLogic.ratingFor(correct = true, confidence = Confidence.VAGUE, hintLevel = 0))
        assertEquals(Rating.GOOD, MistakeReviewLogic.ratingFor(correct = true, confidence = Confidence.SURE, hintLevel = 1))
        assertEquals(
            "靠提示走完第三档不该给 Easy",
            Rating.HARD,
            MistakeReviewLogic.ratingFor(correct = true, confidence = Confidence.SURE, hintLevel = MistakeMastery.HINT_LEVELS),
        )
    }

    // ── 排期：默认自动，钉住后算法不覆盖 ────────────────────────────────

    @Test
    fun `pinned review date survives an automatic reschedule`() {
        val pinnedAt = at(10, 9)
        val plan = MistakeReviewLogic.planRedo(
            mistake = mistake(pinned = true, reviewAt = pinnedAt),
            correct = true,
            confidence = Confidence.SURE,
            hintLevel = 0,
            lastRedoAt = at(0),
            now = at(1),
        )
        assertTrue("用户钉过日期就不该被算法改写", plan.keptPinned)
        assertEquals(pinnedAt, plan.reviewAt)
        assertEquals("记忆状态仍要照常更新", 1, plan.state.reps)
        assertFalse(MistakeMastery.shouldAutoSchedule(pinnedByUser = true))
    }

    @Test
    fun `unpinned item gets the algorithm date instead of the old one`() {
        val stale = at(10, 9)
        val plan = MistakeReviewLogic.planRedo(
            mistake = mistake(pinned = false, reviewAt = stale),
            correct = true,
            confidence = Confidence.VAGUE,
            hintLevel = 0,
            lastRedoAt = at(0),
            now = at(1),
        )
        assertFalse(plan.keptPinned)
        assertNotEquals("未钉住时复习时间由 FSRS 推算", stale, plan.reviewAt)
        assertTrue("排期必须落在将来：${plan.reviewAt}", plan.reviewAt > at(1))
    }

    @Test
    fun `new mistake is scheduled for the next day`() {
        assertEquals(at(0) + dayMs, MistakeReviewLogic.firstReviewAt(at(0)))
    }

    @Test
    fun `high confidence error is released back to a tighter auto date`() {
        val confident = mistake(highConfidenceError = true)
        val normal = mistake(highConfidenceError = false)
        val tight = MistakeReviewLogic.autoReviewAt(confident, at(0))
        val loose = MistakeReviewLogic.autoReviewAt(normal, at(0))
        assertTrue("高置信错题目标保留率更高、间隔更短：tight=$tight loose=$loose", tight < loose)
        assertTrue(tight >= MistakeReviewLogic.firstReviewAt(at(0)))
    }

    @Test
    fun `forgotten item comes back the same day for relearning`() {
        val plan = MistakeReviewLogic.planRedo(
            mistake = mistake(),
            correct = false,
            confidence = Confidence.SURE,
            hintLevel = 0,
            lastRedoAt = null,
            now = at(0),
        )
        assertTrue("高置信答错要标记下来", plan.highConfidenceError)
        assertEquals(0, plan.intervalDays)
        assertEquals("Again 走当日重学", at(0) + Fsrs.RELEARN_MS, plan.reviewAt)
        assertEquals(
            ReviewPlanner.nextRetention(Confidence.SURE, correct = false),
            plan.desiredRetention,
            1e-9,
        )
    }

    @Test
    fun `a once guessed wrong stays flagged as high confidence error`() {
        val plan = MistakeReviewLogic.planRedo(
            mistake = mistake(highConfidenceError = true),
            correct = true,
            confidence = Confidence.VAGUE,
            hintLevel = 0,
            lastRedoAt = at(0),
            now = at(1),
        )
        assertTrue("标记一旦留下就不因后来答对而抹掉", plan.highConfidenceError)
    }

    // ── 队列排序键：欠账 + 高置信靠前 ──────────────────────────────────

    @Test
    fun `unscheduled mistakes outrank healthy scheduled ones`() {
        val fresh = mistake(id = 1L)
        val healthy = mistake(id = 2L, stability = 20.0, reps = 3, lastReviewAt = at(0))
        val order = MistakeReviewLogic.queueOrder(listOf(healthy, fresh), now = at(0)).map { it.id }
        assertEquals(listOf(1L, 2L), order)
    }

    @Test
    fun `high confidence error outranks an identical unattributed mistake`() {
        val sure = mistake(id = 1L, highConfidenceError = true)
        val unsure = mistake(id = 2L, highConfidenceError = false)
        assertTrue(MistakeReviewLogic.queuePriority(sure, at(0)) > MistakeReviewLogic.queuePriority(unsure, at(0)))
        assertEquals(listOf(1L, 2L), MistakeReviewLogic.queueOrder(listOf(unsure, sure), at(0)).map { it.id })
    }

    @Test
    fun `deeper overdue outranks a recently due item`() {
        val behind = mistake(id = 1L, stability = 2.0, reps = 3, lastReviewAt = at(-10))
        val almostDue = mistake(id = 2L, stability = 2.0, reps = 3, lastReviewAt = at(-1))
        assertEquals(listOf(1L, 2L), MistakeReviewLogic.queueOrder(listOf(almostDue, behind), at(0)).map { it.id })
    }

    @Test
    fun `ties break on the earlier review date and unscheduled goes last`() {
        val noDate = mistake(id = 1L, reviewAt = null)
        val soon = mistake(id = 2L, reviewAt = at(2))
        assertEquals(listOf(2L, 1L), MistakeReviewLogic.queueOrder(listOf(noDate, soon), at(0)).map { it.id })
    }

    @Test
    fun `mastered items are never due`() {
        val alreadyMastered = mistake(reviewAt = at(0) - dayMs, mastered = true)
        val pending = mistake(reviewAt = at(0) - dayMs, mastered = false)
        assertFalse(MistakeReviewLogic.isDue(alreadyMastered, at(0)))
        assertTrue(MistakeReviewLogic.isDue(pending, at(0)))
        assertFalse("没排期的条目不算到期", MistakeReviewLogic.isDue(mistake(reviewAt = null), at(0)))
    }

    // ── 保留率与欠账天数 ────────────────────────────────────────────────

    @Test
    fun `unscheduled mistakes report no prediction instead of zero percent`() {
        assertNull(MistakeReviewLogic.predictedRetention(mistake(), at(0)))
        val scheduled = mistake(stability = 10.0, reps = 2, lastReviewAt = at(0))
        val retention = MistakeReviewLogic.predictedRetention(scheduled, at(0))
        assertNotNull(retention)
        assertTrue("刚复习完保留率应接近 1：$retention", retention!! > 0.99)
    }

    @Test
    fun `retention percent rounds and clamps into zero to one hundred`() {
        assertEquals(90, MistakeReviewLogic.retentionPercent(0.904))
        assertEquals(100, MistakeReviewLogic.retentionPercent(1.4))
        assertEquals(0, MistakeReviewLogic.retentionPercent(-0.2))
    }

    @Test
    fun `overdue days come from the pinned date when there is one`() {
        val threeDaysLate = mistake(reviewAt = at(0) - 3 * dayMs)
        assertEquals(3, MistakeReviewLogic.overdueDays(threeDaysLate, at(0)))
        assertEquals("未排期不报欠账", 0, MistakeReviewLogic.overdueDays(mistake(reviewAt = null), at(0)))
    }

    @Test
    fun `memory state mirrors the fsrs columns`() {
        val state = MistakeReviewLogic.memoryState(
            mistake(stability = 7.5, difficulty = 6.2, reps = 4, lapses = 1, lastReviewAt = at(-2)),
        )
        assertEquals(7.5, state.stability, 1e-9)
        assertEquals(6.2, state.difficulty, 1e-9)
        assertEquals(4, state.reps)
        assertEquals(1, state.lapses)
        assertEquals(at(-2), state.lastReviewAt)
    }

    // ── 重做遮罩：没提交就不给看答案与解析 ──────────────────────────────

    @Test
    fun `redo mode hides the solution until the attempt is submitted`() {
        assertTrue(
            "重做模式下未提交必须遮住答案与解析",
            MistakeReviewLogic.solutionIsHidden(MistakeMastery.ReviewMode.REDO, attempted = false),
        )
        assertFalse(MistakeReviewLogic.solutionIsHidden(MistakeMastery.ReviewMode.REDO, attempted = true))
        assertFalse("精读模式不遮", MistakeReviewLogic.solutionIsHidden(MistakeMastery.ReviewMode.SOLUTION, attempted = false))
    }

    @Test
    fun `masked content keeps the stem and options but drops answer and explanation`() {
        val masked = MistakeReviewLogic.visibleContent(
            mode = MistakeMastery.ReviewMode.REDO,
            attempted = false,
            content = SAMPLE_CONTENT,
        )
        assertTrue("题干要留着", masked.contains("甲乙两人相向而行"))
        assertTrue("选项要留着", masked.contains("C. 30"))
        assertFalse("不能出现正确答案行", masked.contains("正确答案"))
        assertFalse("不能出现解析内容", masked.contains("先算速度和"))
        assertEquals(
            "未提交时看到的是遮罩后的内容",
            MistakeReviewLogic.maskedContent(SAMPLE_CONTENT),
            masked,
        )
        val full = MistakeReviewLogic.visibleContent(MistakeMastery.ReviewMode.REDO, attempted = true, content = SAMPLE_CONTENT)
        assertEquals("提交之后就该是完整内容", SAMPLE_CONTENT, full)
    }

    @Test
    fun `masking also strips plain answer and detail lines`() {
        val masked = MistakeReviewLogic.maskedContent("答案：B\n详解：代入检验\n题干：保持不变")
        assertEquals("题干：保持不变", masked)
    }

    @Test
    fun `redo requires a confidence rating before submitting`() {
        assertFalse(MistakeReviewLogic.canSubmitRedo(null))
        assertTrue(MistakeReviewLogic.canSubmitRedo(Confidence.GUESS))
    }

    @Test
    fun `hint ladder stops at three levels and never gives the answer away`() {
        assertEquals(1, MistakeReviewLogic.nextHintLevel(0))
        assertEquals(3, MistakeReviewLogic.nextHintLevel(3))
        assertTrue(MistakeReviewLogic.canRevealMoreHints(2))
        assertFalse(MistakeReviewLogic.canRevealMoreHints(MistakeMastery.HINT_LEVELS))
        assertEquals(0, MistakeReviewLogic.revealedHints(0).size)
        assertEquals(listOf(1, 2, 3), MistakeReviewLogic.revealedHints(9).map { it.level })
        MistakeReviewLogic.revealedHints(MistakeMastery.HINT_LEVELS).forEach { hint ->
            assertFalse("第 ${hint.level} 档不该直接给答案", hint.givesAwayAnswer)
        }
    }

    @Test
    fun `mode caption explains why recall beats rereading`() {
        assertTrue(MistakeReviewLogic.modeCaption(MistakeMastery.ReviewMode.REDO).contains("遮"))
        assertTrue(MistakeReviewLogic.modeCaption(MistakeMastery.ReviewMode.SOLUTION).contains("精读"))
    }

    // ── 错因标签与贴士 ──────────────────────────────────────────────────

    @Test
    fun `only the four known causes carry a self explanation prompt`() {
        MistakeMastery.CAUSES.forEach { cause ->
            assertEquals(cause.selfExplainPrompt, MistakeReviewLogic.selfExplainPrompt(cause.label))
        }
        assertNull("未归因没有提示", MistakeReviewLogic.selfExplainPrompt(""))
        assertNull(MistakeReviewLogic.selfExplainPrompt("运气不好"))
    }

    @Test
    fun `tips after a redo come straight from the tip library`() {
        assertNull(MistakeReviewLogic.tipForRedo(correct = true, confidence = Confidence.SURE))
        assertEquals(
            StudyTips.forEvent(TipEvent.HighConfidenceMistake),
            MistakeReviewLogic.tipForRedo(correct = false, confidence = Confidence.SURE),
        )
        assertEquals(
            TipId.HYPERCORRECTION,
            MistakeReviewLogic.tipForRedo(correct = false, confidence = Confidence.SURE)?.id,
        )
        assertEquals(
            TipId.DESIRABLE_DIFFICULTY,
            MistakeReviewLogic.tipForRedo(correct = false, confidence = Confidence.GUESS)?.id,
        )
    }

    // ── 变体标记：考点不变、外壳由用户换 ────────────────────────────────

    @Test
    fun `variant line keeps the concept and records the new surface`() {
        val line = MistakeReviewLogic.variantNoteLine(conceptTag = "分数加减法", surface = "购物找零")
        assertTrue(line.startsWith(MistakeReviewLogic.VARIANT_PREFIX))
        assertTrue("考点应保留：$line", line.contains("考点=分数加减法"))
        assertTrue("情境由用户自己写：$line", line.contains("情境=购物找零"))
    }

    @Test
    fun `an empty surface is not a variant and adds no line`() {
        assertEquals("", MistakeReviewLogic.variantNoteLine(conceptTag = "分数加减法", surface = "   "))
    }

    @Test
    fun `variant count only counts variant lines`() {
        val note = "一开始抄错了条件\n${MistakeReviewLogic.VARIANT_PREFIX}考点=甲｜情境=乙\n${MistakeReviewLogic.VARIANT_PREFIX}考点=甲｜情境=丙"
        assertEquals(2, MistakeReviewLogic.variantCount(note))
        assertEquals(0, MistakeReviewLogic.variantCount(""))
    }

    private companion object {
        const val SAMPLE_CONTENT = "题干：甲乙两人相向而行\nA. 10\nB. 20\nC. 30\n正确答案：C. 30\n解析：先算速度和。"
    }
}
