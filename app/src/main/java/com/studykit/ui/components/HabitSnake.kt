package com.studykit.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.studykit.ui.habit.buildSnakeDays
import com.studykit.ui.theme.AppTheme
import java.time.LocalDate

/** 一节占的横向距离（含右侧间距）；轨道总长 = 天数 × 这一档 */
private val SnakeTrackPitch: Dp = 20.dp

/** 一节本体的边长 */
private val SnakeCellSide: Dp = 14.dp

/** 蛇头比身子大一档，让"哪端是今天"不用看眼睛也分得出来 */
private val SnakeHeadSide: Dp = 18.dp

/** 连接各节的脊柱粗细 */
private val SnakeSpineWidth: Dp = 7.dp

/** 轨道绘制高度（含月份刻度那一条）；卡片按这个高度下发 */
val SnakeTrackHeight: Dp = 56.dp

/**
 * 习惯历史图：**一条可以左右滑的贪吃蛇**（纯 Canvas 自绘，零素材、零依赖）。
 *
 * 一天一节，从窗口第一天一路排到今天，整条轨道横向铺开、超出卡片的部分靠**左右滑动**看
 * （Codex CLI 那种"历史在一条线上跑"的味道）：
 *
 * - **落点永远在今天**（轨道最右端）：首帧之后自己滚到头，之后不再抢用户的滚动。
 * - **亮节（`accent`）= 那天打过卡，暗节（`heatIdle`）= 漏了**。节与节之间有一条 `divider` 色的脊柱
 *   连着，所以读起来是"一条蛇"而不是一排点。
 * - **蛇头在最右**，比身子大一档，带两只朝右的眼睛。
 * - 蛇头右边一枚**脉动的终端光标**，意思是"今天还没吃，可以吃"；「减弱动效」一开就不建这个动画。
 * - 每月第一天那节下面有一道竖刻度，用来在长轨道上定位月份（画线不排字，Canvas 里排文字要另走
 *   TextLayoutCache，不值当）。
 *
 * 帧预算：轨道本体是**静态**的，只有数据或主题变化才重录；脉动光标是独立的小节点，
 * 每帧重绘的面积只有那 3×18dp，不会拖着 140 节一起重画。
 *
 * @param weeks 往回看多少周（一节一天，轨道长度 = 天数 × [SnakeTrackPitch]）
 */
@Composable
fun HabitSnake(
    activeDays: Set<LocalDate>,
    weeks: Int = 20,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    val today = LocalDate.now()
    val days = remember(today, weeks) { buildSnakeDays(today, weeks.coerceAtLeast(1)) }
    val activeDayCount = remember(days, activeDays) { days.count { it in activeDays } }
    val reduceMotion = AppTheme.settings.reduceMotion

    val eaten = colors.accent
    val skipped = colors.heatIdle
    val spineColor = colors.divider
    val eyeColor = colors.onAccent
    val tickColor = colors.secondaryText

    val scrollState = rememberScrollState()
    // 只在第一次（且已经量出可滚范围时）自动滚到今天；之后用户滚到哪就停在哪
    var didCenter by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(days.size, scrollState.maxValue) {
        if (!didCenter && scrollState.maxValue > 0) {
            didCenter = true
            scrollState.scrollTo(scrollState.maxValue)
        }
    }

    val trackWidth = SnakeTrackPitch * days.size + AppTheme.space.sm
    val description = "近 $weeks 周打卡贪吃蛇，共 ${days.size} 天、其中 $activeDayCount 天有打卡，" +
        "蛇头在今天，左右滑动可看更早的历史"

    Box(modifier = modifier.horizontalScroll(scrollState).semantics { contentDescription = description }) {
        Box(modifier = Modifier.size(width = trackWidth, height = SnakeTrackHeight)) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                if (days.isEmpty()) return@Canvas
                val pitch = SnakeTrackPitch.toPx()
                val cell = SnakeCellSide.toPx()
                val head = SnakeHeadSide.toPx()
                val spineW = SnakeSpineWidth.toPx()
                val midY = (size.height - cell) / 2f
                fun centerX(index: Int): Float = pitch * index + pitch / 2f

                // 1) 脊柱先画，节再盖上去，接缝不会露白
                for (i in 1 until days.size) {
                    drawRect(
                        color = spineColor,
                        topLeft = Offset(centerX(i - 1) + cell / 2f, midY + cell / 2f - spineW / 2f),
                        size = Size(pitch - cell, spineW),
                    )
                }
                // 2) 每月第一天的刻度
                for (i in days.indices) {
                    if (days[i].dayOfMonth == 1) {
                        drawRect(
                            color = tickColor,
                            topLeft = Offset(centerX(i) - 1f, midY + cell + 4.dp.toPx()),
                            size = Size(2f, 6.dp.toPx()),
                        )
                    }
                }
                // 3) 身子（不含最后一格，最后一格留给蛇头）
                for (i in 0 until days.size - 1) {
                    drawRoundRect(
                        color = if (days[i] in activeDays) eaten else skipped,
                        topLeft = Offset(centerX(i) - cell / 2f, midY),
                        size = Size(cell, cell),
                        cornerRadius = CornerRadius(cell * 0.32f),
                    )
                }
                // 4) 蛇头 + 两只朝右的眼睛
                val headLeft = centerX(days.size - 1) - head / 2f
                val headTop = (size.height - head) / 2f
                drawRoundRect(
                    color = eaten,
                    topLeft = Offset(headLeft, headTop),
                    size = Size(head, head),
                    cornerRadius = CornerRadius(head * 0.34f),
                )
                val eyeR = head * 0.11f
                val eyeDx = head * 0.26f
                val eyeDy = head * 0.22f
                drawCircle(
                    color = eyeColor,
                    radius = eyeR,
                    center = Offset(headLeft + head / 2f + eyeDx, headTop + head / 2f - eyeDy),
                )
                drawCircle(
                    color = eyeColor,
                    radius = eyeR,
                    center = Offset(headLeft + head / 2f + eyeDx, headTop + head / 2f + eyeDy),
                )
            }

            // 5) 终端光标：独立小节点，脉动只让它自己重绘；减弱动效时连动画都不建
            if (!reduceMotion && days.isNotEmpty()) {
                val blink = rememberInfiniteTransition(label = "snakeCursor")
                val alpha by blink.animateFloat(
                    initialValue = 0.15f,
                    targetValue = 0.9f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = 620, easing = LinearEasing),
                        repeatMode = RepeatMode.Reverse,
                    ),
                    label = "snakeCursorAlpha",
                )
                Box(
                    modifier = Modifier
                        .offset(
                            x = SnakeTrackPitch * (days.size - 1) +
                                SnakeTrackPitch / 2f + SnakeHeadSide / 2f + 3.dp,
                        )
                        .size(width = 3.dp, height = SnakeHeadSide),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                color = colors.accentInk.copy(alpha = alpha),
                                shape = RoundedCornerShape(1.dp),
                            ),
                    )
                }
            }
        }
    }
}
