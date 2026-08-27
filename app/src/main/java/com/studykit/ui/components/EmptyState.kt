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
import com.studykit.ui.theme.DesignTokens

/**
 * 空态占位：圆形图标容器 + 标题 + 说明文字。
 */
@Composable
fun EmptyState(
    title: String,
    modifier: Modifier = Modifier,
    caption: String? = null,
    icon: ImageVector = Icons.Outlined.DateRange,
) {
    Column(
        modifier             = modifier.fillMaxWidth(),
        horizontalAlignment  = Alignment.CenterHorizontally,
        verticalArrangement  = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(DesignTokens.Accent.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector     = icon,
                contentDescription = null,
                tint            = DesignTokens.Accent,
                modifier        = Modifier.size(28.dp),
            )
        }
        Spacer(Modifier.height(DesignTokens.SpacingMd))
        Text(text = title, style = DesignTokens.CardTitle, textAlign = TextAlign.Center)
        if (caption != null) {
            Spacer(Modifier.height(DesignTokens.SpacingXs))
            Text(
                text      = caption,
                style     = DesignTokens.Caption,
                textAlign = TextAlign.Center,
                modifier  = Modifier.padding(horizontal = DesignTokens.SpacingXl),
            )
        }
    }
}
