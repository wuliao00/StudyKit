package com.studykit.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.studykit.ui.theme.AppTheme

/**
 * 统计磁贴大数字的字号阶梯（sp）。
 *
 * 基准 34sp 下，**4 位数就已经超出「三块磁贴并排」时最窄那块的内容宽度**，
 * 而 `maxLines = 1` 只防换行、不防溢出 —— 它把换行问题静默换成了裁字：
 * 学习首页 1177 词显示成「117」，习惯日历「1250 ml」显示成「1250」
 * （2026-09-24 真机走查撞见；uiautomator 里 text 是完整的 1177，所以是排版裁切不是数值算错）。
 * 少一位数比字小一点严重得多 —— 用户会以为数据丢了。
 *
 * 按**整串长度**而不是数字位数降档，因为调用方也会传带单位的形式（`"6 天"`、`"1250 ml"`）。
 * 3 字符以内保持基准，所以今天显示正常的值一个都不会变小。
 *
 * 故意照最窄的那块磁贴定档，两格并排（累计/连续）时会偏保守、字比必要的略小。
 * 要按实测宽度精算得引 `TextMeasurer` 量一遍再缩，那是后续的事。
 */
internal fun statValueFontSizeSp(value: String, baseSp: Float = 34f): Float = when (value.length) {
    in 0..3 -> baseSp
    4 -> baseSp * 0.74f
    5 -> baseSp * 0.62f
    6 -> baseSp * 0.53f
    else -> baseSp * 0.46f
}

/**
 * 统计磁贴的层级档。
 *
 * 加这一档是因为四屏原来都摆着**三块等重**的磁贴，于是"今天要还多少债"和"我一共有多少词"
 * 在视觉上同权 —— 整屏读起来像一列白板子，没有重点。
 *
 * - [Primary]：这一屏要强调的那个数（大号 + `primaryText`）。**每屏最多一块**，
 *   且现在通常直接由 `HeroSummaryCard` 承担，磁贴里很少再用；
 * - [Reference]：参考数字（小一档 + `secondaryText`）。它们是"查得到"的信息，
 *   不需要每天扫一眼，降档之后顶部那块重点才浮得出来。
 *
 * 颜色不新增令牌：`secondaryText` 本就是既有的次要文本档，压在实底卡面上原本就达标。
 */
enum class StatTileTier { Primary, Reference }

/**
 * 统计磁贴：大数字（[AppTheme.texts.statValue] 按 [statValueFontSizeSp] 缩档）+ 小号说明标签，
 * 用于页面顶部概览。与 [AppCard] 同款描边观感，零阴影；卡面 `colors.card` 随主题变化。
 *
 * 自身撑满所在高度，因此**必须**放在 `Row(Modifier.height(IntrinsicSize.Max))` 里并排使用
 * （四个页面均如此）；放进无界高度的容器里单独使用会纵向撑开，需要时改传固定高度。
 */
@Composable
fun StatTile(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    tier: StatTileTier = StatTileTier.Primary,
) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    // 参考档整体降一档字号（基准 34 → 26），再走同一套长度阶梯，长值照样不会裁字
    val baseSp = if (tier == StatTileTier.Reference) 26f else 34f
    val valueColor = if (tier == StatTileTier.Reference) colors.secondaryText else colors.primaryText
    // 参考档也把描边压淡一档：它不该和承载重点的卡一样有存在感
    val borderAlpha = if (tier == StatTileTier.Reference) 0.40f else 0.6f
    Surface(
        // 与调用方的 `Row(Modifier.height(IntrinsicSize.Max))` 配对：三块磁贴等高，
        // 标签换行（如「今日已打卡」）不再把单块顶得比邻居高。
        modifier = modifier.fillMaxHeight(),
        shape = RoundedCornerShape(AppTheme.radius.lg),
        color = colors.card,
        contentColor = colors.primaryText,
        border = BorderStroke(1.dp, colors.divider.copy(alpha = borderAlpha)),
        shadowElevation = AppTheme.elevation.none,
    ) {
        Column(modifier = Modifier.padding(AppTheme.space.card)) {
            Text(
                text = value,
                style = texts.statValue.copy(
                    fontSize = statValueFontSizeSp(value, baseSp).sp,
                    color = valueColor,
                ),
                maxLines = 1,
            )
            Spacer(Modifier.height(AppTheme.space.xs))
            Text(text = label, style = texts.caption)
        }
    }
}
