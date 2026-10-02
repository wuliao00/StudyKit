package com.studykit.ui.habit

import com.studykit.data.entity.Habit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 列表副标题的"优先执行意图"口径（计划 B Task 16 / spec §7）。
 *
 * 抽成纯函数 [habitSubtitleLine] 而不是留在 Compose 里，是为了让这条 UI 文案的分支
 * 能被普通 JVM 单测钉住（副标题文字本身就是给用户看的关键结果）。
 */
class HabitSubtitleTest {

    private fun item(ifThen: String, streak: Int = 3, isCountType: Boolean = false): HabitItemUi {
        val habit = Habit(
            uuid = "u-sub-$ifThen",
            name = "背单词",
            icon = "🎯",
            targetCount = if (isCountType) 50.0 else 0.0,
            ifThen = ifThen,
        )
        return HabitItemUi(
            habit = habit,
            checkedDates = emptySet(),
            totalCheckDays = 5,
            totalAmount = if (isCountType) 30.0 else 0.0,
            todayAmount = 0.0,
            streak = streak,
            checkedInToday = false,
            latestNote = "",
        )
    }

    @Test
    fun `副标题在有 ifThen 时优先展示整句`() {
        val sentence = "当早晨·书桌前，我就背 10 个单词"
        assertEquals(sentence, habitSubtitleLine(item(ifThen = sentence)))
    }

    @Test
    fun `副标题在 ifThen 为空时回退到既有连续口径`() {
        val text = habitSubtitleLine(item(ifThen = "", streak = 7))
        assertTrue(text, text.contains("连续 7 天"))
    }

    @Test
    fun `数量型回退口径仍走累计文案而非连续优先`() {
        val text = habitSubtitleLine(item(ifThen = "   ", isCountType = true))
        assertTrue(text, text.contains("累计"))
    }
}
