package com.studykit.ui.habit

import com.studykit.data.entity.Habit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

/**
 * 时段分类排序纯函数（v2.4 批次四）的 JVM 单测：slotForHour 边界、
 * 当前时段组置顶、组间 CATEGORIES 序、组内 sortOrder、归档隐藏。
 */
class HabitOrganizeTest {

    // ── slotForHour：小时 → 时段槽（边界逐个钉死） ───────────────────────

    @Test
    fun `slot boundaries 5-10 are morning`() {
        assertEquals("MORNING", slotForHour(hour = 5))
        assertEquals("MORNING", slotForHour(hour = 9))
    }

    @Test
    fun `slot boundaries 10-13 are forenoon`() {
        assertEquals("FORENOON", slotForHour(hour = 10))
        assertEquals("FORENOON", slotForHour(hour = 12))
    }

    @Test
    fun `slot boundaries 13-16 are noon`() {
        assertEquals("NOON", slotForHour(hour = 13))
        assertEquals("NOON", slotForHour(hour = 15))
    }

    @Test
    fun `slot boundaries 16-19 are afternoon`() {
        assertEquals("AFTERNOON", slotForHour(hour = 16))
        assertEquals("AFTERNOON", slotForHour(hour = 18))
    }

    @Test
    fun `slot boundaries 19-23 are evening`() {
        assertEquals("EVENING", slotForHour(hour = 19))
        assertEquals("EVENING", slotForHour(hour = 22))
    }

    @Test
    fun `slot boundaries 23-5 are night`() {
        assertEquals("NIGHT", slotForHour(hour = 23))
        assertEquals("NIGHT", slotForHour(hour = 0))
        assertEquals("NIGHT", slotForHour(hour = 4))
    }

    // ── sortedForList：排序规则 ──────────────────────────────────────────

    private fun item(
        id: Long,
        category: String,
        sortOrder: Int = 0,
        archived: Boolean = false,
    ): HabitItemUi = HabitItemUi(
        habit = Habit(
            id = id,
            uuid = "u$id",
            name = "习惯$id",
            icon = "🎯",
            category = category,
            sortOrder = sortOrder,
            archived = archived,
        ),
        checkedDates = emptySet(),
        totalCheckDays = 0,
        totalAmount = 0.0,
        todayAmount = 0.0,
        streak = 0,
        checkedInToday = false,
        latestNote = "",
    )

    @Test
    fun `current slot group goes first even with later categories order`() {
        val items = listOf(
            item(id = 1, category = "MORNING", sortOrder = 0),
            item(id = 2, category = "NOON", sortOrder = 0),
        )
        // 14 点是中午：NOON 组虽然 CATEGORIES 序在 MORNING 之后，也要顶到最前
        val sorted = sortedForList(items = items, now = LocalTime.of(14, 0))
        assertEquals(listOf(2L, 1L), sorted.map { it.habit.id })
    }

    @Test
    fun `non-current groups follow categories order`() {
        val items = listOf(
            item(id = 1, category = "EVENING", sortOrder = 0),
            item(id = 2, category = "ANY", sortOrder = 0),
            item(id = 3, category = "FORENOON", sortOrder = 0),
        )
        // 上午 10 点：FORENOON 置顶，其余按 ANY → EVENING
        val sorted = sortedForList(items = items, now = LocalTime.of(10, 0))
        assertEquals(listOf(3L, 2L, 1L), sorted.map { it.habit.id })
    }

    @Test
    fun `within group sorted by sortOrder ascending`() {
        val items = listOf(
            item(id = 1, category = "MORNING", sortOrder = 3),
            item(id = 2, category = "MORNING", sortOrder = 1),
            item(id = 3, category = "MORNING", sortOrder = 2),
        )
        val sorted = sortedForList(items = items, now = LocalTime.of(7, 0))
        assertEquals(listOf(2L, 3L, 1L), sorted.map { it.habit.id })
    }

    @Test
    fun `archived items are hidden`() {
        val items = listOf(
            item(id = 1, category = "ANY", sortOrder = 0),
            item(id = 2, category = "ANY", sortOrder = 1, archived = true),
        )
        val sorted = sortedForList(items = items, now = LocalTime.of(7, 0))
        assertTrue(sorted.none { it.habit.archived })
        assertEquals(listOf(1L), sorted.map { it.habit.id })
    }

    @Test
    fun `unknown category falls behind known ones instead of crashing`() {
        val items = listOf(
            item(id = 1, category = "WHATEVER", sortOrder = 0),
            item(id = 2, category = "NIGHT", sortOrder = 0),
        )
        val sorted = sortedForList(items = items, now = LocalTime.of(12, 0))
        assertEquals(listOf(2L, 1L), sorted.map { it.habit.id })
    }

    // ── categoryLabel：展示名映射 ────────────────────────────────────────

    @Test
    fun `category labels match design mapping`() {
        assertEquals("任意", categoryLabel(category = "ANY"))
        assertEquals("早晨", categoryLabel(category = "MORNING"))
        assertEquals("上午", categoryLabel(category = "FORENOON"))
        assertEquals("中午", categoryLabel(category = "NOON"))
        assertEquals("下午", categoryLabel(category = "AFTERNOON"))
        assertEquals("傍晚", categoryLabel(category = "EVENING"))
        assertEquals("晚上", categoryLabel(category = "NIGHT"))
        assertEquals("任意", categoryLabel(category = "GARBAGE"))
    }

    @Test
    fun `categories constant order stays stable`() {
        assertEquals(
            listOf("ANY", "MORNING", "FORENOON", "NOON", "AFTERNOON", "EVENING", "NIGHT"),
            Habit.CATEGORIES,
        )
    }
}
