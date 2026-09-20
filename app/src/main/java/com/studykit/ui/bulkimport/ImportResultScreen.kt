package com.studykit.ui.bulkimport

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.studykit.ui.components.AppButton
import com.studykit.ui.components.AppCard
import com.studykit.ui.components.ConfettiBurst
import com.studykit.ui.components.StatTile
import com.studykit.ui.theme.AppTheme
import com.studykit.util.importer.ImportOutcome

/**
 * 导入结果（spec §5.5）：成功 N / 重复跳过 M / 待修正 K，两类明细都能展开逐行看。
 *
 * 刻意把「重复跳过」的词面列出来而不是只给一个数字：判重是全局的（同一个词不能进两本词库），
 * 用户看到条数变少又不知道少的是哪几条，只会以为导入把数据吞了。
 * 有成功条目时放一次彩带，与 M1 的庆祝语言一致；trigger 用 outcome 的行数组合，
 * 保证同一次结果不会因重组再放一次（ConfettiBurst 首次组合即播放，见其 KDoc）。
 */
@Composable
fun ImportResultScreen(
    outcome: ImportOutcome,
    onDone: () -> Unit,
    onReviewRejected: () -> Unit,
) {
    val texts = AppTheme.texts
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = AppTheme.space.pageH),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(AppTheme.space.xl * 2))
            Text(text = "导入完成", style = texts.pageTitle)
            Spacer(Modifier.height(AppTheme.space.lg))
            // 与预览页同一处理：StatTile 自身 fillMaxHeight()，要包在 IntrinsicSize.Max 的 Row 里才等高
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Max),
                horizontalArrangement = Arrangement.spacedBy(AppTheme.space.sm),
            ) {
                StatTile(value = "${outcome.inserted}", label = "成功导入", modifier = Modifier.weight(1f))
                StatTile(value = "${outcome.skippedDuplicates.size}", label = "重复跳过", modifier = Modifier.weight(1f))
                StatTile(value = "${outcome.rejected.size}", label = "待修正", modifier = Modifier.weight(1f))
            }
            if (outcome.inserted > 0) {
                Spacer(Modifier.height(AppTheme.space.lg))
                Text(text = "一次 ${outcome.inserted} 条，比手打快多了", style = texts.caption)
            }
            if (outcome.skippedDuplicates.isNotEmpty()) {
                Spacer(Modifier.height(AppTheme.space.lg))
                FoldableSection(
                    title = "重复跳过 ${outcome.skippedDuplicates.size} 条",
                    lines = outcome.skippedDuplicates,
                )
            }
            if (outcome.rejected.isNotEmpty()) {
                Spacer(Modifier.height(AppTheme.space.lg))
                FoldableSection(
                    title = "待修正 ${outcome.rejected.size} 行",
                    lines = outcome.rejected.map { "第 ${it.sourceLine} 行 · ${it.reason.label()} · ${it.raw}" },
                )
            }
            Spacer(Modifier.height(AppTheme.space.lg))
            AppButton(text = "好", onClick = onDone)
            if (outcome.rejected.isNotEmpty()) {
                Spacer(Modifier.height(AppTheme.space.md))
                AppButton(text = "回去修那几行", secondary = true, onClick = onReviewRejected)
            }
            Spacer(Modifier.height(AppTheme.space.xl))
        }
        if (outcome.inserted > 0) {
            ConfettiBurst(
                trigger = outcome.inserted to outcome.rejected.size,
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}

/** 可展开的明细区。默认收起：一次导 300 条时明细可能有几十行，展开态不该抢走首屏 */
@Composable
private fun FoldableSection(title: String, lines: List<String>) {
    val texts = AppTheme.texts
    var expanded by rememberSaveable { mutableStateOf(false) }
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
        ) {
            Text(text = title, style = texts.cardTitle, modifier = Modifier.weight(1f))
            Text(text = if (expanded) "收起" else "展开", style = texts.caption)
        }
        if (expanded) {
            Spacer(Modifier.height(AppTheme.space.sm))
            lines.forEach { line ->
                Text(text = line, style = texts.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
