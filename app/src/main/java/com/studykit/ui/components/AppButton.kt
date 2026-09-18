package com.studykit.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.studykit.ui.motion.rememberPressScale
import com.studykit.ui.theme.AppTheme
import com.studykit.ui.theme.DesignTokens

/**
 * 主/次操作按钮：52dp 胶囊形（`CornerRadiusXl` 为高度一半）+ 按压 spring 回弹
 * （[rememberPressScale] → `MotionSpec.press`）。
 *
 * 颜色显式取自 [AppTheme.colors]，不依赖 MaterialTheme 的部分覆写：实底按钮 = `accent` 容器 +
 * `card` 文字；描边按钮 = `accent` 描边 + `accentInk` 文案（accent 在白底仅 3.0:1，纯文本需更深的
 * ink 变体）。禁用态统一走 `divider` 底 + `secondaryText` 文案。
 */
@Composable
fun AppButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    secondary: Boolean = false,
    enabled: Boolean = true,
) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    val interaction = remember { MutableInteractionSource() }
    val scale by rememberPressScale(interaction)

    val labelColor = when {
        !enabled -> colors.secondaryText
        secondary -> colors.accentInk
        else -> colors.card
    }
    val fill = if (enabled) colors.accent else colors.divider
    val labelStyle = texts.body.copy(fontWeight = FontWeight.Medium, color = labelColor)
    val buttonModifier = modifier
        .fillMaxWidth()
        .height(52.dp)
        .graphicsLayer { scaleX = scale; scaleY = scale }

    if (secondary) {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            interactionSource = interaction,
            modifier = buttonModifier,
            shape = RoundedCornerShape(DesignTokens.CornerRadiusXl),
            border = BorderStroke(1.5.dp, fill),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = Color.Transparent,
                contentColor = labelColor,
            ),
        ) {
            Text(text = text, style = labelStyle)
        }
    } else {
        Button(
            onClick = onClick,
            enabled = enabled,
            interactionSource = interaction,
            modifier = buttonModifier,
            shape = RoundedCornerShape(DesignTokens.CornerRadiusXl),
            colors = ButtonDefaults.buttonColors(
                containerColor = fill,
                contentColor = labelColor,
            ),
        ) {
            Text(text = text, style = labelStyle)
        }
    }
}
