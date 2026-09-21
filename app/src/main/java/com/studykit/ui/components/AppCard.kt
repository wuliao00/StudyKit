package com.studykit.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import com.studykit.ui.theme.AppTheme

/**
 * 通用卡片：20dp 圆角 + 1dp「微光」描边 + 一丝抬升（`AppTheme.elevation.hairline`）。
 *
 * ## v2.2 的「暖纸 + 微光」
 *
 * 描边不再是均匀一圈 `divider`，而是**上缘实、下缘淡出**的竖向渐变：光从上方来，
 * 于是纸面的上沿有一道切割、下沿自然化进页面底。分层靠这道光，不靠阴影，
 * 所以 `shadowElevation` 仍只取 hairline；若设备走查判定它太贴地，改取 `elevation.low`
 * 只是一个令牌的宽度。
 *
 * ## 为什么卡片**不做**透明
 *
 * 卡面取 `colors.card`，**随主题变化**（浅色 `#FFFFFF`／夜间 `#26241F`），且保持不透明：
 * M1 定下的那一整套 WCAG AA 实测数字（`accentInk` 5.81:1、`successInk` 5.39:1、
 * `goldInk` 5.93:1 ……）全是按实底卡面算的。卡片一透明，卡上 13sp 文字背后的内容就参与对比度计算，
 * 那批数字全体失效。所以本版只有**浮层**用玻璃（`ui/material/Glass.kt`），卡片一律实底。
 *
 * 颜色显式来自 [AppTheme.colors]，不依赖 MaterialTheme 的局部覆写。
 */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val colors = AppTheme.colors
    val border = remember(colors.divider) {
        BorderStroke(
            width = 1.dp,
            brush = Brush.verticalGradient(
                0f to colors.divider.copy(alpha = 0.80f),
                1f to colors.divider.copy(alpha = 0.25f),
            ),
        )
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(AppTheme.radius.lg),
        color = colors.card,
        contentColor = colors.primaryText,
        border = border,
        shadowElevation = AppTheme.elevation.hairline,
    ) {
        Column(modifier = Modifier.padding(AppTheme.space.card)) {
            content()
        }
    }
}
