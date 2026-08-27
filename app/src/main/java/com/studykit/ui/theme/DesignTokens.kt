package com.studykit.ui.theme

import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** iOS 极简设计令牌 —— 所有 UI 组件必须引用此对象，禁止硬编码 */
object DesignTokens {

    // ── 颜色 ──────────────────────────────────────────────────────────────
    val Background      = Color(0xFFF8F8FA)
    val Card            = Color(0xFFFFFFFF)
    val PrimaryText     = Color(0xFF1C1C1E)
    val SecondaryText   = Color(0xFF8E8E93)
    val Accent          = Color(0xFF007AFF)
    val Success         = Color(0xFF34C759)
    val Gold            = Color(0xFFFFB300)   // 目标达成态专用色（进度环/标记）
    val Warning         = Color(0xFFFF3B30)
    val Divider         = Color(0xFFE5E5EA)   // rgba(60,60,67,0.12) on white

    // ── 字号层级（Roboto 默认）────────────────────────────────────────────
    val LargeTitle = TextStyle(
        fontSize   = 34.sp,
        fontWeight = FontWeight.Bold,
        color      = PrimaryText,
    )
    val PageTitle = TextStyle(
        fontSize   = 22.sp,
        fontWeight = FontWeight.SemiBold,
        color      = PrimaryText,
    )
    val CardTitle = TextStyle(
        fontSize   = 17.sp,
        fontWeight = FontWeight.Medium,
        color      = PrimaryText,
    )
    val Body = TextStyle(
        fontSize = 17.sp,
        color    = PrimaryText,
    )
    val Auxiliary = TextStyle(
        fontSize = 15.sp,
        color    = PrimaryText,
    )
    val Caption = TextStyle(
        fontSize = 13.sp,
        color    = SecondaryText,
    )

    // ── 间距（8dp 网格）────────────────────────────────────────────────────
    val SpacingXs: Dp = 4.dp
    val SpacingSm: Dp = 8.dp
    val SpacingMd: Dp = 16.dp
    val SpacingLg: Dp = 24.dp
    val SpacingXl: Dp = 32.dp

    val PageHorizontalPadding: Dp = 20.dp
    val CardPadding: Dp          = 16.dp

    // ── 圆角 ──────────────────────────────────────────────────────────────
    val CornerRadius: Dp     = 14.dp
    val CornerRadiusLg: Dp   = 16.dp

    // ── 阴影 ──────────────────────────────────────────────────────────────
    val ShadowElevation: Dp  = 2.dp

    // ── 动效 ──────────────────────────────────────────────────────────────
    val AnimDurationMs: Int   = 250
    val AnimEasing: Easing    = FastOutSlowInEasing
}
