package com.studykit.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.studykit.ui.habit.buildHeatmapCells
import com.studykit.ui.theme.AppTheme
import java.time.LocalDate

/**
 * GitHub 风格打卡热力图：`weeks` 列（旧 → 新）× 7 行（周一至周日）的圆角方格。
 * 打过卡的日子填 `accent`，没打的填 `heatIdle`（专用空格色），**未来（末列今天之后）不画**。
 *
 * 颜色显式取自 [AppTheme]（`AppTheme.colors.accent` / `.heatIdle`），双主题各自达标，
 * 不依赖 MaterialTheme 的局部覆写。空格用 `heatIdle` 而不是 `divider.copy(alpha = 0.5f)`：
 * 后者压在卡面只有 1.13:1（浅色）/ 1.14:1（夜间），格子之间基本分不出来；`heatIdle` 是专门为
 * 「卡面上要看得见的空格」提的一档（浅色＝divider 满不透明 1.29:1，夜间 `#454136` 1.52:1），
 * 仍是装饰性元素、不套文本 AA。
 *
 * 实现取向：**单 Canvas 画整张**（56 个格子一次遍历，不是 56 个 `Box`），
 * 尺寸完全由调用方给，推荐 `Modifier.fillMaxWidth().height(108.dp)`。
 * 格子边长取 `min(列宽, 行高)` 即**保持正方形**，整块网格再在画布内双向居中 ——
 * 8 周这种「宽 >> 高」的盒子里不会被拉伸成 7 行横条药丸（拉伸会立刻丢掉
 * GitHub 热力图「一格一天」的读法），20+ 周的宽窗口也不会上下错位。
 * 边长非正（未测量到尺寸 / 列数超过可用宽）时直接不出画，不画退化方框。
 *
 * 「今天」由 [LocalDate.now] 取系统默认时区，只在 `remember` 键里读一次；
 * 跨零点后若页面没有重组，末列的空格会停留在昨天的形状 —— 与列表其余统计同源，
 * 回到前台/重组即自愈，不为它加时钟。
 *
 * 无障碍：网格本身对读屏无意义，故整体挂一条 `contentDescription` 概述窗口与打卡天数，
 * 不再逐格播报（逐格会把 56 格念成 56 声）。
 */
@Composable
fun HeatmapWeeks(
    activeDays: Set<LocalDate>,
    weeks: Int = 8,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    val cols = weeks.coerceAtLeast(1)
    val today = LocalDate.now()
    val cells = remember(today, cols) { buildHeatmapCells(today, cols) }
    val activeDayCount = cells.sumOf { column -> column.count { it != null && it in activeDays } }
    val idleColor = colors.heatIdle
    val gap = AppTheme.space.xs
    Canvas(
        modifier = modifier.semantics {
            contentDescription = "近 $cols 周打卡热力图，$activeDayCount 天有打卡"
        },
    ) {
        val colCount = cells.size
        if (colCount == 0) return@Canvas
        val gapPx = gap.toPx()
        val rows = 7
        val cellW = (size.width - gapPx * (colCount - 1)) / colCount
        val cellH = (size.height - gapPx * (rows - 1)) / rows
        val side = minOf(cellW, cellH)
        if (side <= 0f) return@Canvas
        val gridWidth = colCount * side + (colCount - 1) * gapPx
        val gridHeight = rows * side + (rows - 1) * gapPx
        val originX = (size.width - gridWidth) / 2f
        val originY = (size.height - gridHeight) / 2f
        cells.forEachIndexed { col, column ->
            column.forEachIndexed { row, date ->
                if (date == null) return@forEachIndexed
                drawRoundRect(
                    color = if (date in activeDays) colors.accent else idleColor,
                    topLeft = Offset(
                        x = originX + col * (side + gapPx),
                        y = originY + row * (side + gapPx),
                    ),
                    size = Size(width = side, height = side),
                    // CornerRadius 是 @JvmInline value class：命名实参会把解析推到 internal 构造器上
                    // （CI: "Cannot access 'constructor(packedValue: Long)'"），单位半径重载本就表示两轴相等
                    cornerRadius = CornerRadius(side * 0.22f),
                )
            }
        }
    }
}
