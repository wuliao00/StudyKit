package com.studykit.ui.bulkimport

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.studykit.ui.components.AppButton
import com.studykit.ui.components.AppCard
import com.studykit.ui.components.SectionHeader
import com.studykit.ui.components.StatTile
import com.studykit.ui.theme.AppTheme
import com.studykit.util.importer.ImportItem

/**
 * 导入预览：逐行可勾选剔除、待修正区只读展示。
 * 「先看清楚再入库」是这套流程存在的理由 —— 一次贴 300 行时，用户必须扫一眼就知道有没有解析错。
 */
@Composable
fun ImportPreviewScreen(
    viewModel: ImportViewModel,
    kindLabel: String,
    onBack: () -> Unit,
    onSubmit: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = AppTheme.space.pageH),
    ) {
        Spacer(Modifier.height(AppTheme.space.sm))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = colors.accentInk,
                )
            }
            Spacer(Modifier.width(AppTheme.space.xs))
            Text(text = "预览$kindLabel", style = texts.pageTitle)
        }
        Spacer(Modifier.height(AppTheme.space.md))
        state.plan?.let { plan ->
            // StatTile 自身 fillMaxHeight()，必须包在 height(IntrinsicSize.Max) 的 Row 里才等高（M1 真机结论）
            Row(
                modifier = Modifier.height(IntrinsicSize.Max),
                horizontalArrangement = Arrangement.spacedBy(AppTheme.space.sm),
            ) {
                StatTile(value = "${plan.items.size}", label = "可导入", modifier = Modifier.weight(1f))
                StatTile(value = "${plan.rejected.size}", label = "待修正", modifier = Modifier.weight(1f))
                StatTile(
                    value = "${plan.items.size - state.visibleItems.size}",
                    label = "已剔除",
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(AppTheme.space.lg))
            SectionHeader(title = "逐行确认")
            Spacer(Modifier.height(AppTheme.space.sm))
            plan.items.forEach { item ->
                val excluded = item.sourceLine in state.excluded
                AppCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.toggleExcluded(item.sourceLine) },
                    ) {
                        Checkbox(
                            checked = !excluded,
                            onCheckedChange = { viewModel.toggleExcluded(item.sourceLine) },
                            colors = CheckboxDefaults.colors(checkedColor = colors.accentInk),
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = item.summary(),
                                // 被剔除的行用删除线表达，而不是变灰：灰字在深色主题下与禁用态撞车
                                style = texts.body.copy(
                                    textDecoration = if (excluded) TextDecoration.LineThrough else TextDecoration.None,
                                ),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(text = "第 ${item.sourceLine} 行", style = texts.caption)
                        }
                    }
                }
                Spacer(Modifier.height(AppTheme.space.sm))
            }
            if (plan.rejected.isNotEmpty()) {
                Spacer(Modifier.height(AppTheme.space.md))
                SectionHeader(title = "待修正 ${plan.rejected.size} 行")
                Spacer(Modifier.height(AppTheme.space.sm))
                AppCard(modifier = Modifier.fillMaxWidth()) {
                    plan.rejected.forEach { rejected ->
                        Text(
                            text = "第 ${rejected.sourceLine} 行 · ${rejected.reason.label()} · ${rejected.raw}",
                            style = texts.caption,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Spacer(Modifier.height(AppTheme.space.lg))
            state.error?.let {
                // 直接落在页面底色上，所以走 warningInk 而不是 warning（本仓约定：*Ink 才是文字色）
                Text(text = it, style = texts.caption.copy(color = colors.warningInk))
                Spacer(Modifier.height(AppTheme.space.sm))
            }
            if (state.phase == ImportPhase.COMMITTING) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(color = colors.accent, strokeWidth = 2.dp)
                    Spacer(Modifier.width(AppTheme.space.sm))
                    Text(text = "正在写入…", style = texts.caption)
                }
            }
            AppButton(
                text = "导入 ${state.visibleItems.size} 条",
                enabled = state.canSubmit,
                onClick = { viewModel.submit(onSubmit) },
            )
            Spacer(Modifier.height(AppTheme.space.lg))
        }
    }
}

/** 每个条目取一行人类可读的摘要 */
private fun ImportItem.summary(): String = when (this) {
    is ImportItem.Word -> "$word  $meaning"
    is ImportItem.Question -> "$stem（${options.size} 选项 · 答案 ${'A' + answerIndex}）"
    is ImportItem.Excerpt -> "$book：$excerpt"
    is ImportItem.Mistake -> "$subject · $title"
}
