package com.studykit.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val AppLightColorScheme = lightColorScheme(
    primary         = DesignTokens.Accent,
    onPrimary       = Color.White,
    background      = DesignTokens.Background,
    onBackground    = DesignTokens.PrimaryText,
    surface         = DesignTokens.Card,
    onSurface       = DesignTokens.PrimaryText,
    surfaceVariant  = DesignTokens.Background,
    outline         = DesignTokens.Divider,
)

private val AppTypography = Typography(
    headlineLarge = DesignTokens.LargeTitle,
    headlineMedium = DesignTokens.PageTitle,
    titleLarge = DesignTokens.CardTitle,
    bodyLarge = DesignTokens.Body,
    bodyMedium = DesignTokens.Auxiliary,
    labelMedium = DesignTokens.Caption,
)

private val AppShapes = Shapes(
    medium = androidx.compose.foundation.shape.RoundedCornerShape(DesignTokens.CornerRadius),
    large  = androidx.compose.foundation.shape.RoundedCornerShape(DesignTokens.CornerRadiusLg),
)

@Composable
fun StudyKitTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AppLightColorScheme,
        typography  = AppTypography,
        shapes      = AppShapes,
        content     = content,
    )
}
