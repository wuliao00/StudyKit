package com.studykit.ui.study

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.ui.text.style.TextOverflow
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
@OptIn(ExperimentalLayoutApi::class)
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
                item {
                    // v3.5：一句总账。题包变多以后，先告诉用户“这里一共有多少题”，
                    // 比让他自己把六个卡片上的数字加起来有用。
                    // 用 FlowRow 而不是 Row：三颗胶囊在窄屏 + 大字号下要能换行，
                    // AppPill 的文案恒为单行、没有省略号，塞不下时会被直接裁掉。
                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = AppTheme.space.xs),
                        horizontalArrangement = Arrangement.spacedBy(AppTheme.space.sm),
                        verticalArrangement = Arrangement.spacedBy(AppTheme.space.xs),
                    ) {
                        AppPill(
                            container = colors.accentSoft,
                            ink = colors.accentInk,
                            label = "${packs.map { it.stage }.distinct().size} 个阶段",
                        )
                        AppPill(
                            container = colors.goldSoft,
                            ink = colors.goldInk,
                            label = "共 ${packs.sumOf { it.count }} 题",
                        )
                        AppPill(
                            container = colors.successSoft,
                            ink = colors.successInk,
                            label = "全离线",
                        )
                    }
                }
                items(packs, key = { it.id }) { pack ->
                    PackCard(
                        pack = pack,
                        imported = state.importedByStage[pack.stage.storageValue] ?: 0,
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
    imported: Int,
    downloading: Boolean,
    enabled: Boolean,
    onDownload: () -> Unit,
) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 标题让位给胶囊：AppPill 的文案恒为单行、没有省略号，窄屏大字号下
            // 只有标题能折行/缩短，否则「已入库 N」会被裁成半截。
            Text(
                text = pack.title,
                style = texts.cardTitle,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(AppTheme.space.sm))
            AppPill(
                container = colors.accentSoft,
                ink = colors.accentInk,
                label = pack.stage.label,
            )
            if (imported > 0) {
                Spacer(Modifier.width(AppTheme.space.xs))
                AppPill(
                    container = colors.successSoft,
                    ink = colors.successInk,
                    // 说的是「这一阶段本机有多少题」，不是「这一包导过」：
                    // 老用户导过旧包又导新包时，两者会不一样。
                    label = "本阶段已有 $imported",
                    leadingDot = colors.success,
                )
            }
        }
        Spacer(Modifier.height(AppTheme.space.xs))
        Text(
            text = pack.subjects.joinToString(" / ") + " · ${pack.count} 题",
            style = texts.caption,
        )
        if (pack.typeCounts.isNotEmpty()) {
            Spacer(Modifier.height(AppTheme.space.xs))
            // v3.5：题型分布写进卡片，下载前就知道这包是“全是单选”还是“带主观题”。
            // 大学包有 8 种题型，一行必然放不下，所以限两行 + 省略号。
            Text(
                text = pack.typeCounts.joinToString(" · ") { "${it.label} ${it.count}" },
                style = texts.caption.copy(color = colors.secondaryText),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (pack.description.isNotBlank()) {
            Spacer(Modifier.height(AppTheme.space.xs))
            Text(text = pack.description, style = texts.aux)
        }
        Spacer(Modifier.height(AppTheme.space.xs))
        Text(text = "来源：${pack.source}", style = texts.caption)
        Text(text = "许可：${pack.license}", style = texts.caption)
        Spacer(Modifier.height(AppTheme.space.sm))
        AppButton(
            text = when {
                downloading -> "读取中…"
                imported > 0 -> "再预览一次"
                else -> "下载并预览"
            },
            enabled = enabled,
            secondary = true,
            onClick = onDownload,
        )
    }
}
