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
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.studykit.data.GlassLevel
import com.studykit.ui.material.glassSurface
import com.studykit.ui.material.rememberGlassStyle
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
 * ## 为什么本体从 accent 取色，而不是复用底栏那套玻璃
 *
 * 玻璃的本体色（`glassTint` = `#FBF9F3`，alpha 0.72）是为**压在滚动内容上**的浮层调的；
 * 而页面底色是 `#FAF8F2` —— 两者叠出来的结果与页面底色几乎同值，**在纸色页面上它等于不存在**，
 * 只会留下一道边缘光。所以这一块的本体色改成从品牌色取：`accentSoft` 压在卡面上得到一片很淡的
 * 暖青底，再按玻璃的 alpha 铺出去。这样它在浅色纸面上**看得见**，同时底够浅、文字仍是
 * `primaryText`（深暖墨）压在近白底上，AA 不受影响，也不需要新增任何墨水令牌。
 *
 * ## 为什么层级不依赖玻璃开关
 *
 * 玻璃关掉时（`GlassLevel.OFF`）这一块退回**同一片 accent 底**的实底卡，只是少了光泽与亮带 ——
 * 也就是说"它是这一屏的重点"这件事由**配色**保证，材质只负责让它更好看。
 * 反过来做（关掉玻璃就变成普通白卡）会让强调彻底消失，那是把重点交给了设置项。
 *
 * ## 与 AppCard 的关系
 *
 * `AppCard` 的 KDoc 写着"卡片一律实底"（那批 WCAG 数字都按实底卡面算的）。这一块是**刻意的例外**，
 * 但只破在这一处：文字仍落在足够实的底上（见上一条），且**每屏只有一块**。
 * 其余卡片继续实底 —— 例外只有一处才叫重点，多了就又是板子。
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

    // 本体色：品牌色柔底压在卡面上，得到一片不透明的淡青；再按玻璃 alpha 铺出去。
    // `compositeOver` 而不是直接叠两层 background：只铺一层，符合"着色只允许一层"那条。
    val body = remember(colors.accent, colors.card) {
        colors.accent.copy(alpha = 0.10f).compositeOver(colors.card)
    }
    val glassOn = AppTheme.settings.glass != GlassLevel.OFF
    val baseStyle = rememberGlassStyle()
    val heroStyle = remember(baseStyle, body) {
        baseStyle.copy(tint = body.copy(alpha = AppTheme.glass.tintAlphaSoft))
    }

    // 用 Box 而不是 Surface：**透明容器配 `Surface(shadowElevation = …)` 会把阴影露出来** ——
    // 体是透明的，阴影没有东西盖住它，于是在卡片四周形成一圈灰框（2026-09-26 真机截图撞到，
    // 看着像"方角浅底嵌在圆角灰框里"）。玻璃那条路的边缘感由 `glassSurface` 自己的 1dp 边缘光负责，
    // 不需要、也不能再叠一层 Surface 阴影。
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .then(
                if (glassOn) {
                    Modifier.glassSurface(style = heroStyle, shape = shape)
                } else {
                    Modifier
                        .background(body)
                        .border(
                            width = 1.dp,
                            color = colors.accent.copy(alpha = 0.18f),
                            shape = shape,
                        )
                },
            ),
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
