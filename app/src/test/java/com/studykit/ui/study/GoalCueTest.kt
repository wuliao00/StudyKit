package com.studykit.ui.study

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [GoalCue] 的纯逻辑单测：目标梯度提示只陈述"还差多少"，达成 / 目标非法一律不吭声。
 */
class GoalCueTest {

    @Test
    fun `未完成时说清还差几词`() {
        assertEquals("距今日目标还差 2 词", GoalCue.text(done = 18, goal = 20))
    }

    @Test
    fun `达成目标不吭声`() {
        assertNull(GoalCue.text(done = 20, goal = 20))
        assertNull(GoalCue.text(done = 25, goal = 20))
    }

    @Test
    fun `目标非法不吭声`() {
        assertNull(GoalCue.text(done = 3, goal = 0))
        assertNull(GoalCue.text(done = 3, goal = -1))
    }
}
