package com.studykit.ui.material

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import com.studykit.data.GlassLevel
import com.studykit.ui.theme.AppTheme

/**
 * 一帧玻璃材质需要的颜色、刷子与尺寸，由 [rememberGlassStyle] 从主题令牌算出来。
 *
 * 两枚渐变刷子在这里建好、随样式一起缓存 —— 材质每帧只做「平移 + 改 alpha」两件事，
 * 不在绘制路径上分配任何对象（本版硬约束）。
 *
 * [enabled] 为假时（用户在设置页把玻璃关掉）[glassSurface] 原样返回 modifier，
 * 由调用方的 [glassContainerColor] 退回实底 `colors.card`。两者必须成对使用，
 * 否则会出现「材质关了但容器也透明」的空洞。
 */
@Immutable
data class GlassStyle(
    val enabled: Boolean,
    val tint: Color,
    val glossBrush: Brush,
    val edgeBrush: Brush,
    val bandColor: Color,
    val edgeWidth: Dp,
    val bandWidthFraction: Float,
    val bandAlpha: Float,
    /** 减弱动效：亮带钉在固定位置，不再随滚动/按压跑 */
    val bandStatic: Boolean,
)

/**
 * 手绘玻璃材质。四层，全部走标准绘制 API，**不用系统模糊**。
 *
 * ## 为什么是手绘
 *
 * 「液态玻璃」的本体是背景模糊，而系统级 `RenderEffect` 要 Android 12（API 31）才有，
 * Compose 的 `Modifier.blur` 在 Android 11 及以下**是空操作**。本仓的验收机是 vivo V2156A
 * （Android 11 / SDK 30），走系统模糊等于：唯一能判观感的设备上永远看不到这个功能，写了也无法验收。
 * 所以这里用「半透明底 + 入射光 + 流动亮带 + 边缘切割」把玻璃画出来，Android 8 到 15 表现一致。
 *
 * ## 四层与它们的顺序
 *
 * 1. `background(tint)`：半透明底，压掉背景但不抹掉；
 * 2. `background(glossBrush)`：顶亮底透明的一道入射光；
 * 3. `clip(shape)` + `drawWithCache`：一条斜亮的带子**平移**着扫过玻璃 ——
 *    这就是"液态"的来源。亮带用 `TileMode.Decal` 的线性渐变（只在 0..bandLength 这段有颜色），
 *    每帧改的只有 `translate(left = …)` 与 alpha，被上一步的 `clip` 裁在圆角里；
 * 4. `border(edgeBrush)`：上亮下暗的 1dp 内描边。玻璃的"切割感"靠它，不靠阴影。
 *
 * 抬升阴影**不在这里**，由调用方的 `Modifier.shadow` / `Surface(shadowElevation=…)` 负责，
 * 免得与 `Surface` 自带的阴影叠成两份。
 *
 * ## 为什么不用 `DrawScope.drawOutline`
 *
 * 第一版是照「沿轮廓描一条高光」写的，CI 直接报 `Unresolved reference 'drawOutline'` ——
 * 本仓的 Compose（BOM 2024.12.01 / UI 1.7.6）里它不是可用的公开 API。改成上面这套
 * 全是稳定 API 的写法之后，行为等价（亮带仍然贴着玻璃走），且少一层形状→路径的转换。
 *
 * @param pressed 0f..1f 按压进度，把亮带提亮（手指压在玻璃上，光先聚到那儿）
 * @param scrollPhase 0f..1f 归一化滚动量，驱动亮带扫过玻璃
 * @param paintTint 要不要画第 1 层半透明底。默认要；**只有当底色已由别处负责时**才传 false
 *   —— 目前唯一的使用者是打卡弹层：它的整块面板底色由 `ModalBottomSheet.containerColor` 画
 *   （连拖动手柄那一条一起，不留接缝），这里就只补入射光、亮带与描边。
 *
 * 两个动画量都是 **`() -> Float`** 而不是 `Float`：调用方把 `State` 的读取推迟到绘制期
 * （`pressed = { pressScale }`、`scrollPhase = { sweep.value }`），于是值变化只触发重绘。
 * 传 `Float` 会让调用者每帧重组，`drawWithCache` 的缓存也就白建了。
 */
fun Modifier.glassSurface(
    style: GlassStyle,
    shape: Shape,
    paintTint: Boolean = true,
    pressed: () -> Float = { 0f },
    scrollPhase: () -> Float = { 0f },
): Modifier {
    if (!style.enabled) return this
    val tinted = if (paintTint) this.background(color = style.tint, shape = shape) else this
    return tinted
        .background(brush = style.glossBrush, shape = shape)
        .clip(shape)
        .drawWithCache {
            // 亮带宽度与行程按当前尺寸算一次；尺寸不变就不重算
            val bandLength = (size.width * style.bandWidthFraction).coerceAtLeast(1f)
            val bandBrush = Brush.linearGradient(
                colors = listOf(Color.Transparent, style.bandColor, Color.Transparent),
                start = Offset.Zero,
                end = Offset(bandLength, 0f),
                tileMode = TileMode.Decal,
            )
            val travelRange = size.width + bandLength * 2f
            onDrawBehind {
                val phase = if (style.bandStatic) 0.22f else scrollPhase().coerceIn(0f, 1f)
                val alpha = (style.bandAlpha + pressed() * 0.18f).coerceAtMost(1f)
                translate(left = -bandLength + phase * travelRange) {
                    drawRect(brush = bandBrush, alpha = alpha)
                }
            }
        }
        .border(width = style.edgeWidth, brush = style.edgeBrush, shape = shape)
}

/**
 * 玻璃开着时容器要让位给材质（返回透明），关掉时退回 `colors.card`。
 * 与 [glassSurface] 成对使用。
 */
@Composable
fun glassContainerColor(): Color =
    if (AppTheme.settings.glass == GlassLevel.OFF) AppTheme.colors.card else Color.Transparent

/** 从主题令牌与用户设置算出这一帧的玻璃样式；尺寸是常量，颜色与刷子随主题各一套。 */
@Composable
fun rememberGlassStyle(): GlassStyle {
    val colors = AppTheme.colors
    val settings = AppTheme.settings
    return remember(colors, settings.glass, settings.reduceMotion) {
        val level = settings.glass
        val tintAlpha = if (level == GlassLevel.STRONG) {
            AppTheme.glass.tintAlphaStrong
        } else {
            AppTheme.glass.tintAlphaSoft
        }
        GlassStyle(
            enabled = level != GlassLevel.OFF,
            tint = colors.glassTint.copy(alpha = tintAlpha),
            glossBrush = Brush.verticalGradient(
                0f to colors.glassSpec.copy(alpha = AppTheme.glass.glossTopAlpha),
                0.55f to Color.Transparent,
            ),
            edgeBrush = Brush.verticalGradient(
                0f to colors.glassSpec.copy(alpha = AppTheme.glass.edgeAlphaLight),
                1f to colors.divider.copy(alpha = AppTheme.glass.edgeAlphaDark),
            ),
            bandColor = colors.glassSpec,
            edgeWidth = AppTheme.glass.edgeWidth,
            bandWidthFraction = AppTheme.glass.bandWidthFraction,
            bandAlpha = AppTheme.glass.bandAlpha,
            bandStatic = settings.reduceMotion,
        )
    }
}
