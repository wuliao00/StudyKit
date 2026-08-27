package com.studykit.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.studykit.ui.theme.DesignTokens

/**
 * 统计磁贴：大号数值 + 小号说明标签，用于页面顶部概览。
 */
@Composable
fun StatTile(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier        = modifier,
        shape           = RoundedCornerShape(DesignTokens.CornerRadius),
        color           = DesignTokens.Card,
        shadowElevation = DesignTokens.ShadowElevation,
    ) {
        Column(modifier = Modifier.padding(DesignTokens.CardPadding)) {
            Text(text = value, style = DesignTokens.PageTitle)
            Spacer(Modifier.height(DesignTokens.SpacingXs))
            Text(text = label, style = DesignTokens.Caption)
        }
    }
}
