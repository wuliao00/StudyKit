package com.studykit.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.studykit.ui.theme.AppTheme

/**
 * 通用卡片：20dp 圆角 + 1dp 柔光描边 + 1dp 低阴影（「描边代替阴影」的暖纸感观感）。
 * 卡面取 `colors.card`，**随主题变化**（浅色 `#FFFFFF`／夜间 `#26241F`），不是固定白色。
 * 颜色显式来自 [AppTheme.colors]，不依赖 MaterialTheme 的局部覆写。
 */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val colors = AppTheme.colors
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(AppTheme.radius.lg),
        color = colors.card,
        contentColor = colors.primaryText,
        border = BorderStroke(1.dp, colors.divider.copy(alpha = 0.6f)),
        shadowElevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(AppTheme.space.card)) {
            content()
        }
    }
}
