package com.studykit.ui.habit

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/**
 * 打卡热力图的日期网格（纯函数，零 Android 依赖）：
 * **列 = 周**（旧 → 新，共 [weeks] 列），**行 = 周一..周日**（每列固定 7 格），
 * 今天之后的格子给 `null`（未来的日子还没有事实可画）。
 *
 * 语义要点（与 `HeatmapCellsTest` 一一对应）：
 * - **不读时钟**：[today] 由调用方喂进来，所以单测与本机时区无关；时区语义完全归属
 *   组合层那句 `LocalDate.now()`（系统默认时区，见 [com.studykit.ui.components.HabitSnake]）。
 * - **列按整周对齐**：首列起于「today 所在周的周一」再往前 `weeks - 1` 周，因此**首列永远是
 *   完整的过去一周**（不会出现半截周），残缺只可能落在末列今天之后。习惯创建日落在窗口内时，
 *   它之前的格子仍是「有日期、没打卡」的灰格 —— 与 GitHub 同款的语义折衷，不额外画第三种态。
 * - **[weeks] 边界**：`<= 0` 退化为空网格（不抛异常，组件侧钳成 1 列）；没有上限，
 *   窗口越宽格子越挤，由调用方按可视宽度自己选（本项目取 20 周）。
 */
fun buildHeatmapCells(today: LocalDate, weeks: Int): List<List<LocalDate?>> {
    if (weeks <= 0) return emptyList()
    val thisMonday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val firstMonday = thisMonday.minusWeeks((weeks - 1).toLong())
    return (0 until weeks).map { w ->
        (0 until 7).map { d ->
            val date = firstMonday.plusWeeks(w.toLong()).plusDays(d.toLong())
            if (date.isAfter(today)) null else date
        }
    }
}

/** 蛇身上的一节：网格坐标 + 它代表的那一天。 */
data class SnakeCell(val col: Int, val row: Int, val date: LocalDate)

/**
 * 窗口内已发生的每一天 → 贪吃蛇骨架：**先按时间排，再决定落在哪一格**。
 *
 * 先按「列 = 周、周内周一→周日」收出一条严格递增的日期序列（今天之后的 `null` 在这一步就滤掉），
 * 再把第 `i` 天放进 `col = i / 7`；偶数列自上而下（`row = i % 7`）、奇数列自下而上
 * （`row = 6 - i % 7`）。
 *
 * 为什么不是"照原网格的位置把奇数列倒着读"：那样奇数列里的日期顺序会变成**周日→周一**，
 * 时间往回走，蛇尾反而停在周一而不是今天。行号交给路径决定，才能同时拿到
 * 「相邻两天共边」与「最后一节是今天」这两条。
 *
 * 不变量（`SnakePathTest` 逐条钉着）：日期逐日递增、长度等于窗口内已发生的天数、
 * 相邻两节在网格上共边（`|Δcol| + |Δrow| == 1`，身子不会断）、最后一节永远是今天。
 *
 * 代价说清楚：折返之后**行不再等于星期**（奇数列里周一落在该列最后一格），
 * 所以"我周三总漏"这类纵向规律在这张图上读不出来了 —— 那是贪吃蛇造型的必要代价，
 * 要看星期分布请去「日历」页按周看。
 */
fun buildSnakePath(today: LocalDate, weeks: Int): List<SnakeCell> {
    val dates = buildHeatmapCells(today, weeks).flatMap { column -> column.filterNotNull() }
    return dates.mapIndexed { index, date ->
        val col = index / 7
        val rowInWeek = index % 7
        SnakeCell(col = col, row = if (col % 2 == 0) rowInWeek else 6 - rowInWeek, date = date)
    }
}
