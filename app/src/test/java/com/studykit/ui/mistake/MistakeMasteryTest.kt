package com.studykit.ui.mistake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 错题本科学化改造测试：重做式复习、掌握标准、错因标签、挤牙膏式提示、变体题标记。
 *
 * 依据：Karpicke & Blunt 2011（检索练习优于精读）、Metcalfe 2011/2017（超纠正）、
 * Butler 2011（高置信错误反馈后若不重测会回弹）、Dunlosky 2013（自我解释属中等效用）。
 */
class MistakeMasteryTest {

    // ── 掌握标准：跨间隔连续答对才算掌握 ────────────────────────────────

    @Test
    fun `one correct answer is not enough to master`() {
        assertFalse(MistakeMastery.isMastered(correctStreak = 1, lastGapDays = 7))
    }

    @Test
    fun `two consecutive correct answers across spaced gap masters the item`() {
        assertTrue(MistakeMastery.isMastered(correctStreak = 2, lastGapDays = 7))
        assertEquals(2, MistakeMastery.REQUIRED_CONSECUTIVE_CORRECT)
    }

    @Test
    fun `mastery requires the gap to actually be spaced`() {
        assertFalse("同一天连续答对不算跨间隔掌握", MistakeMastery.isMastered(correctStreak = 3, lastGapDays = 0))
    }

    @Test
    fun `wrong answer resets the streak`() {
        assertEquals(0, MistakeMastery.nextStreak(previous = 2, correct = false))
        assertEquals(3, MistakeMastery.nextStreak(previous = 2, correct = true))
    }

    // ── 复习方式：默认重做，不默认看解析 ────────────────────────────────

    @Test
    fun `review opens in redo mode by default`() {
        assertEquals(MistakeMastery.ReviewMode.REDO, MistakeMastery.defaultMode)
    }

    @Test
    fun `redo flow requires an attempt before the solution is shown`() {
        val flow = MistakeMastery.reviewFlow(attempted = false)
        assertFalse("未作答前不能直接看到完整解析", flow.canRevealSolution)
        assertTrue(flow.promptsRecall)
        val afterAttempt = MistakeMastery.reviewFlow(attempted = true)
        assertTrue(afterAttempt.canRevealSolution)
    }

    // ── 错因标签：概念 / 计算 / 审题 / 记忆 ─────────────────────────────

    @Test
    fun `cause tags cover the four common failure modes`() {
        val labels = MistakeMastery.CAUSES.map { it.label }
        assertTrue(labels.containsAll(listOf("概念不清", "计算失误", "审题偏差", "记忆模糊")))
        assertEquals(4, MistakeMastery.CAUSES.size)
    }

    @Test
    fun `each cause carries a self-explanation prompt`() {
        MistakeMastery.CAUSES.forEach { cause ->
            assertTrue("${cause.label} 缺少自我解释提示", cause.selfExplainPrompt.isNotBlank())
        }
    }

    @Test
    fun `cause tag maps to a feedback layer`() {
        assertEquals(MistakeMastery.FeedbackLayer.ROOT_CAUSE, MistakeMastery.feedbackLayerFor("概念不清"))
        assertEquals(MistakeMastery.FeedbackLayer.PROCESS, MistakeMastery.feedbackLayerFor("计算失误"))
    }

    // ── 挤牙膏提示：分步揭示，不给完整答案 ──────────────────────────────

    @Test
    fun `hints reveal one level at a time and stop before the answer`() {
        val first = MistakeMastery.nextHint(level = 0)
        assertEquals(1, first.level)
        assertEquals(MistakeMastery.HintKind.CUE, first.kind)
        val last = MistakeMastery.nextHint(level = MistakeMastery.HINT_LEVELS - 1)
        assertEquals(MistakeMastery.HINT_LEVELS, last.level)
        assertTrue("挤牙膏模式不能直接给出最终答案", !last.givesAwayAnswer)
    }

    @Test
    fun `hint ladder is cue then step then method`() {
        val ladder = (0 until MistakeMastery.HINT_LEVELS).map { MistakeMastery.nextHint(it).kind }
        assertEquals(MistakeMastery.HintKind.CUE, ladder.first())
        assertEquals(MistakeMastery.HintKind.FIRST_STEP, ladder[1])
        assertEquals(MistakeMastery.HintKind.METHOD, ladder[2])
    }

    // ── 排期：自动优先，手动可覆盖 ──────────────────────────────────────

    @Test
    fun `auto schedule wins unless user pinned an explicit date`() {
        assertTrue(MistakeMastery.shouldAutoSchedule(pinnedByUser = false))
        assertFalse(MistakeMastery.shouldAutoSchedule(pinnedByUser = true))
    }

    @Test
    fun `high confidence error is scheduled tighter than low confidence one`() {
        val sure = MistakeMastery.retentionFor(confident = true)
        val unsure = MistakeMastery.retentionFor(confident = false)
        assertTrue("高置信错题目标保留率更高：sure=$sure unsure=$unsure", sure > unsure)
    }

    // ── 反馈分层：任务级 + 过程级 + 自我调节级 ──────────────────────────

    @Test
    fun `feedback layers are ordered from answer to strategy`() {
        val layers = MistakeMastery.FEEDBACK_LAYERS
        assertEquals(MistakeMastery.FeedbackLayer.TASK, layers[0])
        assertEquals(MistakeMastery.FeedbackLayer.PROCESS, layers[1])
        assertEquals(MistakeMastery.FeedbackLayer.SELF_REGULATION, layers[2])
    }

    // ── 变体题：考点相同、外壳不同 ──────────────────────────────────────

    @Test
    fun `variant keeps the tested concept but flags a new surface`() {
        val v = MistakeMastery.variantOf(sourceTag = "分数加减法", surface = "购物找零")
        assertEquals("分数加减法", v.conceptTag)
        assertEquals("购物找零", v.surface)
        assertTrue(v.isVariant)
    }

    @Test
    fun `same item reviewed twice is not a variant`() {
        assertFalse(MistakeMastery.variantOf(sourceTag = "分数加减法", surface = null).isVariant)
    }
}
