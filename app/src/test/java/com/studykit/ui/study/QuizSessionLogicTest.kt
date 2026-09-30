package com.studykit.ui.study

import com.studykit.data.entity.Question
import com.studykit.srs.Confidence
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
 * 刷题会话纯逻辑测试：交错开关下的顺序决策、信心闸门、挤牙膏档位门槛、
 * 分层反馈内容选择、超纠正识别与结果页统计。
 *
 * Compose 不参与测试，只验算「画之前已经决定好的东西」。
 */
class QuizSessionLogicTest {

    private fun question(id: Long, subject: String) = Question(
        id = id,
        uuid = "q-$id",
        subject = subject,
        stem = "第 $id 题题干",
        optionsJson = "[\"甲\",\"乙\",\"丙\",\"丁\"]",
        answerIndex = 0,
        explanation = "第 $id 题解析",
    )

    // ── 交错：开关与学科数共同决定顺序 ────────────────────────────────────

    @Test
    fun `single subject keeps the original order even with interleave on`() {
        val pool = (1L..6L).map { question(it, "数学") }
        val ordered = QuizSessionLogic.orderedQuestions(pool, interleave = true)
        assertEquals("单学科不该打散：$ordered", pool.map { it.id }, ordered.map { it.id })
    }

    @Test
    fun `interleave off keeps the blocked original order`() {
        val pool = listOf(question(1, "数学"), question(2, "数学"), question(3, "英语"), question(4, "英语"))
        val ordered = QuizSessionLogic.orderedQuestions(pool, interleave = false)
        assertEquals(pool.map { it.id }, ordered.map { it.id })
    }

    @Test
    fun `interleave on mixes subjects without losing any question`() {
        val pool = listOf(
            question(1, "数学"), question(2, "数学"), question(3, "数学"), question(4, "数学"),
            question(5, "英语"), question(6, "英语"), question(7, "英语"), question(8, "英语"),
        )
        val ordered = QuizSessionLogic.orderedQuestions(pool, interleave = true)
        assertEquals(pool.size, ordered.size)
        assertEquals(pool.map { it.id }.sorted(), ordered.map { it.id }.sorted())
        val sameNeighbour = ordered.zipWithNext().count { it.first.subject == it.second.subject }
        assertEquals("交错后不该有相邻同学科：$ordered", 0, sameNeighbour)
    }

    @Test
    fun `a single question is never reordered`() {
        val pool = listOf(question(1, "数学"))
        assertEquals(pool, QuizSessionLogic.orderedQuestions(pool, interleave = true))
    }

    @Test
    fun `subject count counts distinct subjects`() {
        val pool = listOf(question(1, "数学"), question(2, "数学"), question(3, "物理"))
        assertEquals(2, QuizSessionLogic.subjectCount(pool))
        assertEquals(0, QuizSessionLogic.subjectCount(emptyList()))
    }

    // ── 信心闸门：未自评不许作答 ─────────────────────────────────────────

    @Test
    fun `answering requires a confidence rating first`() {
        assertFalse(QuizSessionLogic.canAnswer(null))
        assertTrue(QuizSessionLogic.canAnswer(Confidence.GUESS))
        assertTrue(QuizSessionLogic.canAnswer(Confidence.SURE))
    }

    // ── 挤牙膏提示：三档用完才展开完整解析 ───────────────────────────────

    @Test
    fun `solution stays hidden until all three hint levels are used`() {
        assertFalse("零档不能看解析", QuizSessionLogic.canRevealSolution(0))
        assertFalse("第一档不能看解析", QuizSessionLogic.canRevealSolution(1))
        assertFalse("第二档不能看解析", QuizSessionLogic.canRevealSolution(2))
        assertTrue("第三档才放开解析", QuizSessionLogic.canRevealSolution(3))
    }

    @Test
    fun `feedback omits the explanation while hints are still available`() {
        val lines = QuizSessionLogic.feedbackLines(
            correct = false,
            confidence = Confidence.VAGUE,
            hintLevel = 2,
            explanation = "这道题考的是牛顿第二定律。",
        )
        assertTrue(lines.any { it.label == "本题" && it.text == "回答错误" })
        assertTrue(lines.any { it.label == "下一步" })
        assertTrue(
            "提示未用完三档时不得出现过程级解析：$lines",
            lines.none { it.text.contains("牛顿第二定律") },
        )
        assertTrue(
            "提示未用完三档时不得有 PROCESS 层：$lines",
            lines.none { it.layer.name == "PROCESS" },
        )
    }

    @Test
    fun `feedback gives the explanation once hints are exhausted`() {
        val lines = QuizSessionLogic.feedbackLines(
            correct = false,
            confidence = Confidence.VAGUE,
            hintLevel = 3,
            explanation = "这道题考的是牛顿第二定律。",
        )
        val process = lines.first { it.layer.name == "PROCESS" }
        assertEquals("思路", process.label)
        assertEquals("这道题考的是牛顿第二定律。", process.text)
        assertEquals(3, lines.size)
    }

    @Test
    fun `hints stop at the third level and never give away the answer`() {
        assertEquals(1, QuizSessionLogic.nextHintLevel(0))
        assertEquals(2, QuizSessionLogic.nextHintLevel(1))
        assertEquals("到顶就停在第三档", 3, QuizSessionLogic.nextHintLevel(3))
        assertEquals("越界也停在第三档", 3, QuizSessionLogic.nextHintLevel(9))
        assertTrue(QuizSessionLogic.canRevealMoreHints(2))
        assertFalse(QuizSessionLogic.canRevealMoreHints(3))
        QuizSessionLogic.revealedHints(3).forEach { hint ->
            assertFalse("第 ${hint.level} 档不该直接给答案", hint.givesAwayAnswer)
        }
        assertEquals(0, QuizSessionLogic.revealedHints(0).size)
        assertEquals(listOf(1, 2, 3), QuizSessionLogic.revealedHints(3).map { it.level })
    }

    @Test
    fun `lock text reports how many hint levels are missing`() {
        assertTrue(QuizSessionLogic.solutionLockText(0).contains("3"))
        assertTrue(QuizSessionLogic.solutionLockText(2).contains("1"))
        assertTrue(QuizSessionLogic.solutionLockText(3).contains("0"))
    }

    // ── 超纠正：高置信答错 ───────────────────────────────────────────────

    @Test
    fun `only a confident wrong answer counts as a high confidence error`() {
        assertTrue(QuizSessionLogic.isHighConfidenceError(correct = false, confidence = Confidence.SURE))
        assertFalse(QuizSessionLogic.isHighConfidenceError(correct = true, confidence = Confidence.SURE))
        assertFalse(QuizSessionLogic.isHighConfidenceError(correct = false, confidence = Confidence.GUESS))
        assertFalse(QuizSessionLogic.isHighConfidenceError(correct = false, confidence = null))
    }

    @Test
    fun `confident wrong answer gets the hypercorrection advice`() {
        val advice = QuizSessionLogic.nextStepAdvice(
            correct = false,
            confidence = Confidence.SURE,
            hintLevel = 0,
        )
        assertTrue("应引导写下当初为什么确定：$advice", advice.contains("为什么那么确定"))
        assertNotEqualsSame(
            QuizSessionLogic.nextStepAdvice(correct = false, confidence = Confidence.GUESS, hintLevel = 0),
            advice,
        )
    }

    @Test
    fun `guessing right is asked for an attribution`() {
        val advice = QuizSessionLogic.nextStepAdvice(correct = true, confidence = Confidence.GUESS, hintLevel = 0)
        assertTrue("蒙对要归因：$advice", advice.contains("蒙对"))
    }

    // ── 交错提示：文案只取自 StudyTips ───────────────────────────────────

    @Test
    fun `interleave tip shows when mixing actually happens`() {
        val tip = QuizSessionLogic.interleaveTip(interleave = true, subjectCount = 2, questionCount = 10)
        assertNotNull(tip)
        assertEquals(TipId.INTERLEAVE, tip!!.id)
        assertEquals(StudyTips.forEvent(TipEvent.BlockingStreak(items = 6, subjects = 1)), tip)
        assertTrue(tip.evidence.contains("Brunmair"))
    }

    @Test
    fun `blocking a single subject long enough raises the same tip`() {
        val tip = QuizSessionLogic.interleaveTip(interleave = false, subjectCount = 1, questionCount = 6)
        assertEquals(TipId.INTERLEAVE, tip?.id)
        assertNull("没到连做门槛就不提示", QuizSessionLogic.interleaveTip(false, 1, 5))
        assertNull("开了交错谈不上连做", QuizSessionLogic.interleaveTip(true, 1, 10))
        assertNull("多学科但没开交错", QuizSessionLogic.interleaveTip(false, 2, 10))
    }

    @Test
    fun `interleave copy tells the user struggling is expected`() {
        val tip = QuizSessionLogic.interleaveTip(interleave = true, subjectCount = 3, questionCount = 10)
        assertNotNull(tip)
        assertTrue("应说明觉得更难是正常的：${tip!!.text}", tip.text.contains("难"))
    }

    // ── 会话进度与结果统计 ───────────────────────────────────────────────

    private fun attempt(id: Long, confidence: Confidence, hintLevel: Int, correct: Boolean) =
        QuizAttempt(questionId = id, confidence = confidence, hintLevel = hintLevel, selected = 0, correct = correct)

    @Test
    fun `session is finished only after the last question`() {
        assertFalse(QuizSessionLogic.isFinished(running = false, questionCount = 10, index = 0))
        assertFalse(QuizSessionLogic.isFinished(running = true, questionCount = 10, index = 9))
        assertTrue(QuizSessionLogic.isFinished(running = true, questionCount = 10, index = 10))
        assertFalse("空题池不该被判为完成", QuizSessionLogic.isFinished(running = true, questionCount = 0, index = 0))
    }

    @Test
    fun `summary counts correct answers and high confidence errors`() {
        val attempts = listOf(
            attempt(1, Confidence.SURE, 0, correct = true),
            attempt(2, Confidence.SURE, 1, correct = false),
            attempt(3, Confidence.GUESS, 0, correct = true),
            attempt(4, Confidence.VAGUE, 2, correct = false),
        )
        assertEquals(2, QuizSessionLogic.correctCount(attempts))
        assertEquals(1, QuizSessionLogic.highConfidenceErrorCount(attempts))
        assertEquals(50, QuizSessionLogic.accuracyPercent(attempts))
        assertEquals("空会话不除零", 0, QuizSessionLogic.accuracyPercent(emptyList()))
    }

    @Test
    fun `option letters start at A and stay single letter`() {
        assertEquals("A", QuizSessionLogic.letterOf(0))
        assertEquals("B", QuizSessionLogic.letterOf(1))
        assertEquals("D", QuizSessionLogic.letterOf(3))
    }

    @Test
    fun `new mistake enters the queue the next day`() {
        val now = 1_700_000_000_000L
        assertEquals(now + 24L * 60L * 60L * 1000L, QuizSessionLogic.firstReviewAt(now))
    }

    private fun assertNotEqualsSame(unexpected: String, actual: String) {
        assertTrue("两条建议应当有别：$unexpected / $actual", unexpected != actual)
    }
}
