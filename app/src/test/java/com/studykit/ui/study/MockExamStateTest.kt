package com.studykit.ui.study

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 模考状态机（v2.7 计划 B Task 13）的**纯逻辑**测试。
 *
 * 钉的是「延迟反馈」这条承诺的内核：交卷之前，任何对错信息都拿不到；
 * 交卷那一刻才一次性算出每题的 (selected, correct)，未作答的题 selected 落 null、
 * 判为错但不崩。UI 侧的"看不见反馈"由 MockExamRenderTest 用组合真跑一遍钉死，
 * 这里只管状态机本身——练习模式的即时反馈路径完全分叉，那一侧代码不动。
 */
class MockExamStateTest {

    // ── 计划正文逐字给出的两条（规格）──────────────────────────────
    @Test fun `answer recorded but verdict withheld until submit`() {
        val st = MockExamState(questionIds = listOf(1L, 2L), answerCount = 4)
        st.answer(1L, selected = 2)
        assertNull(st.verdictOf(1L))          // 交卷前永远拿不到对错
        st.answer(2L, selected = 0)
        val result = st.submit()
        assertEquals(2, result.size)
        assertEquals(2, result.getValue(1L).selected)
    }

    @Test fun `unanswered question submits as skipped not crash`() {
        val result = MockExamState(listOf(9L), 4).submit()
        assertEquals(null, result.getValue(9L).selected)
    }

    // ── 补齐：把"交卷前拿不到 / 交卷后拿得到 + 判对判错 + 无崩路径"钉全 ──
    @Test fun `verdict appears only after submit and reflects answer key`() {
        val st = MockExamState(
            questionIds = listOf(1L, 2L),
            answerCount = 4,
            answerKey = mapOf(1L to 2, 2L to 1),
        )
        st.answer(1L, selected = 2)   // 命中答案
        st.answer(2L, selected = 0)   // 选错
        assertNull(st.verdictOf(1L))  // 交卷前仍然一片空白
        val result = st.submit()
        assertTrue(result.getValue(1L).correct)
        assertFalse(result.getValue(2L).correct)
        assertEquals(2, st.verdictOf(1L)?.selected)
        assertTrue(st.verdictOf(1L)?.correct == true)
    }

    @Test fun `latest selection before submit wins`() {
        val st = MockExamState(listOf(1L), 4, mapOf(1L to 3))
        st.answer(1L, selected = 0)
        st.answer(1L, selected = 3)
        val result = st.submit()
        assertEquals(3, result.getValue(1L).selected)
        assertTrue(result.getValue(1L).correct)
    }

    @Test fun `submit is idempotent and skips no crash on unknown ids`() {
        val st = MockExamState(listOf(1L), 4, mapOf(1L to 1))
        st.answer(999L, selected = 1)   // 不在题号表里的选择应被忽略，不崩
        val first = st.submit()
        val second = st.submit()
        assertEquals(first, second)     // 交卷两次结果一致
        assertEquals(null, first.getValue(1L).selected)
        assertFalse(first.getValue(1L).correct)
    }

    @Test fun `answered count reports recorded answers`() {
        val st = MockExamState(listOf(1L, 2L, 3L), 4)
        assertEquals(0, st.answeredCount)
        st.answer(2L, selected = 1)
        assertEquals(1, st.answeredCount)
    }
}
