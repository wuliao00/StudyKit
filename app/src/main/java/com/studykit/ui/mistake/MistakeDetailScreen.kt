package com.studykit.ui.mistake

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.studykit.data.entity.Mistake
import com.studykit.ui.components.AppButton
import com.studykit.ui.components.AppCard
import com.studykit.ui.components.AppTextField
import com.studykit.ui.theme.DesignTokens
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

private fun sourceLabel(source: String): String = when (source) {
    Mistake.SOURCE_WORD -> "单词学习"
    Mistake.SOURCE_PRACTICE -> "刷题收录"
    Mistake.SOURCE_PHOTO -> "拍照录入"
    else -> source
}

/**
 * 错题详情页：大图查看（点击放大）+ 内容/备注 + 学科/来源/时间信息，
 * 操作：编辑学科、设置复习时间（快捷项）、标记已掌握、删除。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MistakeDetailScreen(
    viewModel: MistakeViewModel,
    onBack: () -> Unit,
) {
    val mistake by viewModel.detail.collectAsStateWithLifecycle()
    var showFullImage by remember { mutableStateOf(false) }
    var showSubjectDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
    val reviewFormat = remember { SimpleDateFormat("MM月dd日", Locale.getDefault()) }

    val current = mistake
    if (current == null) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = DesignTokens.PageHorizontalPadding),
        ) {
            Spacer(Modifier.height(DesignTokens.SpacingSm))
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = DesignTokens.Accent,
                )
            }
            Text(text = "错题不存在或已删除", style = DesignTokens.Caption)
        }
        return
    }

    val imageFile = current.imagePath?.let { viewModel.resolveImage(it) }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = DesignTokens.PageHorizontalPadding),
        ) {
            Spacer(Modifier.height(DesignTokens.SpacingSm))
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        tint = DesignTokens.Accent,
                    )
                }
                Spacer(Modifier.weight(1f))
                if (!current.mastered) {
                    TextButton(onClick = { viewModel.markMastered(current.id) }) {
                        Text(
                            text = "标记掌握",
                            style = DesignTokens.Auxiliary.copy(
                                color = DesignTokens.Success,
                                fontWeight = FontWeight.Medium,
                            ),
                        )
                    }
                }
            }

            Text(text = current.title, style = DesignTokens.PageTitle)
            Spacer(Modifier.height(DesignTokens.SpacingXs))
            Text(
                text = "${current.subject} · ${sourceLabel(current.source)} · ${dateFormat.format(current.createdAt)}",
                style = DesignTokens.Caption,
            )
            if (current.mastered) {
                Spacer(Modifier.height(DesignTokens.SpacingXs))
                Text(
                    text = "已掌握",
                    style = DesignTokens.Caption.copy(
                        color = DesignTokens.Success,
                        fontWeight = FontWeight.Medium,
                    ),
                )
            }

            // ── 大图 ──────────────────────────────────────────────────────
            if (imageFile != null && imageFile.exists()) {
                Spacer(Modifier.height(DesignTokens.SpacingMd))
                AsyncImage(
                    model = imageFile,
                    contentDescription = current.title,
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(DesignTokens.CornerRadiusLg))
                        .clickable { showFullImage = true },
                )
            }

            // ── 内容 / 备注 ──────────────────────────────────────────────
            if (current.content.isNotBlank()) {
                Spacer(Modifier.height(DesignTokens.SpacingMd))
                AppCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "题目内容",
                        style = DesignTokens.Caption.copy(fontWeight = FontWeight.Medium),
                    )
                    Spacer(Modifier.height(DesignTokens.SpacingXs))
                    Text(text = current.content, style = DesignTokens.Body)
                }
            }
            if (current.note.isNotBlank()) {
                Spacer(Modifier.height(DesignTokens.SpacingMd))
                AppCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "备注",
                        style = DesignTokens.Caption.copy(fontWeight = FontWeight.Medium),
                    )
                    Spacer(Modifier.height(DesignTokens.SpacingXs))
                    Text(text = current.note, style = DesignTokens.Body)
                }
            }

            // ── 复习时间 ──────────────────────────────────────────────────
            Spacer(Modifier.height(DesignTokens.SpacingLg))
            Text(text = "复习提醒", style = DesignTokens.CardTitle)
            Spacer(Modifier.height(DesignTokens.SpacingXs))
            Text(
                text = current.reviewAt?.let { "已设置：${reviewFormat.format(it)}" } ?: "尚未设置复习时间",
                style = DesignTokens.Caption,
            )
            Spacer(Modifier.height(DesignTokens.SpacingMd))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(DesignTokens.SpacingSm),
                verticalArrangement = Arrangement.spacedBy(DesignTokens.SpacingSm),
            ) {
                ReviewOption("明天") { viewModel.setReviewAt(current.id, dayOffset(1)) }
                ReviewOption("三天后") { viewModel.setReviewAt(current.id, dayOffset(3)) }
                ReviewOption("一周后") { viewModel.setReviewAt(current.id, dayOffset(7)) }
            }

            // ── 学科归类 ──────────────────────────────────────────────────
            Spacer(Modifier.height(DesignTokens.SpacingLg))
            AppButton(text = "编辑学科归类", secondary = true, onClick = { showSubjectDialog = true })

            Spacer(Modifier.height(DesignTokens.SpacingMd))
            AppButton(
                text = "删除错题",
                secondary = true,
                onClick = { showDeleteDialog = true },
            )
            Spacer(Modifier.height(DesignTokens.SpacingXl))
        }

        // ── 全屏看图 ──────────────────────────────────────────────────────
        if (showFullImage && imageFile != null) {
            FullImageOverlay(file = imageFile, onDismiss = { showFullImage = false })
        }
    }

    // ── 编辑学科对话框 ──────────────────────────────────────────────────────
    if (showSubjectDialog) {
        SubjectEditDialog(
            initial = current.subject,
            onDismiss = { showSubjectDialog = false },
            onConfirm = { subject ->
                viewModel.updateSubject(current.id, subject)
                showSubjectDialog = false
            },
        )
    }

    // ── 删除确认 ──────────────────────────────────────────────────────────
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(text = "删除错题", style = DesignTokens.CardTitle) },
            text = { Text(text = "删除后不可恢复，相关图片也会一并清理。", style = DesignTokens.Auxiliary) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    viewModel.delete(current.id) { onBack() }
                }) {
                    Text(text = "删除", color = DesignTokens.Warning)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(text = "取消", color = DesignTokens.Accent)
                }
            },
        )
    }
}

/** 复习时间快捷项 */
@Composable
private fun ReviewOption(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(DesignTokens.CornerRadius))
            .background(DesignTokens.Accent.copy(alpha = 0.10f))
            .clickable(onClick = onClick)
            .padding(horizontal = DesignTokens.SpacingMd, vertical = DesignTokens.SpacingSm),
    ) {
        Text(
            text = label,
            style = DesignTokens.Auxiliary.copy(
                color = DesignTokens.Accent,
                fontWeight = FontWeight.Medium,
            ),
        )
    }
}

/** 距今天 +days 天后的上午 9 点时间戳 */
private fun dayOffset(days: Int): Long {
    val calendar = Calendar.getInstance().apply {
        add(Calendar.DAY_OF_YEAR, days)
        set(Calendar.HOUR_OF_DAY, 9)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    return calendar.timeInMillis
}

/** 全屏看图：黑底占满，点击关闭 */
@Composable
private fun FullImageOverlay(file: File, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = file,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** 编辑学科对话框 */
@Composable
private fun SubjectEditDialog(
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "编辑学科归类", style = DesignTokens.CardTitle) },
        text = {
            AppTextField(
                value = value,
                onValueChange = { value = it },
                placeholder = "输入学科",
            )
        },
        confirmButton = {
            TextButton(
                enabled = value.isNotBlank(),
                onClick = { onConfirm(value.trim()) },
            ) {
                Text(text = "保存", color = DesignTokens.Accent)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "取消", color = DesignTokens.SecondaryText)
            }
        },
    )
}
