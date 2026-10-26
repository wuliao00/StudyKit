package com.studykit.ui.study

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.studykit.ui.bulkimport.ImportViewModel
import com.studykit.ui.components.AppButton
import com.studykit.ui.components.AppCard
import com.studykit.ui.components.AppPill
import com.studykit.ui.components.EmptyState
import com.studykit.ui.theme.AppTheme
import com.studykit.util.importer.QuestionPackInfo

/**
 * 题库商店：按阶段列出题包，下载后先进批量导入的预览屏，再由用户确认入库。
 *
 * 当前目录与题包随 APK 打包（原创示例，逐包标注许可），因此“下载”不联网、不需要存储权限；
 * 这一点在页头明说，避免用户误以为下面列的是高考/考研真题。
 */
@Composable
fun QuestionStoreScreen(
    viewModel: QuestionStoreViewModel,
    importViewModel: ImportViewModel,
    onBack: () -> Unit,
    onPreview: () -> Unit,
) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    val state by viewModel.state.collectAsStateWithLifecycle()
    val catalog = state.catalog

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppTheme.space.pageH, vertical = AppTheme.space.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = colors.accentInk,
                )
            }
            Spacer(Modifier.width(AppTheme.space.xs))
            Column {
                Text(text = "题库商店", style = texts.pageTitle)
                Text(text = "按阶段下载题包 · 先预览再入库", style = texts.caption)
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = AppTheme.space.pageH,
                end = AppTheme.space.pageH,
                bottom = AppTheme.space.xl,
            ),
            verticalArrangement = Arrangement.spacedBy(AppTheme.space.md),
        ) {
            item {
                Text(
                    text = "当前目录是随应用打包的原创示例题，不含高考/考研/考公真题或教材原题；" +
                        "每个包都标了来源与许可。导入他人题包前请确认你有再分发/使用权。",
                    style = texts.caption,
                )
            }

            if (state.loading) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = AppTheme.space.lg),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(color = colors.accent, strokeWidth = 2.dp)
                        Spacer(Modifier.width(AppTheme.space.sm))
                        Text(text = "读取题库目录…", style = texts.caption)
                    }
                }
            }

            state.error?.let { message ->
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = message,
                            style = texts.caption.copy(color = colors.warningInk),
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = viewModel::reload) {
                            Text(text = "重试", style = texts.aux.copy(color = colors.accentInk))
                        }
                    }
                }
            }

            if (catalog != null && catalog.packs.isEmpty() && !state.loading) {
                item {
                    EmptyState(
                        title = "目录里没有题包",
                        caption = "检查 manifest.json，或先用手动导入",
                    )
                }
            }

            catalog?.notice?.takeIf { it.isNotBlank() }?.let { notice ->
                item {
                    Text(
                        text = notice,
                        style = texts.caption.copy(color = colors.secondaryText),
                    )
                }
            }

            catalog?.packs?.let { packs ->
                items(packs, key = { it.id }) { pack ->
                    PackCard(
                        pack = pack,
                        downloading = state.downloadingId == pack.id,
                        enabled = state.downloadingId == null,
                        onDownload = {
                            viewModel.download(pack) { plan ->
                                importViewModel.loadPlan(plan)
                                onPreview()
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun PackCard(
    pack: QuestionPackInfo,
    downloading: Boolean,
    enabled: Boolean,
    onDownload: () -> Unit,
) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = pack.title, style = texts.cardTitle)
            Spacer(Modifier.width(AppTheme.space.sm))
            AppPill(
                container = colors.accentSoft,
                ink = colors.accentInk,
                label = pack.stage.label,
            )
        }
        Spacer(Modifier.height(AppTheme.space.xs))
        Text(
            text = pack.subjects.joinToString(" / ") + " · ${pack.count} 题",
            style = texts.caption,
        )
        if (pack.description.isNotBlank()) {
            Spacer(Modifier.height(AppTheme.space.xs))
            Text(text = pack.description, style = texts.aux)
        }
        Spacer(Modifier.height(AppTheme.space.xs))
        Text(text = "来源：${pack.source}", style = texts.caption)
        Text(text = "许可：${pack.license}", style = texts.caption)
        Spacer(Modifier.height(AppTheme.space.sm))
        AppButton(
            text = if (downloading) "读取中…" else "下载并预览",
            enabled = enabled,
            secondary = true,
            onClick = onDownload,
        )
    }
}
