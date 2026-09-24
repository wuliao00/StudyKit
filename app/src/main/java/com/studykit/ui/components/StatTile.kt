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
 * 统计磁贴：34sp 大数字（[AppTheme.texts.statValue]，长值按 [statValueFontSizeSp] 缩档）+ 小号说明标签，
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
) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    Surface(
        // 与调用方的 `Row(Modifier.height(IntrinsicSize.Max))` 配对：三块磁贴等高，
        // 标签换行（如「今日已打卡」）不再把单块顶得比邻居高。
        modifier = modifier.fillMaxHeight(),
        shape = RoundedCornerShape(AppTheme.radius.lg),
        color = colors.card,
        contentColor = colors.primaryText,
        border = BorderStroke(1.dp, colors.divider.copy(alpha = 0.6f)),
        shadowElevation = AppTheme.elevation.none,
    ) {
        Column(modifier = Modifier.padding(AppTheme.space.card)) {
            Text(
                text = value,
                style = texts.statValue.copy(fontSize = statValueFontSizeSp(value).sp),
                maxLines = 1,
            )
            Spacer(Modifier.height(AppTheme.space.xs))
            Text(text = label, style = texts.caption)
        }
    }
}
