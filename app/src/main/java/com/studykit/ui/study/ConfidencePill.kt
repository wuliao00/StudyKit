package com.studykit.ui.study

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import com.studykit.data.memory.Confidence
import com.studykit.ui.theme.AppTheme

/**
 * 翻面后、评分前的 10 秒信心条（app.docx 模块1 P0"自评信心"/第三部分#3）。
 * 文案刻意中性：这是给用户自己看的校准，不是打分表演。
 * 跳过（selected=null 且用户直接评分）= confidence 记 NULL，不参与超纠正。
 *
 * 没复用 [com.studykit.ui.components.AppPill]：它的签名是 (container, ink, label, modifier, leadingDot)，
 * **无 onClick 无选中态**（只展示）；选中/未选只用已有 AppColors token（accentSoft/accentInk/card/
 * secondaryText），不新增色值、不动 AppPill 的公共 API。
 */
private val LABELS = listOf(
    Confidence.GUESS to "瞎猜",
    Confidence.FAIR to "有点印象",
    Confidence.SURE to "非常确定",
)

@Composable
fun ConfidenceRow(
    selected: Confidence?,
    onPick: (Confidence) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(AppTheme.space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("有多大把握？", style = texts.caption, color = colors.secondaryText)
        LABELS.forEach { (value, label) ->
            val isSelected = selected == value
            Text(
                text = label,
                style = texts.caption.copy(fontWeight = FontWeight.Medium),
                color = if (isSelected) colors.accentInk else colors.secondaryText,
                modifier = Modifier
                    .clip(RoundedCornerShape(AppTheme.radius.sm))
                    .background(if (isSelected) colors.accentSoft else colors.card)
                    .clickable { onPick(value) }
                    .padding(horizontal = AppTheme.space.md, vertical = AppTheme.space.sm),
            )
        }
    }
}
