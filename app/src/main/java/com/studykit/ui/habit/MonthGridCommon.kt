package com.studykit.ui.habit

import java.time.LocalDate

/**
 * 两页日历（`HabitCalendarScreen` 习惯打卡页 / `GlobalCalendarScreen` 全局日历页）共用的
 * 月网格骨架：表头文案、网格格数、补齐规则。
 *
 * 放在独立的这份文件里而不是塞进 `HeatmapLogic.kt`：那份是**热力图**的网格
 * （列 = 周、行 = 周一至周日、消费者是 `ui/components/HeatmapWeeks.kt`），与「一个月摊成
 * 6 行 7 列」是两套形状、两套语义；两者同住 `ui/habit` 已经足够让纯逻辑集中在一处，
 * 再把月网格并进去只会让文件名与内容互相说不清。
 *
 * 两个页面各自的日格（`DayCell` / `GlobalDayCell`）**不**合并：一个画打卡点与补卡圈、
 * 一个画打卡点与系统日程徽标，语义不同，控制方已裁定留待设备走查再议。
 */

/** 周一至周日表头（月首列为周一，与 [padToFullWeeks] 的前导空位同一口径） */
internal val WeekHeader = listOf("一", "二", "三", "四", "五", "六", "日")

/**
 * 月历网格固定 6 行（6 行 × 7 列 = 42 格）。
 *
 * 28/29/30 天的月份、以及月初不落在周一的月份原本只铺 5 行，翻月时 `AnimatedContent` 的两帧
 * 内容不等高，转场收束那一帧卡片会突跳一行的量；不足 42 格一律用空位补齐（见 [padToFullWeeks]），
 * 网格恒为 6 行。纯结构常量（行列数），不是新的 dp 度量。
 */
internal const val MonthGridCells: Int = 6 * 7

/**
 * 把「月初前导空位 + 当月各日」补齐成恒定的 [MonthGridCells] 格，供调用方 `chunked(7)` 出行。
 *
 * 空位用 `null` 表达：页面上它画成一枚与日格等宽的占位 `Spacer`，于是整行皆空的补位行也不会
 * 塌成 padding 高（6 行的固定高度正靠这个）。
 *
 * 已达 42 格（或多于 42 格）时**原样返回**，既不裁也不补：满格月本就无需再补，而真出现超量时
 * 默默吃掉格子比多出一行更难查。理论上月网格最多 `6 + 31 = 37` 格，42 是恒够用的上限。
 *
 * @param days 顺序为「[leadingBlanks] 个 `null` + 当月 1..月末」，其中
 *   `leadingBlanks = month.atDay(1).dayOfWeek.value - 1`（周一 = 0 … 周日 = 6）。
 */
internal fun padToFullWeeks(days: List<LocalDate?>): List<LocalDate?> =
    if (days.size >= MonthGridCells) days
    else days + List(size = MonthGridCells - days.size) { null }
