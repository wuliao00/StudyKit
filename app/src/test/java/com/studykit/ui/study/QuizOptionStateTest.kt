package com.studykit.ui.study

import com.studykit.ui.components.QuizOptionState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 选项反馈态映射（[quizOptionState]）纯逻辑单元测试：只喂「下标 + 正确答案 + VM 判定 +
 * 本地乐观锁定」四个数，不依赖 Android / Compose，也不碰 `QuizScreen` 的任何快照。
 *
 * 这套映射就是刷题页的反馈契约（点下去锁住 → 判定 → 只有一份高亮），一旦写反就会
 * 出现「答对却标红」「残留 pending 盖掉判定」这类肉眼可见的错乱，所以逐条钉死。
 * 题面统一按 4 个选项、正确答案在 2（C）来喂。
 */
class QuizOptionStateTest {

    private val idle = QuizOptionState.Idle

    @Test fun `未作答且未点击时全部中性`() {
        for (i in 0..3) {
            assertEquals(idle, quizOptionState(index = i, answerIndex = 2, graded = null, pending = -1))
        }
    }

    @Test fun `点击后未回写时被点项为 Selected 其余中性`() {
        // DB 写入在飞的窗口：pending 让被点的砖块先亮起来，同时挡住第二次点击
        assertEquals(
            QuizOptionState.Selected,
            quizOptionState(index = 0, answerIndex = 2, graded = null, pending = 0),
        )
        for (i in listOf(1, 2, 3)) {
            assertEquals(idle, quizOptionState(index = i, answerIndex = 2, graded = null, pending = 0))
        }
    }

    @Test fun `答对时所选项为 Correct 不被 pending 盖成 Selected`() {
        // 用户点的正是正确项：先命中 Correct，绝不出现 Selected
        assertEquals(
            QuizOptionState.Correct,
            quizOptionState(index = 2, answerIndex = 2, graded = 2, pending = 2),
        )
        for (i in listOf(0, 1, 3)) {
            assertEquals(idle, quizOptionState(index = i, answerIndex = 2, graded = 2, pending = 2))
        }
    }

    @Test fun `答错时错项 Wrong 正确项 Correct 其余中性`() {
        assertEquals(
            QuizOptionState.Wrong,
            quizOptionState(index = 1, answerIndex = 2, graded = 1, pending = 1),
        )
        assertEquals(
            QuizOptionState.Correct,
            quizOptionState(index = 2, answerIndex = 2, graded = 1, pending = 1),
        )
        for (i in listOf(0, 3)) {
            assertEquals(idle, quizOptionState(index = i, answerIndex = 2, graded = 1, pending = 1))
        }
    }

    @Test fun `判定后 pending 残留不改写任何一项`() {
        // pending 是本地状态、可能停在旧下标上：判定分支必须完全不看它
        for (i in 0..3) {
            assertEquals(
                quizOptionState(index = i, answerIndex = 2, graded = 1, pending = -1),
                quizOptionState(index = i, answerIndex = 2, graded = 1, pending = 3),
            )
        }
    }

    @Test fun `正确下标越界的脏数据只亮错项不崩`() {
        // options_json 与 answer_index 不匹配时（历史数据）：无 Correct，用户所选项仍标 Wrong
        assertEquals(
            QuizOptionState.Wrong,
            quizOptionState(index = 3, answerIndex = 9, graded = 3, pending = -1),
        )
        for (i in 0..2) {
            assertEquals(idle, quizOptionState(index = i, answerIndex = 9, graded = 3, pending = -1))
        }
    }
}
