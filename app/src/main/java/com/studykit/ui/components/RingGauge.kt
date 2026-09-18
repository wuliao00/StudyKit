package com.studykit.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.studykit.ui.motion.MotionSpec
import com.studykit.ui.theme.AppTheme

/**
 * 进度环（自绘，零素材）：底环为轨道色，进度弧为强调色，12 点方向起算、圆头收尾。
 *
 * 从 `HabitListScreen` 的私有 `ProgressRing` 泛化而来，动效由 tween 升级为
 * [MotionSpec.ring] spring（damping 0.85 / stiffness 160）——逐帧跟随 vsync，
 * 高刷屏按 90/120Hz 渲染；`progress` 只读入 draw 阶段，因此进度变化只重绘、不重组。
 *
 * 达成态换色（如转 gold）由调用方传 [color] 决定，组件本身不做「>=1 变色」的隐式策略。
 *
 * @param progress 0..1，超出范围自动钳制。
 * @param strokeWidth 环的线宽；需小于组件尺寸，否则弧会被裁剪。
 */
@Composable
fun RingGauge(
    progress: Float,
    modifier: Modifier = Modifier,
    strokeWidth: Dp = 10.dp,
    color: Color = AppTheme.colors.accent,
    trackColor: Color = AppTheme.colors.divider,
) {
    val animated by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = MotionSpec.ring,
        label = "ringGauge",
    )
    Canvas(modifier = modifier) {
        val sw = strokeWidth.toPx()
        val inset = sw / 2f
        val arc = size.minDimension - sw
        val topLeft = Offset(x = inset, y = inset)
        val sz = Size(width = arc, height = arc)
        drawArc(
            color = trackColor,
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = topLeft,
            size = sz,
            style = Stroke(width = sw),
        )
        if (animated > 0f) {
            drawArc(
                color = color,
                startAngle = -90f,
                sweepAngle = 360f * animated,
                useCenter = false,
                topLeft = topLeft,
                size = sz,
                style = Stroke(width = sw, cap = StrokeCap.Round),
            )
        }
    }
}
