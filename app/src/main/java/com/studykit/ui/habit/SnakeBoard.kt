package com.studykit.ui.habit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.studykit.ui.theme.AppTheme
import java.time.LocalDate
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** 棋盘列数 = 看多少周（与 HeatmapCard 同一口径），行数 = 一周七天 */
private const val BOARD_COLS = 20
private const val BOARD_ROWS = 7

/** 一次拖拽累计超过这个长度才算一次方向输入（px，按 40dp 换算） */
private const val SWIPE_THRESHOLD_DP = 40f

/**
 * 贪吃蛇棋盘（v2.4 批次一）：把近 20 周的打卡网格变成可玩棋盘。
 *
 * 分工：规则与状态全在 [SnakeGame]（纯 JVM、单测钉死），这里只剩
 * "把状态画出来 + 把手势喂进去 + 按节拍调 tick" 三件事 —— 第三次重做换来的分工。
 *
 * 几个刻意的选择：
 * - **游戏循环 keyed 在 isOver 上**，不是 keyed 在 game 上：转向不该重置步进节拍，
 *   否则快速滑两下等于白送一次免费加速。
 * - 计时用 `delay(stepMillis)` 而不是 `withFrameNanos` 数帧：这个游戏的节拍是
 *   每格 90~150ms，帧级精度毫无意义，delay 更省电也不占帧预算。
 * - 中途退出（返回热力图）直接丢弃对局 —— 这是挂在习惯页的点缀（Hamari 2014：
 *   游戏化有条件有效），不值得为它做存档。
 */
@Composable
fun SnakeBoard(
    activeDays: Set<LocalDate>,
    best: Int,
    onScore: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    val today = remember { LocalDate.now() }
    val grid = remember(today) { buildHeatmapCells(today, BOARD_COLS) }
    // 每个棋盘格 → 是否打过卡（金果判定与格子底色共用这一份映射）
    val checkedIn: Set<SnakeCell> = remember(grid, activeDays) {
        grid.flatMapIndexed { x, column ->
            column.mapIndexedNotNull { y, date ->
                date?.takeIf { it in activeDays }?.let { SnakeCell(x, y) }
            }
        }.toSet()
    }
    val dates: Map<Long, LocalDate> = remember(grid) {
        buildMap {
            grid.forEachIndexed { x, column ->
                column.forEachIndexed { y, date -> if (date != null) put(SnakeCell(x, y).key, date) }
            }
        }
    }

    var game by remember(checkedIn) { mutableStateOf(SnakeGame.start(BOARD_COLS, BOARD_ROWS, checkedIn)) }
    val latest = rememberUpdatedState(game)

    // 游戏循环：keyed 在 isOver —— 转向不重置节拍；结束即停；再来一局（isOver 翻回 false）重启
    LaunchedEffect(game.isOver, checkedIn) {
        while (isActive && !latest.value.isOver) {
            delay(latest.value.stepMillis.toLong())
            game = latest.value.tick()
        }
    }
    // 破纪录上报：只在结束那一刻判断一次，别每帧都写库
    LaunchedEffect(game.isOver) {
        val g = latest.value
        if (g.isOver && g.score > best) onScore(g.score)
    }

    val density = LocalDensity.current
    val threshold = with(density) { SWIPE_THRESHOLD_DP.dp.toPx() }

    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "得分 ${game.score}",
                style = texts.cardTitle,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "最高 ${maxOf(best, game.score)}",
                style = texts.caption,
                color = colors.secondaryText,
            )
        }
        Spacer(Modifier.height(AppTheme.space.sm))

        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val cell = (maxWidth / BOARD_COLS).coerceAtMost(17.dp)
            val boardPx = with(density) { cell.toPx() }
            val corner = CornerRadius(cell.toPx() * 0.22f, cell.toPx() * 0.22f)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(cell * BOARD_ROWS)
                    .background(colors.card)
                    .pointerInput(checkedIn) {
                        // 累计位移过阈值才算一次输入：慢滑时单帧位移很小，只看单帧会漏
                        var accX = 0f
                        var accY = 0f
                        detectDragGestures(
                            onDragStart = {
                                accX = 0f
                                accY = 0f
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                accX += amount.x
                                accY += amount.y
                                if (abs(accX) > threshold || abs(accY) > threshold) {
                                    val dir = if (abs(accX) > abs(accY)) {
                                        if (accX > 0) SnakeDirection.RIGHT else SnakeDirection.LEFT
                                    } else {
                                        if (accY > 0) SnakeDirection.DOWN else SnakeDirection.UP
                                    }
                                    game = latest.value.turn(dir)
                                    accX = 0f
                                    accY = 0f
                                }
                            },
                        )
                    }
                    .semantics {
                        contentDescription = "贪吃蛇棋盘，7 行 20 列，" +
                            "亮格是打过卡的日子，滑动控制蛇的方向，吃果子得分"
                    },
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val step = size.width / BOARD_COLS
                    val gap = step * 0.12f
                    // 底格：打过卡 = accentSoft，没打过 = heatIdle
                    for (x in 0 until BOARD_COLS) for (y in 0 until BOARD_ROWS) {
                        val date = dates[SnakeCell(x, y).key]
                        val painted = date != null && date in activeDays
                        drawRoundRect(
                            color = if (painted) colors.accentSoft else colors.heatIdle,
                            topLeft = Offset(x * step + gap / 2, y * step + gap / 2),
                            size = Size(step - gap, step - gap),
                            cornerRadius = corner,
                        )
                    }
                    // 果子：金果用 gold、普通果用 warning —— 与蛇身(success)三种色分开
                    val fx = game.food.x * step
                    val fy = game.food.y * step
                    drawRoundRect(
                        color = if (game.food in checkedIn) colors.gold else colors.warning,
                        topLeft = Offset(fx + gap, fy + gap),
                        size = Size(step - gap * 2, step - gap * 2),
                        cornerRadius = corner,
                    )
                    // 蛇：头大一点
                    game.snake.forEachIndexed { i, c ->
                        val inset = if (i == 0) gap * 0.4f else gap
                        drawRoundRect(
                            color = colors.success,
                            topLeft = Offset(c.x * step + inset, c.y * step + inset),
                            size = Size(step - inset * 2, step - inset * 2),
                            cornerRadius = corner,
                        )
                    }
                }
                if (game.isOver) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(colors.card.copy(alpha = 0.88f))
                            .pointerInput(Unit) {
                                detectTapGestures {
                                    game = SnakeGame.start(BOARD_COLS, BOARD_ROWS, checkedIn)
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "本局 ${game.score} 分",
                                style = texts.cardTitle,
                                color = colors.primaryText,
                            )
                            Spacer(Modifier.height(AppTheme.space.xs))
                            Text(
                                text = if (game.score >= best && game.score > 0) "新纪录！" else "最高 $best",
                                style = texts.caption,
                                color = colors.secondaryText,
                            )
                            Spacer(Modifier.height(AppTheme.space.xs))
                            Text(
                                text = "点按任意处再来一局",
                                style = texts.caption,
                                color = colors.accentInk,
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(AppTheme.space.xs))
        Text(
            text = "滑动控制方向 · 吃🍓 +10，落在打过卡的格子上 +25 · 撞墙或咬到自己就结束",
            style = texts.caption,
            color = colors.secondaryText,
        )
    }
}
