package com.studykit.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.PaddingValues
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

/**
 * 主/次操作按钮：52dp 胶囊形（`AppTheme.radius.xl` 为高度一半）+ 按压 spring 回弹
 * （[rememberPressScale] → `MotionSpec.press`）。
 *
 * 颜色显式取自 [AppTheme.colors]，不依赖 MaterialTheme 的部分覆写：
 * - 实底按钮 = `accentInk` 容器 + `onAccent` 文案。浅色主题是 `#00735F` 上的白字（5.81:1），
 *   夜间主题是 `#65D7C2` 上的暖墨字（9.88:1）；白字若留在 `#65D7C2` 上只有 1.74:1，故两主题各自达标。
 * - 描边按钮 = `accent` 描边 + `accentInk` 文案（accent 在白底仅 3.04:1，纯文本需更深的 ink 变体）。
 * - 禁用态：文案统一 `secondaryText`；实底容器用 `accent` 28% 淡底（`divider` 与页面底色两主题各
 *   只有 1.22:1 / 1.45:1，轮廓几乎消失）；描边分支容器**始终透明**，只把描边淡化为同一 28% 底——
 *   所以禁用态并非「统一走 divider 底」，两个分支的底色语义不同。
 *
 * 两分支都显式给出 `contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp)`：
 * 52dp 固定高度下不留足版心时，`fontScale ≥ 1.15` 会裁切文字。
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

    // 禁用态淡 accent 底/描边：实底分支靠它保住轮廓，描边分支靠它保住描边可见度
    val disabledTint = colors.accent.copy(alpha = 0.28f)
    val labelColor = when {
        !enabled -> colors.secondaryText
        secondary -> colors.accentInk
        else -> colors.onAccent
    }
    val containerColor = if (enabled) colors.accentInk else disabledTint
    val borderColor = if (enabled) colors.accent else disabledTint
    val labelStyle = texts.body.copy(fontWeight = FontWeight.Medium, color = labelColor)
    val contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp)
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
            shape = RoundedCornerShape(AppTheme.radius.xl),
            border = BorderStroke(1.5.dp, borderColor),
            contentPadding = contentPadding,
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
            shape = RoundedCornerShape(AppTheme.radius.xl),
            contentPadding = contentPadding,
            // disabled* 槽位一并显式给值：M3 ButtonColors 在禁用时会改读 disabledContainerColor
            // （默认 onSurface 12% 灰），不覆写就拿不到我们的淡 accent 底。
            colors = ButtonDefaults.buttonColors(
                containerColor = containerColor,
                contentColor = labelColor,
                disabledContainerColor = disabledTint,
                disabledContentColor = colors.secondaryText,
            ),
        ) {
            Text(text = text, style = labelStyle)
        }
    }
}
