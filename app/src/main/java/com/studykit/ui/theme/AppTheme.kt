package com.studykit.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 双主题颜色令牌：唯一取色入口。
 *
 * 注意：[StudyKitTheme] 只**部分**覆写了 Material3 `ColorScheme`（仅 primary/background/surface/outline 等
 * 少数字段，secondary、container 系列仍走 M3 默认推导）。因此组件与页面的填充色/文本色必须**显式**取自
 * `AppTheme.colors.*`，不要依赖 `MaterialTheme.colorScheme.*`，否则夜间主题会拿到未覆写的默认色。
 *
 * `accentInk` = accent 的「墨水」变体，专用于纯文字/图标场景（描边按钮文案、空态图标等），
 * 因为浅色主题下 accent(#00A78E) 在白底只有 3.0:1 对比度，不满足正文文本 AA；大面积实底按钮仍用
 * accent 容器 + `card`（白）文字，无需替换。
 */
@Immutable
data class AppColors(
    val background: Color, val card: Color,
    val primaryText: Color, val secondaryText: Color,
    val accent: Color, val accentSoft: Color, val accentInk: Color,
    val success: Color, val successSoft: Color,
    val gold: Color, val goldSoft: Color,
    val warning: Color, val warningSoft: Color,
    val divider: Color,
)

val LightColors = AppColors(
    background = Palette.LightBackground, card = Palette.LightCard,
    primaryText = Palette.LightPrimaryText, secondaryText = Palette.LightSecondaryText,
    accent = Palette.LightAccent, accentSoft = Palette.LightAccent.copy(alpha = 0.10f),
    accentInk = Palette.LightAccentInk,
    success = Palette.LightSuccess, successSoft = Palette.LightSuccess.copy(alpha = 0.10f),
    gold = Palette.LightGold, goldSoft = Palette.LightGold.copy(alpha = 0.14f),
    warning = Palette.LightWarning, warningSoft = Palette.LightWarning.copy(alpha = 0.10f),
    divider = Palette.LightDivider,
)

val DarkColors = AppColors(
    background = Palette.DarkBackground, card = Palette.DarkCard,
    primaryText = Palette.DarkPrimaryText, secondaryText = Palette.DarkSecondaryText,
    accent = Palette.DarkAccent, accentSoft = Palette.DarkAccent.copy(alpha = 0.16f),
    accentInk = Palette.DarkAccentInk,
    success = Palette.DarkSuccess, successSoft = Palette.DarkSuccess.copy(alpha = 0.16f),
    gold = Palette.DarkGold, goldSoft = Palette.DarkGold.copy(alpha = 0.18f),
    warning = Palette.DarkWarning, warningSoft = Palette.DarkWarning.copy(alpha = 0.16f),
    divider = Palette.DarkDivider,
)

@Immutable
data class AppTexts(
    val largeTitle: TextStyle, val pageTitle: TextStyle, val cardTitle: TextStyle,
    val body: TextStyle, val aux: TextStyle, val caption: TextStyle,
    val statValue: TextStyle, val heroNumber: TextStyle,
)

fun buildAppTexts(c: AppColors): AppTexts = AppTexts(
    largeTitle = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Bold, color = c.primaryText),
    pageTitle = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = c.primaryText),
    cardTitle = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Medium, color = c.primaryText),
    body = TextStyle(fontSize = 17.sp, color = c.primaryText),
    aux = TextStyle(fontSize = 15.sp, color = c.primaryText),
    caption = TextStyle(fontSize = 13.sp, color = c.secondaryText),
    statValue = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp, color = c.primaryText),
    heroNumber = TextStyle(fontSize = 40.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp, color = c.primaryText),
)

@Immutable
data class AppThemeVals(val colors: AppColors, val texts: AppTexts)

val LocalAppTheme = staticCompositionLocalOf {
    AppThemeVals(LightColors, buildAppTexts(LightColors))
}

object AppTheme {
    private val current: AppThemeVals
        @Composable @ReadOnlyComposable get() = LocalAppTheme.current
    val colors: AppColors
        @Composable @ReadOnlyComposable get() = current.colors
    val texts: AppTexts
        @Composable @ReadOnlyComposable get() = current.texts
}
