package com.studykit.ui.habit

import com.studykit.data.entity.CheckIn
import com.studykit.data.entity.Habit
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 全局日历的打卡投影。
 *
 * 这里原本有个**只写不读**的缺陷：`check_ins.is_makeup` 一路写进了库，
 * 但投影时只把习惯名带出去，标记在这一步被丢掉 —— 于是更新说明里
 * 「补的单独标识不与当天混算」这句话在界面上根本不存在。
 * 编译不报错、日历也照常显示打卡，只有把标记跟到底才能发现，所以钉成测试。
 */
class GlobalCalendarProjectionTest {

    private val read = Habit(id = 1, uuid = "u1", name = "背单词", icon = "book")
    private val water = Habit(id = 2, uuid = "u2", name = "喝水", icon = "drop")
    private val habits = listOf(read, water)

    private fun checkIn(
        habitId: Long,
        date: String,
        makeup: Boolean = false,
    ) = CheckIn(
        id = habitId * 100 + date.hashCode().toLong(),
        uuid = "c-$habitId-$date",
        habitId = habitId,
        date = date,
        isMakeup = makeup,
    )

    private fun at(date: LocalDate) = groupCheckInsByDate(habits, listOf(
        checkIn(read.id, date.toString()),
    )).getValue(date).single()

    // ── 补卡标记必须跟到界面上 ──────────────────────────────────

    @Test
    fun `makeup flag survives the projection`() {
        val day = LocalDate.of(2026, 9, 22)
        val projected = groupCheckInsByDate(
            habits = habits,
            checkIns = listOf(
                checkIn(read.id, "2026-09-22", makeup = true),
                checkIn(water.id, "2026-09-22", makeup = false),
            ),
        ).getValue(day)

        val makeupRow = projected.first { it.name == "背单词" }
        val normalRow = projected.first { it.name == "喝水" }
        assertTrue("补卡标记在投影里丢了 —— 「单独标识」就成了空话", makeupRow.isMakeup)
        assertFalse("同一天正常打卡被误标成补卡", normalRow.isMakeup)
    }

    @Test
    fun `normal check-in is not marked`() {
        assertFalse(at(LocalDate.of(2026, 9, 20)).isMakeup)
    }

    // ── 归组与容错 ─────────────────────────────────────────────

    @Test
    fun `rows on the same day group together and different days stay apart`() {
        val grouped = groupCheckInsByDate(
            habits = habits,
            checkIns = listOf(
                checkIn(read.id, "2026-09-22"),
                checkIn(water.id, "2026-09-22"),
                checkIn(read.id, "2026-09-21"),
            ),
        )
        assertEquals(2, grouped.size)
        assertEquals(2, grouped.getValue(LocalDate.of(2026, 9, 22)).size)
        assertEquals(1, grouped.getValue(LocalDate.of(2026, 9, 21)).size)
    }

    @Test
    fun `unparseable date drops the whole row instead of landing on a bogus day`() {
        // 脏数据宁可不上屏：归到某个默认日会在日历上凭空多出一个谁都没打过的格子
        val grouped = groupCheckInsByDate(
            habits = habits,
            checkIns = listOf(
                checkIn(read.id, "not-a-date"),
                checkIn(read.id, ""),
                checkIn(read.id, "2026-09-22"),
            ),
        )
        assertEquals(1, grouped.size)
        assertEquals(listOf("背单词"), grouped.getValue(LocalDate.of(2026, 9, 22)).map { it.name })
    }

    @Test
    fun `check-in whose habit row is gone still shows and keeps its flag`() {
        // 删习惯会级联删打卡，但备份恢复等路径仍可能留下悬空 habit_id；
        // 这种行不能整条吞掉（用户会以为打卡丢了），也不能崩。
        val grouped = groupCheckInsByDate(
            habits = emptyList(),
            checkIns = listOf(checkIn(99, "2026-09-22", makeup = true)),
        ).getValue(LocalDate.of(2026, 9, 22)).single()

        assertEquals("习惯", grouped.name)
        assertTrue("兜底名字顺手把补卡标记也丢了", grouped.isMakeup)
    }
}
