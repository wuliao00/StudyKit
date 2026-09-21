package com.studykit.ui.material

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.drawOutline
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Dp
import com.studykit.data.GlassLevel
import com.studykit.ui.theme.AppTheme

/**
 * 一帧玻璃材质需要的全部颜色与尺寸，由 [rememberGlassStyle] 从主题令牌算出来。
 *
 * [enabled] 为假时（用户在设置页把玻璃关掉）[glassSurface] 直接原样返回 modifier，
 * 由调用方的 `glassContainerColor()` 退回实底 `colors.card` —— 两者必须成对使用，
 * 否则会出现"材质关了但容器透明"的空洞。
 */
@Immutable
data class GlassStyle(
    val enabled: Boolean,
    val tint: Color,
    val tintAlpha: Float,
    val spec: Color,
    val glossTopAlpha: Float,
    val edgeLight: Color,
    val edgeDark: Color,
    val edgeWidth: Dp,
    val bandWidthFraction: Float,
    val bandAlpha: Float,
    /** 减弱动效：亮带钉在固定位置，不再随滚动/按压跑 */
    val bandStatic: Boolean,
)

/**
 * 手绘玻璃材质。五层，全部走 `DrawScope`，**不用系统模糊**。
 *
 * ## 为什么是手绘
 *
 * 「液态玻璃」的本体是背景模糊，而系统级 `RenderEffect` 要 Android 12（API 31）才有，
 * Compose 的 `Modifier.blur` 在 Android 11 及以下**是空操作**。本仓的验收机是 vivo V2156A
 * （Android 11），走系统模糊等于：唯一能判观感的设备上永远看不到这个功能，写了也无法验收。
 * 所以这里用「分层 + 高光 + 边缘切割」把玻璃画出来，Android 8 到 15 表现一致。
 *
 * ## 五层
 *
 * 1. 半透明底 tint；2. 顶亮底暗的入射光渐变；3. 随内容滚动沿边缘跑的一条细亮带（"液态"的来源）；
 * 4. 上亮下暗的内描边（玻璃的切割感靠它，不靠阴影）；5. 抬升阴影由调用方的 `Modifier.shadow` 负责，
 *    材质本身不加，免得和 `Surface` 的阴影叠成两份。
 *
 * ## 帧卫生（本版硬约束）
 *
 * 1、2、4 层与亮带的**刷子**全在 `drawWithCache` 块里建一次，只在尺寸/形状/主题变化时重建；
 * 每帧变的只有 `translate(left = …)` 的位移与两个 alpha —— 位移靠变换画布实现，
 * 亮带用 `TileMode.Decal` 的线性渐变沿轮廓描边绘制，因此**不需要裁剪路径**，也不逐帧新建 `Brush`。
 *
 * @param pressed 0f..1f 按压进度，把亮带提亮（手指压在玻璃上，光先聚到那儿）
 * @param scrollPhase 0f..1f 归一化滚动量，驱动亮带沿边缘游走
 *
 * 两个动画量都是 **`() -> Float`** 而不是 `Float`：调用方把 `State` 的读取推迟到绘制期
 * （`pressed = { pressScale }`、`scrollPhase = { scrollState.value / 600f }`），
 * 于是值变化只触发重绘；若传 `Float`，每帧都会让调用者重组、`drawWithCache` 的缓存也就白建了。
 * 每个 lambda 都只在 `onDrawBehind` 里求值，绝不在建缓存的那一段读。
 */
fun Modifier.glassSurface(
    style: GlassStyle,
    shape: Shape,
    pressed: () -> Float = { 0f },
    scrollPhase: () -> Float = { 0f },
): Modifier {
    if (!style.enabled) return this
    return drawWithCache {
        val outline = shape.createOutline(size, layoutDirection, this)
        val glossBrush = Brush.verticalGradient(
            0f to style.spec.copy(alpha = style.glossTopAlpha),
            0.5f to Color.Transparent,
            1f to Color.Transparent,
        )
        // 边缘描边：上亮下暗。渐变按 y 方向铺，画在轮廓上就是"顶边亮、底边暗"
        val edgeBrush = Brush.verticalGradient(
            0f to style.edgeLight,
            1f to style.edgeDark,
        )
        val bandLength = (size.width * style.bandWidthFraction).coerceAtLeast(1f)
        val bandBrush = Brush.linearGradient(
            colors = listOf(Color.Transparent, style.spec, Color.Transparent),
            start = Offset.Zero,
            end = Offset(bandLength, 0f),
            tileMode = TileMode.Decal,
        )
        val edgePx = style.edgeWidth.toPx()
        val travelRange = size.width + bandLength * 2f

        onDrawBehind {
            // 1. 底
            drawOutline(outline = outline, color = style.tint, alpha = style.tintAlpha)
            // 2. 入射光
            drawOutline(outline = outline, brush = glossBrush, alpha = 1f)
            // 3. 流动亮带：先按滚动量把画布平移，再沿轮廓描一条比描边粗一点的高光
            val phase = if (style.bandStatic) 0.22f else scrollPhase().coerceIn(0f, 1f)
            val bandAlpha = (style.bandAlpha + pressed() * 0.18f).coerceAtMost(1f)
            translate(left = -bandLength + phase * travelRange) {
                drawOutline(
                    outline = outline,
                    brush = bandBrush,
                    alpha = bandAlpha,
                    strokeWidth = edgePx * 2.4f,
                )
            }
            // 4. 内描边
            drawOutline(
                outline = outline,
                brush = edgeBrush,
                alpha = 1f,
                strokeWidth = edgePx,
            )
        }
    }
}

/**
 * 玻璃是否生效 + 该用哪个容器色。跟 [glassSurface] 成对使用：
 * 关掉时返回 `colors.card`，让 `NavigationBar` / `Surface` 退回实底。
 */
@Composable
fun glassContainerColor(): Color =
    if (AppTheme.settings.glass == GlassLevel.OFF) AppTheme.colors.card else Color.Transparent

/** 从主题令牌与设置算出这一帧的玻璃样式。尺寸是常量，颜色随深浅主题各一套。 */
@Composable
fun rememberGlassStyle(): GlassStyle {
    val colors = AppTheme.colors
    val settings = AppTheme.settings
    return remember(colors, settings.glass, settings.reduceMotion) {
        val level = settings.glass
        GlassStyle(
            enabled = level != GlassLevel.OFF,
            tint = colors.glassTint,
            tintAlpha = if (level == GlassLevel.STRONG) AppTheme.glass.tintAlphaStrong else AppTheme.glass.tintAlphaSoft,
            spec = colors.glassSpec,
            glossTopAlpha = AppTheme.glass.glossTopAlpha,
            edgeLight = colors.glassSpec.copy(alpha = AppTheme.glass.edgeAlphaLight),
            edgeDark = colors.divider.copy(alpha = AppTheme.glass.edgeAlphaDark),
            edgeWidth = AppTheme.glass.edgeWidth,
            bandWidthFraction = AppTheme.glass.bandWidthFraction,
            bandAlpha = AppTheme.glass.bandAlpha,
            bandStatic = settings.reduceMotion,
        )
    }
}
