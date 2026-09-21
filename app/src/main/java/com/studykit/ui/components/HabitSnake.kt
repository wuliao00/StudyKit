package com.studykit.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.studykit.ui.habit.SnakeCell
import com.studykit.ui.habit.buildSnakePath
import com.studykit.ui.theme.AppTheme
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 习惯历史图：**贪吃蛇版打卡图**（纯 Canvas 自绘，零素材、零依赖）。
 *
 * 近 [weeks] 周摊成 20 列 × 7 行的格子，但不再是一颗颗散点 —— 而是**一条从窗口第一天爬到今天的蛇**：
 * 路径按列推进、偶数列自上而下、奇数列自下而上（骨架见 `ui/habit/HeatmapLogic.kt` 的 [buildSnakePath]），
 * 所以相邻两天永远共用一条边，身子是连着的。
 *
 * 三件事各自怎么读：
 * - **亮段（`accent`）= 那天打过卡**，暗段（`heatIdle`）= 那天漏了。蛇身长度 = 窗口内已过的天数（固定），
 *   会发亮的节数才是"坚持"，所以这不是"越长越好"，而是"越亮越好"。
 * - **蛇头永远停在今天**那一格，带两只眼睛，朝向爬行方向。头一律用 `accent` 实色，
 *   它标的是"现在爬到这儿"，与"今天打没打"是两件事 —— 否则今天没打卡时头上还要再分一档色，读起来更累。
 * - **尾细头粗**（节宽从 0.46 到 0.86 倍格边长线性变），这条锥度是"像蛇"的主要来源，
 *   比给每节加圆角管用得多。
 *
 * 换来的代价，写在明处：折返之后**行不再等于星期**（奇数列里周日是该列第一格），
 * 所以"我周三总漏"这类纵向规律在这张图上读不出来了；要看星期分布去「日历」页按周看。
 *
 * 绘制预算：一次记录约 140 节 + 139 条接缝 = 不到 300 个圆角矩形。它只在数据/主题变化时重录，
 * 滚动时复用已录好的 RenderNode，不参与每帧（本版 A/B 实测：滚动 p50 7ms，与底栏玻璃无关）。
 * 所有颜色只取 `AppTheme.colors`，间距只取 `AppTheme.space`。
 */
@Composable
fun HabitSnake(
    activeDays: Set<LocalDate>,
    weeks: Int = 20,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    val cols = weeks.coerceAtLeast(1)
    val today = LocalDate.now()
    val path = remember(today, cols) { buildSnakePath(today, cols) }
    val activeDayCount = remember(path, activeDays) { path.count { it.date in activeDays } }
    val gap = AppTheme.space.xs
    val eaten = colors.accent
    val skipped = colors.heatIdle
    val headColor = colors.accent
    // 眼睛取卡面色：亮段上的两只眼要在青绿上跳出来，取 onAccent 那一档（浅色=白、夜间=暖墨）更稳
    val eyeColor = colors.onAccent
    val description = "近 $cols 周打卡贪吃蛇，${activeDayCount} 天有打卡，蛇头停在今天"

    Canvas(modifier = modifier.semantics { contentDescription = description }) {
        val n = path.size
        if (n == 0) return@Canvas
        val gapPx = gap.toPx()
        val cellW = (size.width - gapPx * (cols - 1)) / cols
        val cellH = (size.height - gapPx * 6f) / 7f
        val side = min(cellW, cellH)
        if (side <= 0f) return@Canvas
        // 整块网格在画布里居中（沿用旧热力图的口径，卡片变宽时蛇不会贴左）
        val gridW = cols * side + (cols - 1) * gapPx
        val gridH = 7f * side + 6f * gapPx
        val originX = (size.width - gridW) / 2f
        val originY = (size.height - gridH) / 2f

        fun centerOf(cell: SnakeCell): Offset = Offset(
            x = originX + cell.col * (side + gapPx) + side / 2f,
            y = originY + cell.row * (side + gapPx) + side / 2f,
        )

        /** 尾细头粗：i 从 0（窗口第一天）到 n-1（今天）线性变粗 */
        fun widthOf(i: Int): Float {
            val t = if (n <= 1) 1f else i.toFloat() / (n - 1)
            return side * (0.46f + 0.40f * t)
        }

        fun segmentColor(index: Int): Color =
            if (path[index].date in activeDays) eaten else skipped

        // 1) 接缝先画：方形节随后盖住两端，接缝宽度取两节里较细的那条，才不会露出"胖接头"
        for (i in 1 until n) {
            val a = centerOf(path[i - 1])
            val b = centerOf(path[i])
            val thickness = min(widthOf(i - 1), widthOf(i))
            val dx = abs(b.x - a.x)
            val dy = abs(b.y - a.y)
            val horizontal = dx > dy
            drawRoundRect(
                color = segmentColor(i),
                topLeft = if (horizontal) Offset(min(a.x, b.x), a.y - thickness / 2f)
                else Offset(a.x - thickness / 2f, min(a.y, b.y)),
                size = if (horizontal) Size(dx, thickness) else Size(thickness, dy),
                cornerRadius = CornerRadius(thickness / 2f),
            )
        }

        // 2) 蛇身（不含最后一格，最后一格留给蛇头）
        for (i in 0 until n - 1) {
            val c = centerOf(path[i])
            val w = widthOf(i)
            drawRoundRect(
                color = segmentColor(i),
                topLeft = Offset(c.x - w / 2f, c.y - w / 2f),
                size = Size(w, w),
                cornerRadius = CornerRadius(w * 0.34f),
            )
        }

        // 3) 蛇头 + 两只眼，眼睛朝向由"上一节 → 头"的方向决定
        val head = centerOf(path[n - 1])
        val hw = side * 0.96f
        drawRoundRect(
            color = headColor,
            topLeft = Offset(head.x - hw / 2f, head.y - hw / 2f),
            size = Size(hw, hw),
            cornerRadius = CornerRadius(hw * 0.36f),
        )
        val prev = if (n >= 2) centerOf(path[n - 2]) else Offset(head.x - 1f, head.y)
        var dx = head.x - prev.x
        var dy = head.y - prev.y
        val len = sqrt(dx * dx + dy * dy)
        if (len > 0f) {
            dx /= len
            dy /= len
        }
        val eyeRadius = hw * 0.11f
        val forward = hw * 0.20f
        val spread = hw * 0.22f
        drawCircle(
            color = eyeColor,
            radius = eyeRadius,
            center = Offset(head.x + dx * forward - dy * spread, head.y + dy * forward + dx * spread),
        )
        drawCircle(
            color = eyeColor,
            radius = eyeRadius,
            center = Offset(head.x + dx * forward + dy * spread, head.y + dy * forward - dx * spread),
        )
    }
}
