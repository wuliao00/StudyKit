package com.studykit.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
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
 * 直径取 `minDimension`，弧在容器内**双向居中**：非正方形容器（如 `fillMaxSize()` 的长条 Box）
 * 也不会把环挤到左上角，调用侧无需再自备正方形壳子。
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
    // 两枚 Stroke 提到组合层 remember：`Stroke` 是普通堆对象，画在 `Canvas` 里就会
    // **每帧各分配一次**。习惯页一张卡一枚环、冷启动那一下所有环同时从 0 补到当前进度
    // （约 500ms），10 个环就是每帧 20 次分配；提出来之后分配次数与帧数无关。
    // 注意只认 `strokeWidth` 这一个输入 —— `animated` 必须继续留在 draw 块里读，
    // 读在组合层会让进度补间从"重绘"退化成"每帧重组"。
    val strokePx = with(LocalDensity.current) { strokeWidth.toPx() }
    val trackStroke = remember(strokePx) { Stroke(width = strokePx) }
    val arcStroke = remember(strokePx) { Stroke(width = strokePx, cap = StrokeCap.Round) }
    Canvas(modifier = modifier) {
        val sw = strokeWidth.toPx()
        val arc = size.minDimension - sw
        // 弧在**非正方形**容器里也要居中：以 minDimension 定径后按各自轴向取中点，
        // 而不是贴着左上角（旧写法 inset,inset 只在下发正方形容器时看不出偏移）
        val topLeft = Offset(x = (size.width - arc) / 2f, y = (size.height - arc) / 2f)
        val sz = Size(width = arc, height = arc)
        drawArc(
            color = trackColor,
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = topLeft,
            size = sz,
            style = trackStroke,
        )
        if (animated > 0f) {
            drawArc(
                color = color,
                startAngle = -90f,
                sweepAngle = 360f * animated,
                useCenter = false,
                topLeft = topLeft,
                size = sz,
                style = arcStroke,
            )
        }
    }
}
