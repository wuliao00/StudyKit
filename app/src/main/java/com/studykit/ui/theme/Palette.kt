package com.studykit.ui.theme

import androidx.compose.ui.graphics.Color

/** 暖纸感色板：数值来自 spec §1，禁止在页面直接使用，统一经 AppTheme.colors */
object Palette {
    // 浅色
    val LightBackground = Color(0xFFFAF8F2)
    val LightCard = Color(0xFFFFFFFF)
    val LightPrimaryText = Color(0xFF1F1D1A)
    val LightSecondaryText = Color(0xFF8A857C)
    val LightAccent = Color(0xFF00A78E)
    val LightAccentInk = Color(0xFF00735F)   // 文本/图标专用深accent：白底对比度 5.8:1（accent 仅 3.0:1 不达标）
    val LightSuccess = Color(0xFF34C759)
    val LightGold = Color(0xFFFFB300)
    val LightWarning = Color(0xFFFF5A52)
    val LightDivider = Color(0xFFE7E2D8)
    // 深色（暖黑，非纯黑）
    val DarkBackground = Color(0xFF1C1B18)
    val DarkCard = Color(0xFF26241F)
    val DarkPrimaryText = Color(0xFFF0EDE6)
    val DarkSecondaryText = Color(0xFF9A958B)
    val DarkAccent = Color(0xFF33C7AB)
    val DarkAccentInk = Color(0xFF65D7C2)    // DarkAccent 同色相提亮版：暖黑底上文本更清晰
    val DarkSuccess = Color(0xFF4CD07D)
    val DarkGold = Color(0xFFFFC94D)
    val DarkWarning = Color(0xFFFF7A73)
    val DarkDivider = Color(0xFF3A372F)
}
