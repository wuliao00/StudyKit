package com.studykit.ui.motion

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** 全局 spring 动效规格：spring 逐帧跟随 vsync，高刷屏按 90/120Hz 渲染 */
object MotionSpec {
    const val FadeMs = 220
    const val StaggerMs = 40L

    val press = spring<Float>(dampingRatio = 0.55f, stiffness = 420f)
    val snap = spring<Float>(dampingRatio = 0.72f, stiffness = 380f)
    val flip = spring<Float>(dampingRatio = 0.68f, stiffness = 240f)
    val ring = spring<Float>(dampingRatio = 0.85f, stiffness = 160f)

    fun dpSpring() = spring<Dp>(
        dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 600f,
    )
    fun flyOut() = spring<Float>(dampingRatio = 0.62f, stiffness = 550f)

    fun navEnter(): EnterTransition =
        slideInHorizontally(spring(dampingRatio = 0.85f, stiffness = 240f)) { it / 3 } +
            fadeIn(tween(FadeMs))
    fun navExit(): ExitTransition =
        slideOutHorizontally(spring(dampingRatio = 0.9f, stiffness = 300f)) { it / 4 } +
            fadeOut(tween(160))
    fun navPopEnter(): EnterTransition =
        slideInHorizontally(spring(dampingRatio = 0.85f, stiffness = 240f)) { -it / 4 } +
            fadeIn(tween(FadeMs))
    fun navPopExit(): ExitTransition =
        slideOutHorizontally(spring(dampingRatio = 0.9f, stiffness = 300f)) { it / 3 } +
            fadeOut(tween(160))
}

/** 按压回弹缩放：0.96 按下 → spring 恢复，供按钮/卡片/导航项复用 */
@Composable
fun rememberPressScale(source: MutableInteractionSource): State<Float> {
    val pressed by source.collectIsPressedAsState()
    return animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = MotionSpec.press,
        label = "pressScale",
    )
}

/** 列表/卡片错峰入场：淡入 + 24dp 上移回弹；index 提供逐级延迟 */
@Composable
fun StaggeredIn(index: Int, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val shift = with(LocalDensity.current) { 24.dp.toPx() }
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (index > 0) delay(index * MotionSpec.StaggerMs)
        shown = true
    }
    val p by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.78f, stiffness = 260f),
        label = "stagger",
    )
    Box(
        modifier.graphicsLayer {
            alpha = (p * 2f).coerceIn(0f, 1f)
            translationY = (1f - p) * shift
        },
        content = { content() },
    )
}
