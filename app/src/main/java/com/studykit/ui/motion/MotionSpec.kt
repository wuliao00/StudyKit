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
    /**
     * 淡入淡出与颜色补间的基准时长，**Int 毫秒** —— 喂给 `tween(durationMillis: Int)`。
     * 注意与 [StaggerMs] 单位同名不同型（那个是 Long），两者不可互换传参。
     */
    const val FadeMs = 220

    /**
     * 错峰入场的逐级延迟，**Long 毫秒** —— 喂给 `delay(Long)`，故与 [FadeMs] 的 Int 不同型。
     * 每级 `index * StaggerMs`，index 由调用方限幅（见 [StaggeredIn]）。
     */
    const val StaggerMs = 40L

    /**
     * 导航**退场**页的淡出时长，Int 毫秒。必须**长于**入场页的 `fadeIn(FadeMs)`：
     * 转场期间两层同时半透明，若出场页先淡干净（原值 160ms < 220ms），
     * 在入场页尚未完全不透明的那 60ms 里会露出 `colors.background` 底色 —— 一道闪缝。
     * 280 = FadeMs(220) + 60ms 余量；不打算把入场页的 slide spring 沉降（≈600ms）也拉长，
     * 那会让每次推页都显得黏手，闪缝只需要「出场比入场晚淡完」即可消掉。
     */
    const val NavExitFadeMs = 280

    val press = spring<Float>(dampingRatio = 0.55f, stiffness = 420f)
    val snap = spring<Float>(dampingRatio = 0.72f, stiffness = 380f)
    val flip = spring<Float>(dampingRatio = 0.68f, stiffness = 240f)
    val ring = spring<Float>(dampingRatio = 0.85f, stiffness = 160f)

    /** 错峰入场（[StaggeredIn]）的位移/淡入 spring：轻微过冲后落位 */
    val stagger = spring<Float>(dampingRatio = 0.78f, stiffness = 260f)

    fun dpSpring() = spring<Dp>(
        dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 600f,
    )
    fun flyOut() = spring<Float>(dampingRatio = 0.62f, stiffness = 550f)

    /**
     * push 时**入场**页：从右侧推入（`slideInHorizontally` 的 lambda 是**动画起点**的偏移量，
     * 正值 = 起始位置在右侧）+ 淡入。
     */
    fun navEnter(): EnterTransition =
        slideInHorizontally(spring(dampingRatio = 0.85f, stiffness = 240f)) { it / 3 } +
            fadeIn(tween(FadeMs))

    /**
     * push 时**出场**页：向左被推走 + 淡出。
     *
     * 偏移量取**负**：`slideOutHorizontally` 的 lambda 是**动画终点**的偏移量，
     * 而 [navEnter] 的正值是「起点在右侧」—— 两者符号语义相反。这里若也写 `+it / 4`，
     * 出场页向右、入场页向左，两页在转场中段**背向岔开**，被让开的那条左缘只剩窗口底色透出
     * （与 [NavExitFadeMs] 那条闪缝是同一个位置，观感上像「旧页被新页拽走」）。
     * 取 `-it / 4` 才是「旧页向左让位」，
     * 并与 [navPopEnter]（`-it / 4` 起、向右回位）/[navPopExit]（`+it / 3` 终点、向右滑出）互成镜像。
     */
    fun navExit(): ExitTransition =
        slideOutHorizontally(spring(dampingRatio = 0.9f, stiffness = 300f)) { -it / 4 } +
            fadeOut(tween(NavExitFadeMs))

    /** pop（返回）时**入场**页：从左侧回到位（起点偏左）+ 淡入 */
    fun navPopEnter(): EnterTransition =
        slideInHorizontally(spring(dampingRatio = 0.85f, stiffness = 240f)) { -it / 4 } +
            fadeIn(tween(FadeMs))

    /** pop（返回）时**出场**页：向右滑出（终点偏右，与 [navPopEnter] 同向）+ 淡出 */
    fun navPopExit(): ExitTransition =
        slideOutHorizontally(spring(dampingRatio = 0.9f, stiffness = 300f)) { it / 3 } +
            fadeOut(tween(NavExitFadeMs))
}

/**
 * 按压回弹缩放：0.96 按下 → spring 恢复，供按钮/卡片/导航项复用。
 *
 * 只返回 [State]，**不代为应用**：调用方需把它挂到 `Modifier.graphicsLayer { scaleX = …; scaleY = … }`
 * 上（见 `AppButton`）。`graphicsLayer` 走渲染层缩放，不改变布局尺寸，因此不会在按压时挤动兄弟控件；
 * 用 `Modifier.scale` 也可，但那同样是绘制层，二者都不要改成 `size`/`width` 之类的布局属性。
 */
@Composable
fun rememberPressScale(source: MutableInteractionSource): State<Float> {
    val pressed by source.collectIsPressedAsState()
    return animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = MotionSpec.press,
        label = "pressScale",
    )
}

/**
 * 列表/卡片错峰入场：淡入 + 24dp 上移回弹；index 提供逐级延迟。
 *
 * **只有入场，没有退场**：内部 `remember` 的状态在条目离屏后即销毁，
 * 从列表移除 / 翻页离开时不会有淡出，退场请另走 `Modifier.animateItem()`（LazyList）或导航转场。
 * 同理，长列表快速滚动时刚进入视口的条目会重播一次入场（`remember` 重建）。
 *
 * `index` 由**调用方**提供，语义是「本次入场序列内的序号」，不是数据下标：
 * 请传**视口局部**下标（如 `LazyColumn` 首屏可见区间的相对位置）并自行限幅，建议 `minOf(index, 8)`
 * —— 每级 [StaggerMs](40ms)，不限幅时第 40 项要等 1.6s 才开始入场，首屏看起来像空白。
 * 限幅后 8 项之后（≥320ms）同帧开始，观感上仍是「逐条铺开」而不会有可见等待。
 */
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
        animationSpec = MotionSpec.stagger,
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
