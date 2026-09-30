package com.studykit.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.studykit.tips.StudyTips
import com.studykit.tips.Tip
import com.studykit.ui.theme.AppTheme

/**
 * 科学小贴士卡片：`[科学验证]` 徽标 + 一句结论 + 可选证据来源。
 *
 * 只在对应事件发生时出现一次，不做随机弹窗；徽标存在的意义是让「反直觉但站得住」的结论
 * 显得可信，而不是靠语气施压。
 *
 * 取色全部走 `AppTheme`：本仓是双主题，且 [com.studykit.ui.theme.StudyKitTheme] 只部分覆写了
 * Material3 的 ColorScheme，所以文字色必须显式取 ink 族（`accentInk` 在浅色卡面 5.81:1，
 * 品牌色 `accent` 只有 3.04:1，不足文本 AA）。
 */
@Composable
fun TipCard(
    tip: Tip,
    modifier: Modifier = Modifier,
    showEvidence: Boolean = false,
) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    AppCard(modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(AppTheme.space.sm)) {
            AppPill(
                container = colors.accentSoft,
                ink = colors.accentInk,
                label = StudyTips.TAG_LABEL,
            )
            Text(
                text = tip.text,
                style = texts.body,
            )
            if (showEvidence) {
                Text(
                    text = tip.evidence,
                    style = texts.caption,
                )
            }
        }
    }
}

/** 轻量徽标：供列表项或卡片角落复用同一句 `[科学验证]`，避免各页自己写一份措辞 */
@Composable
fun ScienceBadge(modifier: Modifier = Modifier) {
    val colors = AppTheme.colors
    AppPill(
        modifier = modifier,
        container = colors.accentSoft,
        ink = colors.accentInk,
        label = StudyTips.TAG_LABEL,
    )
}
