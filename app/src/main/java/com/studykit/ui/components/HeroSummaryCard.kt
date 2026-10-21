package com.studykit.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.studykit.ui.theme.AppTheme

/**
 * 每屏顶部的**第一等重点块**：一个第一等数字 + 一句说明 + **这一屏唯一的实心主行动**。
 *
 * ## 它解决什么
 *
 * 四屏原来的顶部都是一行三块等重的统计磁贴 —— 每块一样大、一样色、一样没有动作，
 * 于是"今天该做什么"和"我一共有多少"在视觉上同权，整屏读起来像一列白板子。
 * 这一块把"今天要做的那个数"和"那件事的入口"绑在一起放在最上面，
 * 其余统计退成参考数字（[StatTileTier.Reference]）。
 *
 * ## v3.2：从"半透明绿玻璃"改成"被灯照到的一张卡"
 *
 * 旧版把这一块画成 accent 柔底的半透明玻璃，结果在夜间是一整片橄榄绿的蒙层：
 * 背景的绿、玻璃的绿、高光的绿叠在一起，文字反而退后（2026-10-20 使用者反馈「毛玻璃很丑」）。
 * v3.2 把它改成**实底渐变 + 一处径向光晕**：卡面从一点点品牌色过渡到 card，光晕只落在
 * 左侧读数环周围，像台灯照在桌面上的一张纸。玻璃从此只属于**真正浮在内容之上**的层
 * （底栏、弹层、临时状态卡），重点卡不再参与玻璃。
 *
 * 光晕强度随主题分档：夜间 0.18、浅色 0.10 —— 浅色纸上再强就会变成一块青色污渍。
 * 文字仍落在足够实的底上，`primaryText` / `secondaryText` 的 AA 数字全部不变。
 *
 * ## 为什么层级不依赖玻璃开关
 *
 * 这一块从 v3.2 起与玻璃设置无关：它就是一张被照亮的实底卡。
 * 用户关掉玻璃，只会影响底栏/弹层，这一屏的重点不会跟着消失。
 *
 * ## 与 AppCard 的关系
 *
 * `AppCard` 的 KDoc 写着"卡片一律实底"（那批 WCAG 数字都按实底卡面算的）。
 * 这一块同样是实底，只是多了一层**装饰性**光晕与品牌色渐变；每屏只有一块，别处不再复制。
 *
 * @param value 第一等数字，唯一一处用 [com.studykit.ui.theme.AppTexts.heroNumber] 的地方
 * @param actionLabel 主行动按钮文案；为 null 时不画按钮（有些屏的重点是"看"不是"做"）
 * @param leading 左侧插槽：进度环之类。容器按 88dp 见方给位，传 null 则整块被文字占满
 * @param supporting 数字下方的补充行（考试倒计时、连续天数徽章……），可空
 */
@Composable
fun HeroSummaryCard(
    label: String,
    value: String,
    caption: String,
    actionLabel: String?,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    supporting: (@Composable () -> Unit)? = null,
) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    val shape = RoundedCornerShape(AppTheme.radius.lg)

    // v3.2：实底渐变（顶部混入一点品牌色，向下回到 card）+ 左侧读数环后的一团径向光晕。
    // 夜间光晕比浅色强一档：浅色纸上再亮就会变成一块青色污渍。
    val dark = colors.background.luminance() < 0.5f
    val top = remember(colors.accent, colors.card, dark) {
        colors.accent.copy(alpha = if (dark) 0.10f else 0.07f).compositeOver(colors.card)
    }
    val glowAlpha = if (dark) 0.20f else 0.10f
    val borderBrush = remember(colors.divider) {
        Brush.verticalGradient(
            0f to colors.divider.copy(alpha = 0.95f),
            1f to colors.divider.copy(alpha = 0.28f),
        )
    }

    // 用 Box 而不是 Surface：`Surface(shadowElevation)` 会在卡片四周画一圈灰框，
    // 而这里的层次靠渐变 + 光晕 + 1dp 切割边，不靠阴影。
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.verticalGradient(0f to top, 1f to colors.card))
            .drawBehind {
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(colors.accent.copy(alpha = glowAlpha), Color.Transparent),
                        center = Offset(size.width * 0.22f, size.height * 0.46f),
                        radius = size.minDimension * 0.95f,
                    ),
                )
            }
            .border(width = 1.dp, brush = borderBrush, shape = shape),
    ) {
        Column(modifier = Modifier.padding(AppTheme.space.card)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (leading != null) {
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier.width(88.dp).height(88.dp),
                        contentAlignment = Alignment.Center,
                    ) { leading() }
                    Spacer(Modifier.width(AppTheme.space.lg))
                }
                Column(Modifier.weight(1f)) {
                    Text(text = label, style = texts.caption)
                    Spacer(Modifier.height(AppTheme.space.xs))
                    Text(text = value, style = texts.heroNumber)
                    Spacer(Modifier.height(AppTheme.space.xs))
                    Text(
                        text = caption,
                        style = texts.aux.copy(fontWeight = FontWeight.Medium),
                    )
                    if (supporting != null) {
                        Spacer(Modifier.height(AppTheme.space.sm))
                        supporting()
                    }
                }
            }
            if (actionLabel != null) {
                Spacer(Modifier.height(AppTheme.space.md))
                // 这一屏唯一的实心按钮。别的可点元素一律保持卡片/描边/文字形态 ——
                // 实心只给一个，"重点"这个词才有意义（AppButton 的实底档就是 accentInk + onAccent）。
                AppButton(
                    text = actionLabel,
                    onClick = onAction,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
