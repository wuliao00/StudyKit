package com.studykit.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.studykit.tips.Tip
import com.studykit.tips.StudyTips
import com.studykit.ui.theme.DesignTokens

/**
 * 科学小贴士卡片：徽标 + 文案 + 证据来源。
 *
 * 只在对应事件发生时出现一次，不做随机弹窗；
 * 「[科学验证]」徽标的作用是让反直觉的结论显得可信，而不是靠语气施压。
 */
@Composable
fun TipCard(
    tip: Tip,
    modifier: Modifier = Modifier,
    showEvidence: Boolean = false,
) {
    Column(
        modifier = modifier
            .padding(horizontal = DesignTokens.PageHorizontalPadding, vertical = DesignTokens.SpacingSm)
            .padding(DesignTokens.CardPadding),
        verticalArrangement = Arrangement.spacedBy(DesignTokens.SpacingSm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = StudyTips.TAG_LABEL,
                style = DesignTokens.Caption,
                color = DesignTokens.Accent,
                textAlign = TextAlign.Center,
            )
        }
        Text(text = tip.text, style = DesignTokens.Body)
        if (showEvidence) {
            Text(
                text = tip.evidence,
                style = DesignTokens.Caption,
                color = DesignTokens.SecondaryText,
            )
        }
    }
}

/** 轻量徽标，供卡片角落或列表项复用 */
@Composable
fun ScienceBadge(modifier: Modifier = Modifier) {
    Text(
        modifier = modifier
            .padding(4.dp),
        text = StudyTips.TAG_LABEL,
        style = DesignTokens.Caption,
        color = DesignTokens.Accent,
    )
}
