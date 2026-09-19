package com.studykit.ui.habit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * 打卡热力图网格（[buildHeatmapCells]）纯逻辑单元测试：只喂 `today` + `weeks`，
 * 不依赖 Android / Compose，也**不读系统时钟**——函数把「今天」当参数收进来，
 * 因此时区语义完全由调用方（组合层的 `LocalDate.now()`）决定，本测试与本机时区无关。
 *
 * 网格契约：列 = 周（旧→新，共 `weeks` 列）、行 = 周一至周日（每列固定 7 格）、
 * 今天之后的日期为 `null`（未来的格子不出画），`weeks <= 0` 退化为空网格。
 *
 * 基准日 2026-09-18 是**周五**（`dayOfWeek.value = 5` → 行下标 4），所在周周一起于
 * 2026-09-14；往前推 7 周得 8 周窗口首列 2026-07-27。周末/周一两个边界另用
 * 2026-09-20（周日）与 2026-09-14（周一）钉死，跨年用 2026-01-05（周一）。
 */
class HeatmapCellsTest {

    private val today = LocalDate.of(2026, 9, 18)

    // ── brief 基例：形状与今天的位置 ─────────────────────────────────────

    @Test fun `列数等于周数且每列七天`() {
        val cells = buildHeatmapCells(today, weeks = 8)
        assertEquals(8, cells.size)
        cells.forEach { assertEquals(7, it.size) }
    }

    @Test fun `最后一列包含今天且未来为 null`() {
        val cells = buildHeatmapCells(today, weeks = 8)
        val last = cells.last()
        assertEquals(today, last[today.dayOfWeek.value - 1])
        val after = last.drop(today.dayOfWeek.value)
        assertEquals(true, after.all { it == null })
    }

    @Test fun `今天所在周起始为周一`() {
        val cells = buildHeatmapCells(today, weeks = 1)
        assertEquals(DayOfWeek.MONDAY, cells.single().first()!!.dayOfWeek)
    }

    // ── 窗口起点与「未来空格」的作用域 ───────────────────────────────────

    @Test fun `首列起于今天所在周再往前 weeks 减 1 周的周一`() {
        // 2026-09-14（本周一）往前 7 周 = 2026-07-27
        val cells = buildHeatmapCells(today, weeks = 8)
        assertEquals(LocalDate.of(2026, 7, 27), cells.first().first())
        assertEquals(DayOfWeek.MONDAY, cells.first().first()!!.dayOfWeek)
    }

    @Test fun `每一列的行序都是周一至周日`() {
        buildHeatmapCells(today, weeks = 8).forEach { column ->
            column.forEachIndexed { row, date ->
                if (date != null) assertEquals(row + 1, date.dayOfWeek.value)
            }
        }
    }

    @Test fun `未来空格只出现在最后一列今天之后`() {
        val cells = buildHeatmapCells(today, weeks = 8)
        assertTrue(cells.dropLast(1).flatten().none { it == null })
    }

    @Test fun `整窗日期连续递增且以今天收尾`() {
        val days = buildHeatmapCells(today, weeks = 8).flatten().filterNotNull()
        assertEquals(LocalDate.of(2026, 7, 27), days.first())
        assertEquals(today, days.last())
        // 56 格里只剩末列的周六周日是空格
        assertEquals(8 * 7 - 2, days.size)
        assertTrue(days.zipWithNext { from, to -> ChronoUnit.DAYS.between(from, to) }.all { it == 1L })
    }

    // ── 一周之内的两端：周日（无空格）与周一（六个空格） ──────────────────

    @Test fun `今天是周日时末列没有未来空格`() {
        val sunday = LocalDate.of(2026, 9, 20)
        val cells = buildHeatmapCells(sunday, weeks = 4)
        assertTrue(cells.last().none { it == null })
        assertEquals(sunday, cells.last().last())
        assertEquals(4 * 7, cells.flatten().filterNotNull().size)
    }

    @Test fun `今天是周一时末列只有首格有值`() {
        val monday = LocalDate.of(2026, 9, 14)
        val cells = buildHeatmapCells(monday, weeks = 2)
        assertEquals(monday, cells.last().first())
        assertEquals(6, cells.last().drop(1).count { it == null })
    }

    // ── 跨年与参数边界 ───────────────────────────────────────────────────

    @Test fun `跨年窗口仍是连续网格`() {
        val newYear = LocalDate.of(2026, 1, 5)   // 周一
        val cells = buildHeatmapCells(newYear, weeks = 8)
        val days = cells.flatten().filterNotNull()
        assertEquals(LocalDate.of(2025, 11, 17), days.first())
        assertEquals(newYear, days.last())
        assertEquals(8 * 7 - 6, days.size)
        assertTrue(days.zipWithNext { from, to -> ChronoUnit.DAYS.between(from, to) }.all { it == 1L })
    }

    @Test fun `周数非正数退化为空网格`() {
        assertTrue(buildHeatmapCells(today, weeks = 0).isEmpty())
        assertTrue(buildHeatmapCells(today, weeks = -3).isEmpty())
    }

    @Test fun `单周与五十多周都按周数出列`() {
        assertEquals(1, buildHeatmapCells(today, weeks = 1).size)
        assertEquals(53, buildHeatmapCells(today, weeks = 53).size)
    }
}
