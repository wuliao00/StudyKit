package com.studykit.ui.material

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import com.studykit.data.GlassLevel
import com.studykit.ui.theme.AppTheme

/**
 * 一帧玻璃材质需要的颜色、刷子与尺寸，由 [rememberGlassStyle] 从主题令牌算出来。
 *
 * v3.2 起玻璃只有三层：中性底、顶部白高光、一圈细切割边。
 * 原来的「流动亮带」连同它的 `pressed` / `scrollPhase` 两个输入一起删掉了 ——
 * 那道绿光是夜间毛玻璃看起来最廉价的地方（用户 2026-10-20 的原话是「很丑」），
 * 而且它逼着每个调用方养一枚 `Animatable`，仅为了一次性扫光。
 *
 * [enabled] 为假时（用户在设置页把玻璃关掉）[glassSurface] 原样返回 modifier，
 * 由调用方的 [glassContainerColor] 退回实底 `colors.card`。两者必须成对使用，
 * 否则会出现「材质关了但容器也透明」的空洞。
 */
@Immutable
data class GlassStyle(
    val enabled: Boolean,
    /** 弹层（ModalBottomSheet）的 containerColor 用这一枚；与 [tintBrush] 同源 */
    val tint: Color,
    /** 玻璃面板的实际底：由上到下极轻的明暗变化，避免整块像一张单色塑料膜 */
    val tintBrush: Brush,
    val glossBrush: Brush,
    val edgeBrush: Brush,
    val edgeWidth: Dp,
)

/**
 * 手绘玻璃材质。**不用系统模糊**，原因不变：`RenderEffect` 要 Android 12+
 * （本仓的验收机之一是 Android 11），Compose 的 `Modifier.blur` 在 11 及以下是空操作 ——
 * 在唯一能判观感的设备上永远看不到的功能，写了也无法验收。所以这里用
 * 「半透明中性底 + 一道顶部白高光 + 一圈细边缘」把玻璃画出来，Android 8 到 15 表现一致。
 *
 * 抬升阴影**不在这里**，由调用方的 `Modifier.shadow` / `Surface(shadowElevation=…)` 负责，
 * 免得与 `Surface` 自带的阴影叠成两份。
 *
 * @param paintTint 要不要画半透明底。默认要；**只有当底色已由别处负责时**才传 false
 *   —— 目前唯一的使用者是打卡弹层：它的整块面板底色由 `ModalBottomSheet.containerColor` 画
 *   （连拖动手柄那一条一起，不留接缝），这里就只补高光与描边。
 */
fun Modifier.glassSurface(
    style: GlassStyle,
    shape: Shape,
    paintTint: Boolean = true,
): Modifier {
    if (!style.enabled) return this
    val tinted = if (paintTint) this.background(brush = style.tintBrush, shape = shape) else this
    return tinted
        .background(brush = style.glossBrush, shape = shape)
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
    return remember(colors, settings.glass) {
        val level = settings.glass
        val tintAlpha = if (level == GlassLevel.STRONG) {
            AppTheme.glass.tintAlphaStrong
        } else {
            AppTheme.glass.tintAlphaSoft
        }
        val base = colors.glassTint
        // 边缘随主题换角色：夜间是"白切割"（光从上方切过深色玻璃），
        // 浅色是"灰切割"（白线压在暖白上等于没有）。两者都只亮上缘、下缘几乎消失。
        val dark = colors.background.luminance() < 0.5f
        val edgeTop = if (dark) colors.glassSpec.copy(alpha = AppTheme.glass.edgeAlphaLight) else colors.divider.copy(alpha = 0.90f)
        val edgeBottom = if (dark) colors.divider.copy(alpha = AppTheme.glass.edgeAlphaDark) else colors.divider.copy(alpha = 0.20f)
        GlassStyle(
            enabled = level != GlassLevel.OFF,
            tint = base.copy(alpha = tintAlpha),
            tintBrush = Brush.verticalGradient(
                0f to base.copy(alpha = (tintAlpha + 0.04f).coerceAtMost(1f)),
                1f to base.copy(alpha = (tintAlpha - 0.04f).coerceAtLeast(0f)),
            ),
            glossBrush = Brush.verticalGradient(
                0f to colors.glassSpec.copy(alpha = AppTheme.glass.glossTopAlpha),
                0.45f to Color.Transparent,
            ),
            edgeBrush = Brush.verticalGradient(0f to edgeTop, 1f to edgeBottom),
            edgeWidth = AppTheme.glass.edgeWidth,
        )
    }
}
