package com.studykit.ui.habit

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 贪吃蛇轨道数据源（[buildSnakeDays]）的纯逻辑单测。
 *
 * 轨道那一侧是"下标即格子序号"，所以这里必须保证三件事：**一天不多一天不少**（长度与网格里
 * 已发生的天数一致）、**逐日连续**（不然蛇身上会出现凭空断掉的一节）、**末位是今天**
 * （蛇头才会停在今天，卡片进来时滚到最右端才是"今天"而不是某个历史日）。
 *
 * 基准日 2026-09-18 是周五；往前 19 周得首列周一 2026-05-04，窗口共 138 天。
 */
class SnakeDaysTest {

    private val today = LocalDate.of(2026, 9, 18)

    @Test fun `20 周窗口到周五共 138 天且末位是今天`() {
        val days = buildSnakeDays(today, weeks = 20)
        assertEquals(138, days.size)
        assertEquals(today, days.last())
        assertEquals(LocalDate.of(2026, 5, 4), days.first())
        assertEquals(DayOfWeek.MONDAY, days.first().dayOfWeek)
    }

    @Test fun `日期逐日连续不跳也不重`() {
        val days = buildSnakeDays(today, weeks = 20)
        for (i in 1 until days.size) {
            assertEquals(1L, ChronoUnit.DAYS.between(days[i - 1], days[i]))
        }
    }

    @Test fun `长度与网格里已发生的天数同源`() {
        val cells = buildHeatmapCells(today, weeks = 12)
        val expected = cells.sumOf { column -> column.count { it != null } }
        assertEquals(expected, buildSnakeDays(today, weeks = 12).size)
    }

    @Test fun `今天恰好是周日时窗口走满整周`() {
        val sunday = LocalDate.of(2026, 9, 20)
        val days = buildSnakeDays(sunday, weeks = 8)
        assertEquals(8 * 7, days.size)
        assertEquals(sunday, days.last())
    }

    @Test fun `周数不为正时返回空而不是抛`() {
        assertTrue(buildSnakeDays(today, weeks = 0).isEmpty())
        assertTrue(buildSnakeDays(today, weeks = -5).isEmpty())
    }
}
