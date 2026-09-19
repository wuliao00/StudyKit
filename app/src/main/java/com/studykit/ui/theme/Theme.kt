package com.studykit.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// onPrimary / onError 取 `c.onAccent`（「压实底上的字」那一档），**不取 c.card**：
// AppColors 的 KDoc 明令 card 随主题变化（light #FFFFFF / dark #26241F），不能再被当作「白墨」
// 给文字着色。浅色下两者同值（LightOnAccent 就是卡面白），改的只有夜间：
// 由 card #26241F 换成暖墨 #1C1B18 —— 与 AppButton 实底按钮的文案同一档。
private fun scheme(dark: Boolean, c: AppColors) = if (dark) darkColorScheme(
    primary = c.accent, onPrimary = c.onAccent,
    background = c.background, onBackground = c.primaryText,
    surface = c.card, onSurface = c.primaryText,
    surfaceVariant = c.background, outline = c.divider,
    error = c.warning, onError = c.onAccent,
) else lightColorScheme(
    primary = c.accent, onPrimary = c.onAccent,
    background = c.background, onBackground = c.primaryText,
    surface = c.card, onSurface = c.primaryText,
    surfaceVariant = c.background, outline = c.divider,
    error = c.warning, onError = c.onAccent,
)

@Composable
fun StudyKitTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors
    val vals = remember(colors) { AppThemeVals(colors, buildAppTexts(colors)) }
    val view = LocalView.current
    SideEffect {
        (view.context as? Activity)?.window?.let {
            WindowCompat.getInsetsController(it, view).isAppearanceLightStatusBars = !darkTheme
        }
    }
    CompositionLocalProvider(LocalAppTheme provides vals) {
        MaterialTheme(
            colorScheme = scheme(darkTheme, colors),
            shapes = Shapes(
                medium = RoundedCornerShape(AppTheme.radius.md),
                large = RoundedCornerShape(AppTheme.radius.lg),
            ),
            content = content,
        )
    }
}
