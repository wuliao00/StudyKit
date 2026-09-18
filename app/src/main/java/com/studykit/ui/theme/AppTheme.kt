package com.studykit.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

@Immutable
data class AppColors(
    val background: Color, val card: Color,
    val primaryText: Color, val secondaryText: Color,
    val accent: Color, val accentSoft: Color,
    val success: Color, val successSoft: Color,
    val gold: Color, val goldSoft: Color,
    val warning: Color, val warningSoft: Color,
    val divider: Color,
)

val LightColors = AppColors(
    background = Palette.LightBackground, card = Palette.LightCard,
    primaryText = Palette.LightPrimaryText, secondaryText = Palette.LightSecondaryText,
    accent = Palette.LightAccent, accentSoft = Palette.LightAccent.copy(alpha = 0.10f),
    success = Palette.LightSuccess, successSoft = Palette.LightSuccess.copy(alpha = 0.10f),
    gold = Palette.LightGold, goldSoft = Palette.LightGold.copy(alpha = 0.14f),
    warning = Palette.LightWarning, warningSoft = Palette.LightWarning.copy(alpha = 0.10f),
    divider = Palette.LightDivider,
)

val DarkColors = AppColors(
    background = Palette.DarkBackground, card = Palette.DarkCard,
    primaryText = Palette.DarkPrimaryText, secondaryText = Palette.DarkSecondaryText,
    accent = Palette.DarkAccent, accentSoft = Palette.DarkAccent.copy(alpha = 0.16f),
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
    largeTitle = TextStyle(34.sp, FontWeight.Bold, color = c.primaryText),
    pageTitle = TextStyle(22.sp, FontWeight.SemiBold, color = c.primaryText),
    cardTitle = TextStyle(17.sp, FontWeight.Medium, color = c.primaryText),
    body = TextStyle(17.sp, color = c.primaryText),
    aux = TextStyle(15.sp, color = c.primaryText),
    caption = TextStyle(13.sp, color = c.secondaryText),
    statValue = TextStyle(34.sp, FontWeight.Bold, letterSpacing = (-0.5).sp, color = c.primaryText),
    heroNumber = TextStyle(40.sp, FontWeight.Bold, letterSpacing = (-1).sp, color = c.primaryText),
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
