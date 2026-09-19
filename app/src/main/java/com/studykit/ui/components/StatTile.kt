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
import com.studykit.ui.theme.AppTheme

/**
 * 统计磁贴：34sp 大数字（[AppTheme.texts.statValue]，`maxLines = 1` 防 4 位数换行）+ 小号说明标签，
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
            Text(text = value, style = texts.statValue, maxLines = 1)
            Spacer(Modifier.height(AppTheme.space.xs))
            Text(text = label, style = texts.caption)
        }
    }
}
