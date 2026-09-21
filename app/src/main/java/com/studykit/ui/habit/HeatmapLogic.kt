package com.studykit.ui.habit

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/**
 * 打卡历史的日期网格（纯函数，零 Android 依赖）：
 * **列 = 周**（旧 → 新，共 [weeks] 列），**行 = 周一..周日**（每列固定 7 格），
 * 今天之后的格子给 `null`（未来的日子还没有事实可画）。
 *
 * 语义要点（与 `HeatmapCellsTest` 一一对应）：
 * - **不读时钟**：[today] 由调用方喂进来，所以单测与本机时区无关；时区语义完全归属
 *   组合层那句 `LocalDate.now()`（系统默认时区，见 [com.studykit.ui.components.HabitSnake]）。
 * - **列按整周对齐**：首列起于「today 所在周的周一」再往前 `weeks - 1` 周，因此**首列永远是
 *   完整的过去一周**（不会出现半截周），残缺只可能落在末列今天之后。习惯创建日落在窗口内时，
 *   它之前的格子仍是「有日期、没打卡」的灰格 —— 与 GitHub 同款的语义折衷，不额外画第三种态。
 * - **[weeks] 边界**：`<= 0` 退化为空网格（不抛异常，组件侧钳成 1 列）；没有上限。
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

/**
 * 贪吃蛇轨道的**唯一数据源**：窗口内已发生的天数，按时间从旧到新排好，今天最后一。
 *
 * 就是把 [buildHeatmapCells] 按「列 = 周、周内周一→周日」摊平并滤掉 `null`。
 * 轨道那一侧直接用返回值的下标当格子序号（一节一天、横向单条），
 * 所以"哪几天该出现、什么顺序"这件事全仓只有一份实现。
 *
 * 不变量（`SnakeDaysTest` 钉着）：逐日递增、首格是周一、末格是今天、
 * 长度 = `weeks` 周窗口里已经过去的天数（`weeks <= 0` 时为空而不是抛）。
 */
fun buildSnakeDays(today: LocalDate, weeks: Int): List<LocalDate> =
    buildHeatmapCells(today, weeks).flatMap { column -> column.filterNotNull() }
