package com.studykit.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.studykit.ui.theme.AppTheme
import com.studykit.ui.theme.DesignTokens

/**
 * 统计磁贴：34sp 大数字（[AppTheme.texts.statValue]）+ 小号说明标签，用于页面顶部概览。
 * 与 [AppCard] 同款描边观感，零阴影。
 */
@Composable
fun StatTile(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(DesignTokens.CornerRadiusLg),
        color = colors.card,
        contentColor = colors.primaryText,
        border = BorderStroke(1.dp, colors.divider.copy(alpha = 0.6f)),
        shadowElevation = 0.dp,
    ) {
        Column(modifier = Modifier.padding(DesignTokens.CardPadding)) {
            Text(text = value, style = texts.statValue)
            Spacer(Modifier.height(DesignTokens.SpacingXs))
            Text(text = label, style = texts.caption)
        }
    }
}
