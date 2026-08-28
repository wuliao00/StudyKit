package com.studykit.ui.study

import com.studykit.data.entity.Question
import com.studykit.data.entity.Word
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 学习会话状态模型（QuizUiState / CardSessionUi）纯逻辑单元测试，
 * 只验证派生属性计算，不依赖 Android 框架。
 */
class StudySessionLogicTest {

    private fun question(id: Long) = Question(
        id = id,
        uuid = "q$id",
        subject = "数学",
        stem = "1 + 1 = ?",
        optionsJson = """["1","2","3","4"]""",
        answerIndex = 1,
        explanation = "1 + 1 = 2",
    )

    // ── QuizUiState：题库练习会话 ───────────────────────────────────────

    @Test
    fun `empty quiz state is not started`() {
        val state = QuizUiState()
        assertFalse(state.started)
        assertFalse(state.finished)
        assertEquals(0, state.total)
        assertEquals(0, state.accuracyPercent)
        assertNull(state.current)
    }

    @Test
    fun `accuracy percent is computed from answers`() {
        val questions = listOf(question(1), question(2), question(3), question(4))
        val state = QuizUiState(questions = questions, index = 1, correctCount = 2)
        assertEquals(4, state.total)
        assertTrue(state.started)
        assertFalse(state.finished)
        assertEquals(50, state.accuracyPercent)
    }

    @Test
    fun `quiz finishes when index passes last question`() {
        val questions = listOf(question(1), question(2))
        val state = QuizUiState(questions = questions, index = 2, correctCount = 1)
        assertTrue(state.finished)
        assertNull(state.current)
    }

    @Test
    fun `current question follows index`() {
        val questions = listOf(question(1), question(2))
        val state = QuizUiState(questions = questions, index = 1)
        assertEquals(2L, state.current?.id)
    }

    // ── CardSessionUi：卡片学习会话 ─────────────────────────────────────

    private fun word(id: Long) = Word(
        id = id,
        uuid = "w$id",
        word = "word$id",
        meaning = "释义$id",
        example = "example $id",
    )

    @Test
    fun `empty card session is finished`() {
        val session = CardSessionUi()
        assertEquals(0, session.total)
        assertTrue(session.finished)
        assertNull(session.current)
    }

    @Test
    fun `card session tracks position and counts`() {
        val session = CardSessionUi(
            queue = listOf(word(1), word(2), word(3)),
            index = 2,
            knownCount = 1,
            unknownCount = 1,
        )
        assertEquals(3, session.total)
        assertEquals(3L, session.current?.id)
        assertFalse(session.finished)
    }

    @Test
    fun `card session finishes at queue end`() {
        val session = CardSessionUi(
            queue = listOf(word(1)),
            index = 1,
            knownCount = 1,
            unknownCount = 0,
        )
        assertTrue(session.finished)
    }
}
