package com.studykit.ui.study

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.studykit.data.entity.WordList
import com.studykit.ui.bulkimport.ImportViewModel
import com.studykit.ui.components.AppButton
import com.studykit.ui.components.AppCard
import com.studykit.ui.components.AppPill
import com.studykit.ui.components.AppTextField
import com.studykit.ui.components.EmptyState
import com.studykit.ui.components.SectionHeader
import com.studykit.ui.theme.AppTheme
import com.studykit.util.importer.DictBookInfo

/**
 * 词库商店：搜索、每本独立进度、已导入整本撤销。
 *
 * 进度条挂在行内而不是全局遮罩 —— 一次只下一本，但用户可能边下边翻目录，
 * 全屏挡一下就没法挑了。
 */
@Composable
fun DictStoreScreen(
    viewModel: DictStoreViewModel,
    importViewModel: ImportViewModel,
    onBack: () -> Unit,
    onPreview: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
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
            Text(text = "词库商店", style = texts.pageTitle)
        }
        Spacer(Modifier.height(AppTheme.space.md))
        AppTextField(
            value = state.query,
            onValueChange = viewModel::setQuery,
            placeholder = "搜书名或标签，如 四级 / 考研 / 新东方",
        )
        Spacer(Modifier.height(AppTheme.space.md))
        when (state.phase) {
            DictStorePhase.LOADING -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = colors.accent)
            }

            DictStorePhase.FAILED -> {
                Spacer(Modifier.height(AppTheme.space.xl))
                EmptyState(title = "词库列表没拉到", caption = state.error ?: "检查网络后重试")
                Spacer(Modifier.height(AppTheme.space.lg))
                AppButton(text = "重试", onClick = viewModel::refresh)
            }

            DictStorePhase.READY -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(AppTheme.space.md),
            ) {
                if (state.imported.isNotEmpty()) {
                    item(key = "imported_header") { SectionHeader(title = "已导入（可整本撤销）") }
                    items(state.imported, key = { "list_${it.id}" }) { list ->
                        ImportedListRow(
                            list = list,
                            onDelete = {
                                viewModel.deleteList(list) { removed ->
                                    Toast.makeText(context, "已撤销 $removed 个单词", Toast.LENGTH_SHORT).show()
                                }
                            },
                        )
                    }
                }
                if (state.online == false) {
                    // 快照与在线目录内容一模一样，不标出来的话「能列出书」就会被当成「联网成功」的证据
                    item(key = "snapshot_notice") {
                        AppCard(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = "手机没连上，下面这份是打包进 App 的目录快照，可能不是最新；" +
                                    "下载词表需要网络。",
                                style = texts.caption,
                            )
                            Spacer(Modifier.height(AppTheme.space.sm))
                            AppButton(text = "重新连一次", secondary = true, onClick = viewModel::refresh)
                        }
                    }
                }
                state.error?.let { message ->
                    item(key = "error_line") {
                        Text(text = message, style = texts.caption.copy(color = colors.warningInk))
                    }
                }
                items(state.availableBooks, key = { it.id }) { book ->
                    DictBookRow(
                        book = book,
                        progress = state.progress[book.id],
                        onImport = { viewModel.importBook(book, importViewModel, onPreview) },
                    )
                }
                item(key = "attribution") { AttributionCard() }
                item { Spacer(Modifier.height(AppTheme.space.md)) }
            }
        }
    }
}

@Composable
private fun DictBookRow(book: DictBookInfo, progress: Float?, onImport: () -> Unit) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Text(text = book.title, style = texts.cardTitle)
        Spacer(Modifier.height(AppTheme.space.xs))
        Text(
            text = "${book.wordNum} 词 · ${readableSize(book.sizeBytes)}",
            style = texts.caption,
        )
        if (book.tags.isNotEmpty()) {
            Spacer(Modifier.height(AppTheme.space.sm))
            Row(horizontalArrangement = Arrangement.spacedBy(AppTheme.space.xs)) {
                book.tags.take(3).forEach { tag ->
                    AppPill(container = colors.accentSoft, ink = colors.accentInk, label = tag)
                }
            }
        }
        Spacer(Modifier.height(AppTheme.space.md))
        if (progress != null) {
            LinearProgressIndicator(
                progress = { progress },
                color = colors.accent,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(AppTheme.space.sm))
        }
        AppButton(
            text = if (progress == null) "导入这本" else "下载中 ${(progress * 100).toInt()}%",
            secondary = true,
            enabled = progress == null,
            onClick = onImport,
        )
    }
}

@Composable
private fun ImportedListRow(list: WordList, onDelete: () -> Unit) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    var confirm by rememberSaveable(list.id) { mutableStateOf(false) }
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(text = list.title, style = texts.cardTitle)
                Spacer(Modifier.height(AppTheme.space.xs))
                Text(text = "共 ${list.wordNum} 词 · 已导入 ${list.importedCount}", style = texts.caption)
            }
            TextButton(onClick = { confirm = true }) {
                Text(
                    text = "撤销",
                    style = texts.aux.copy(color = colors.warningInk, fontWeight = FontWeight.Medium),
                )
            }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(text = "撤销《${list.title}》？") },
            text = {
                Text(text = "会删掉这本词库带进来的 ${list.importedCount} 个单词，手工录入的不受影响。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirm = false
                        onDelete()
                    },
                ) { Text(text = "撤销", color = colors.warningInk) }
            },
            dismissButton = {
                TextButton(onClick = { confirm = false }) { Text(text = "留着", color = colors.accentInk) }
            },
            containerColor = colors.card,
        )
    }
}

/** 数据来源与许可（spec §7 明确要求，不可省略） */
@Composable
private fun AttributionCard() {
    val texts = AppTheme.texts
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Text(text = "数据来源", style = texts.cardTitle)
        Spacer(Modifier.height(AppTheme.space.xs))
        Text(
            text = "词表来自开源仓库 kajweb/dict（抓取自公开背词应用），仅用于个人学习；" +
                "导入后完全离线，App 不会上传你的任何数据。",
            style = texts.caption,
        )
    }
}

/** 目录里的 size 是字节；800KB 与 0.8MB 混着看不如统一成一位小数 */
private fun readableSize(bytes: Int): String = when {
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000f)
    bytes >= 1_000 -> "${bytes / 1_000} KB"
    else -> "$bytes B"
}
