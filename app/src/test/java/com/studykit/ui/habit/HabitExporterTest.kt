package com.studykit.ui.habit

import com.studykit.data.entity.Habit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 习惯成就分享文本生成（HabitExporter.buildShareText）纯逻辑单元测试，
 * 不依赖 Android 框架。
 */
class HabitExporterTest {

    /** 天数型习惯（进度未达成） */
    private fun dayHabitItem(
        name: String = "跑步",
        icon: String = "run",
        checkDays: Int = 3,
        streak: Int = 2,
        checkedInToday: Boolean = true,
    ): HabitItemUi {
        val today = LocalDate.of(2026, 8, 28)
        val dates = (0 until checkDays).map { today.minusDays(it.toLong()) }.toSet()
        return HabitItemUi(
            habit = Habit(uuid = "u1", name = name, icon = icon),
            checkedDates = dates,
            totalCheckDays = checkDays,
            totalAmount = 0.0,
            todayAmount = if (checkedInToday) 1.0 else 0.0,
            streak = streak,
            checkedInToday = checkedInToday,
            latestNote = "",
        )
    }

    /** 数量型习惯（累计已达成目标） */
    private fun countHabitItemAchieved(): HabitItemUi {
        val today = LocalDate.of(2026, 8, 28)
        return HabitItemUi(
            habit = Habit(
                uuid = "u2", name = "喝水", icon = "💧",
                targetCount = 500.0, unit = "ml",
            ),
            checkedDates = setOf(today),
            totalCheckDays = 1,
            totalAmount = 500.0,
            todayAmount = 500.0,
            streak = 1,
            checkedInToday = true,
            latestNote = "",
        )
    }

    @Test
    fun `unachieved day habit line contains streak and progress`() {
        val text = HabitExporter.buildShareText(listOf(dayHabitItem()))
        assertTrue(text.contains("🏃 跑步"))
        assertTrue(text.contains("坚持了 3 天"))
        assertTrue(text.contains("连续 2 天"))
        assertTrue(text.contains("进度 3/21 天"))
    }

    @Test
    fun `achieved habit line marks goal reached`() {
        val text = HabitExporter.buildShareText(listOf(countHabitItemAchieved()))
        assertTrue(text.contains("已达成目标"))
        assertTrue(text.contains("500/500 ml"))
    }

    @Test
    fun `summary counts habits and max streak`() {
        val text = HabitExporter.buildShareText(
            listOf(
                dayHabitItem(streak = 2),
                dayHabitItem(name = "背单词", icon = "book", streak = 5),
            ),
        )
        assertTrue(text.contains("共 2 个习惯"))
        assertTrue(text.contains("最长连续 5 天"))
    }

    @Test
    fun `header line present`() {
        val text = HabitExporter.buildShareText(listOf(dayHabitItem()))
        assertTrue(text.startsWith("【StudyKit · 我的习惯打卡】"))
    }
}
