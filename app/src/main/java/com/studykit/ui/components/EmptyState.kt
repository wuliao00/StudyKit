package com.studykit.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.studykit.ui.theme.AppTheme
import com.studykit.ui.theme.DesignTokens

/**
 * 空态占位：72dp 圆形 accentSoft 图标容器 + 标题 + 说明文字。
 * 图标为纯图形元素，取 `accentInk` 以保证在浅底上仍达对比度。
 */
@Composable
fun EmptyState(
    title: String,
    modifier: Modifier = Modifier,
    caption: String? = null,
    icon: ImageVector = Icons.Outlined.DateRange,
) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(colors.accentSoft),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = colors.accentInk,
                modifier = Modifier.size(32.dp),
            )
        }
        Spacer(Modifier.height(DesignTokens.SpacingMd))
        Text(text = title, style = texts.cardTitle, textAlign = TextAlign.Center)
        if (caption != null) {
            Spacer(Modifier.height(DesignTokens.SpacingXs))
            Text(
                text = caption,
                style = texts.caption,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = DesignTokens.SpacingXl),
            )
        }
    }
}
