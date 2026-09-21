package com.studykit.ui.habit

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 贪吃蛇骨架（[buildSnakePath]）的纯逻辑单测。
 *
 * 钉的是"这条线真的能画成一条蛇"的三件事：**日期连续**（一天不落、一天不重）、
 * **网格上相邻**（相邻两节必须共边，否则身子中间会断开）、**蛇头停在今天**
 * （末列奇偶两种走法都要成立，因为换周时列的奇偶会翻）。
 *
 * 基准日 2026-09-18 是周五；往前 19 周得首列周一 2026-05-04，窗口共 138 天。
 */
class SnakePathTest {

    private val today = LocalDate.of(2026, 9, 18)

    @Test fun `路径长度等于窗口内已发生的天数`() {
        val path = buildSnakePath(today, weeks = 20)
        val first = path.first().date
        val last = path.last().date
        assertEquals(138, path.size)
        assertEquals(ChronoUnit.DAYS.between(first, last) + 1, path.size.toLong())
    }

    @Test fun `日期严格逐日递增且首格是周一`() {
        val path = buildSnakePath(today, weeks = 20)
        for (i in 1 until path.size) {
            assertEquals(1L, ChronoUnit.DAYS.between(path[i - 1].date, path[i].date))
        }
        assertEquals(DayOfWeek.MONDAY, path.first().date.dayOfWeek)
    }

    @Test fun `相邻两节在网格上共边所以身子不会断`() {
        val path = buildSnakePath(today, weeks = 20)
        for (i in 1 until path.size) {
            val dc = kotlin.math.abs(path[i].col - path[i - 1].col)
            val dr = kotlin.math.abs(path[i].row - path[i - 1].row)
            assertTrue(
                "第 $i 节与上一节不相邻：dc=$dc dr=$dr",
                dc + dr == 1,
            )
        }
    }

    @Test fun `蛇头停在今天 末列奇偶两种走法都成立`() {
        // weeks=20 → 末列下标 19（奇数列，自下而上）；weeks=21 → 末列 20（偶数列，自上而下）
        assertEquals(today, buildSnakePath(today, weeks = 20).last().date)
        assertEquals(today, buildSnakePath(today, weeks = 21).last().date)
        // 周五是周内第 5 格（i % 7 = 4），在奇数列里被翻到 row = 6 - 4 = 2
        val head = buildSnakePath(today, weeks = 20).last()
        assertEquals(19, head.col)
        assertEquals(2, head.row)
        // 偶数列末列（weeks=21 → 最后一列下标 20）不翻，同一天的行号就是 4
        assertEquals(4, buildSnakePath(today, weeks = 21).last().row)
    }

    @Test fun `周数不为正时是空路径而不是抛异常`() {
        assertTrue(buildSnakePath(today, weeks = 0).isEmpty())
        assertTrue(buildSnakePath(today, weeks = -3).isEmpty())
    }

    @Test fun `今天恰好是周日时末列走满七天`() {
        val sunday = LocalDate.of(2026, 9, 20)
        val path = buildSnakePath(sunday, weeks = 8)
        assertEquals(sunday, path.last().date)
        assertEquals(8 * 7, path.size)
    }
}
