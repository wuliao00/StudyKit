package com.studykit.ui.motion

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
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
     * 导航**退场**页的淡出时长，Int 毫秒。**不早于**入场页的 [fadeEnter]（默认时长即 [FadeMs]）：
     * 转场期间两层同时半透明，若出场页先淡干净（原值 160ms < 220ms），
     * 在入场页尚未完全不透明的那 60ms 里会露出 `colors.background` 底色 —— 一道闪缝。
     * 只需要「出场比入场晚淡完」，故取 280 = FadeMs(220) + 60ms 余量，
     * 不必去拉长入场页的 slide spring 沉降（[navEnter] 的 ζ=0.85 / k=240，
     * 按 4/(ζω₀)、ω₀=√k≈15.5 rad/s 估算约 300ms，取 1% 判据也只到 ~350ms）——
     * 把沉降拖长会让每次推页都显得黏手。
     */
    const val NavExitFadeMs = 280

    /**
     * **切卡**入场的淡入时长，Int 毫秒（`CardStudyScreen` 的 AnimatedContent）。
     * 短于 [FadeMs]：切卡是逐张推进的高频动作，220ms 会让「下一张」读起来黏手；
     * 与并行的 slide spring [cardSwitch] 合起来是「卡片滑进来 + 顺手淡一下」。
     */
    const val CardSwitchInMs = 180

    /**
     * **切卡**退场的淡出时长，Int 毫秒。**短于**入场的那条 [CardSwitchInMs]：
     * 与导航退场（[NavExitFadeMs]，那里要晚于入场免得露底色）方向相反，因为这里旧卡是
     * 「被新卡推走」，早点淡干净才不挡新卡。
     */
    const val CardSwitchOutMs = 120

    /**
     * 答错抖动（[com.studykit.ui.components.QuizOptionTile]）的总时长，Int 毫秒。
     * 一个 tween 把 0→1 走完即停，配合线性衰减包络，总时长固定、不随帧数变化。
     */
    const val ShakeMs = 420

    /**
     * 错峰入场（[StaggeredIn]）的下标限幅：`minOf(index, StaggerIndexCap)`。
     *
     * 每级 [StaggerMs](40ms)，8 级封顶 = 320ms，与首屏入场序列同量级；
     * 不限幅时第 40 项要等 1.6s 才开始淡入，长列表首屏下方一片空白。
     * 调用方目前只有书架一处（其余列表页还没接错峰入场），但仍收在这里而不是退回调用方的
     * private 常量：限幅值与 [StaggerMs]/[StaggeredIn] 是同一份语义，分开写迟早对不上。
     */
    const val StaggerIndexCap = 8

    /**
     * 时长类补间的统一缓动（FastOutSlowIn）。收在这里是为了让页面不再各自
     * `import FastOutSlowInEasing`。
     *
     * **显式**挂这条曲线的站点：淡入淡出工厂 [fadeEnter]/[fadeExit]（因而 [FadeMs]、
     * [NavExitFadeMs]、[CardSwitchInMs]、[CardSwitchOutMs] 全部由它兜住）、
     * 数字滚动 [CountUpMs]（刷题正确率、小结计数）、[com.studykit.ui.components.ConfettiBurst] 的粒子。
     *
     * 仍**不传** easing、靠 `tween` 默认值的两类分支：月网格翻月的
     * `slideIn/OutHorizontally(tween(FadeMs))`，以及颜色交叉补间（筛选 chip、学科选项、
     * 两页日历的日格 `animateColorAsState`）。它们的默认曲线恰好也是 FastOutSlowIn，
     * 观感一致 —— 但那份一致来自框架默认值，不是本仓库的结构保证；本波按裁定只把
     * **淡入淡出**收进工厂，滑入与颜色补间要收成工厂得等动效走查之后。
     */
    val Easing = FastOutSlowInEasing

    /** 结算数字滚动（认识/不认识、正确率）的补间时长，Int 毫秒 */
    const val CountUpMs = 700

    val press = spring<Float>(dampingRatio = 0.55f, stiffness = 420f)
    val snap = spring<Float>(dampingRatio = 0.72f, stiffness = 380f)
    val flip = spring<Float>(dampingRatio = 0.68f, stiffness = 240f)
    val ring = spring<Float>(dampingRatio = 0.85f, stiffness = 160f)

    /** 错峰入场（[StaggeredIn]）的位移/淡入 spring：轻微过冲后落位 */
    val stagger = spring<Float>(dampingRatio = 0.78f, stiffness = 260f)

    /**
     * **切卡**（`CardStudyScreen` 的 `AnimatedContent` 横向滑入）的 spring。
     *
     * 来源：波 3 只把同一段转场里的两个淡入淡出时长上收为 [CardSwitchInMs] /
     * [CardSwitchOutMs]，这条 slide 的 spring 当时以「页内内联字面量」的形式留报，
     * 本波（波 4 项 4）按裁定原样搬进来 —— **dampingRatio / stiffness 一分未改**。
     *
     * 它与 [stagger] 近乎同值（0.78 / 260 vs 0.8 / 260）但**刻意不合并**：
     * 两者管的是两个动作（列表逐级进场 vs 单张卡片换页），等值取舍会直接改到翻卡手感，
     * 而那属于动效调参、要等设备走查。留两枚具名常量，正是为了让那次调参只改一处。
     */
    val cardSwitch = spring<Float>(dampingRatio = 0.8f, stiffness = 260f)

    fun flyOut() = spring<Float>(dampingRatio = 0.62f, stiffness = 550f)

    /**
     * 淡入工厂：**页面与内容转场**的淡入一律走这里，不再在页面里裸写
     * `fadeIn(tween(...))` —— 于是 [Easing] 那条曲线由结构保证，不再只是一句注释宣称。
     *
     * 默认 [FadeMs]；切卡这类更短促的转场显式传 [CardSwitchInMs]。
     * 与 [fadeExit] 成对使用（同一个 `AnimatedContent` / `NavHost` 的 in 与 out），
     * 命名沿 [navEnter]/[navExit] 那一套 in/out 对偶。
     */
    fun fadeEnter(durationMillis: Int = FadeMs): EnterTransition =
        fadeIn(animationSpec = tween(durationMillis = durationMillis, easing = Easing))

    /**
     * 淡出工厂：[fadeEnter] 的另一半，与它同一个规格入口。
     *
     * 默认 [FadeMs]；导航退场要显式传 [NavExitFadeMs]（晚于入场淡完，免得露出窗口底色），
     * 切卡退场传 [CardSwitchOutMs]（早于新卡淡到位）。
     */
    fun fadeExit(durationMillis: Int = FadeMs): ExitTransition =
        fadeOut(animationSpec = tween(durationMillis = durationMillis, easing = Easing))

    /**
     * push 时**入场**页：从右侧推入（`slideInHorizontally` 的 lambda 是**动画起点**的偏移量，
     * 正值 = 起始位置在右侧）+ 淡入。
     */
    fun navEnter(): EnterTransition =
        slideInHorizontally(spring(dampingRatio = 0.85f, stiffness = 240f)) { it / 3 } +
            fadeEnter()

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
            fadeExit(durationMillis = NavExitFadeMs)

    /** pop（返回）时**入场**页：从左侧回到位（起点偏左）+ 淡入 */
    fun navPopEnter(): EnterTransition =
        slideInHorizontally(spring(dampingRatio = 0.85f, stiffness = 240f)) { -it / 4 } +
            fadeEnter()

    /** pop（返回）时**出场**页：向右滑出（终点偏右，与 [navPopEnter] 同向）+ 淡出 */
    fun navPopExit(): ExitTransition =
        slideOutHorizontally(spring(dampingRatio = 0.9f, stiffness = 300f)) { it / 3 } +
            fadeExit(durationMillis = NavExitFadeMs)
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
 * 请传**视口局部**下标（如 `LazyColumn` 首屏可见区间的相对位置）并自行限幅
 * —— `minOf(index, StaggerIndexCap)`（常量见 [StaggerIndexCap]），每级 [StaggerMs](40ms)，
 * 不限幅时第 40 项要等 1.6s 才开始入场，首屏看起来像空白。
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
