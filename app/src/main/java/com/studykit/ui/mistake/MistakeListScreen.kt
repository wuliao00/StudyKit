package com.studykit.ui.mistake

import android.Manifest
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.studykit.data.entity.Mistake
import com.studykit.ui.components.AppCard
import com.studykit.ui.components.EmptyState
import com.studykit.ui.theme.DesignTokens
import com.studykit.util.MistakeImageStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/** 轻量标签：来源、错因、高置信、钉住都用它 */
@Composable
private fun Tag(label: String, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(DesignTokens.SpacingXs))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = DesignTokens.SpacingSm, vertical = 2.dp),
    ) {
        Text(
            text = label,
            style = DesignTokens.Caption.copy(color = color, fontWeight = FontWeight.Medium),
        )
    }
}

/** 来源徽标：单词/刷题/拍照 */
@Composable
private fun SourceBadge(source: String) {
    val (label, color) = when (source) {
        Mistake.SOURCE_WORD -> "单词" to DesignTokens.Accent
        Mistake.SOURCE_PRACTICE -> "刷题" to DesignTokens.Success
        Mistake.SOURCE_PHOTO -> "拍照" to DesignTokens.Warning
        else -> source to DesignTokens.SecondaryText
    }
    Tag(label = label, color = color)
}

/** 学科筛选 Chip 行 */
@Composable
private fun SubjectFilterRow(
    subjects: List<String>,
    selected: String?,
    onSelect: (String?) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(DesignTokens.SpacingSm),
    ) {
        FilterChip(label = "全部", selected = selected == null) { onSelect(null) }
        subjects.forEach { subject ->
            FilterChip(label = subject, selected = selected == subject) { onSelect(subject) }
        }
    }
}

@Composable
private fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(DesignTokens.CornerRadius))
            .background(if (selected) DesignTokens.Accent else DesignTokens.Card)
            .clickable(onClick = onClick)
            .padding(horizontal = DesignTokens.SpacingMd, vertical = DesignTokens.SpacingSm),
    ) {
        Text(
            text = label,
            style = DesignTokens.Auxiliary.copy(
                color = if (selected) DesignTokens.Card else DesignTokens.PrimaryText,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            ),
        )
    }
}

/**
 * 错题列表页（错题 Tab）：待复习队列按引擎优先级排序（欠账越深、越是有把握答错越靠前），
 * 每条展示预测保留率与欠账天数；是否已掌握由跨间隔连对判定，没有手动按钮。
 */
@Composable
fun MistakeListScreen(
    viewModel: MistakeViewModel,
    onOpenDetail: (Long) -> Unit,
    onOpenCapture: () -> Unit,
) {
    val context = LocalContext.current
    val mistakes by viewModel.mistakes.collectAsStateWithLifecycle()
    val subjects by viewModel.subjects.collectAsStateWithLifecycle()
    val selected by viewModel.subjectFilter.collectAsStateWithLifecycle()
    val pending by viewModel.pendingQueue.collectAsStateWithLifecycle()
    val masteredList by viewModel.masteredList.collectAsStateWithLifecycle()
    val dueQueue by viewModel.dueQueue.collectAsStateWithLifecycle()
    val reviewTotal by viewModel.reviewTotal.collectAsStateWithLifecycle()
    val dateFormat = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
    val reviewFormat = remember { SimpleDateFormat("MM月dd日", Locale.getDefault()) }

    LaunchedEffect(Unit) { viewModel.refreshDueQueue() }

    // ── 拍照流程：CAMERA 权限 → TakePicture → 暂存临时文件 → 跳录入页 ────────
    var pendingUriFile by remember { mutableStateOf<File?>(null) }
    val takePictureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture(),
    ) { success ->
        val file = pendingUriFile
        if (success && file != null && file.exists() && file.length() > 0) {
            viewModel.setPendingCapture(file)
            onOpenCapture()
        } else {
            file?.delete()
        }
    }
    // 相机权限被拒后展示引导文案
    var showCameraRationale by remember { mutableStateOf(false) }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            showCameraRationale = false
            launchCamera(context) { file, uri ->
                pendingUriFile = file
                takePictureLauncher.launch(uri)
            }
        } else {
            showCameraRationale = true
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = DesignTokens.PageHorizontalPadding),
    ) {
        Spacer(Modifier.height(DesignTokens.SpacingSm))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = "错题本", style = DesignTokens.LargeTitle)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { cameraPermissionLauncher.launch(Manifest.permission.CAMERA) }) {
                Text(
                    text = "拍照录入",
                    style = DesignTokens.Auxiliary.copy(
                        color = DesignTokens.Accent,
                        fontWeight = FontWeight.SemiBold,
                    ),
                )
            }
        }
        Text(
            text = "共 ${mistakes.size} 道 · 待复习 ${pending.size} · 已掌握 ${masteredList.size}",
            style = DesignTokens.Caption,
        )
        Text(
            text = "累计重做 $reviewTotal 次 · 今日到期 ${dueQueue.size} 道",
            style = DesignTokens.Caption,
        )
        Text(
            text = "顺序由算法按欠账程度决定；跨间隔连对两次才转已掌握。",
            style = DesignTokens.Caption,
        )

        if (showCameraRationale) {
            Spacer(Modifier.height(DesignTokens.SpacingSm))
            Text(
                text = "拍照录入需要相机权限。请在系统设置 → 应用 → StudyKit → 权限中允许「相机」，" +
                    "或再次点击右上角「拍照录入」重新发起授权。",
                style = DesignTokens.Caption.copy(color = DesignTokens.Warning),
            )
        }

        Spacer(Modifier.height(DesignTokens.SpacingMd))
        SubjectFilterRow(subjects = subjects, selected = selected, onSelect = viewModel::selectSubject)

        Spacer(Modifier.height(DesignTokens.SpacingMd))
        if (pending.isEmpty() && masteredList.isEmpty()) {
            Spacer(Modifier.height(DesignTokens.SpacingXl * 2))
            EmptyState(
                title = if (mistakes.isEmpty()) "错题本还是空的" else "该学科下暂无错题",
                caption = "去「题库练习」答题自动收录，或点右上角「拍照录入」",
                icon = Icons.Outlined.Close,
            )
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(DesignTokens.SpacingMd),
                modifier = Modifier.fillMaxSize(),
            ) {
                if (pending.isNotEmpty()) {
                    item(key = "header_pending") {
                        QueueHeader(
                            title = "待复习",
                            count = pending.size,
                            caption = "预测保留率越低、欠账越久的越靠前",
                        )
                    }
                    items(pending, key = { "pending_${it.id}" }) { mistake ->
                        MistakeItem(
                            mistake = mistake,
                            dateText = dateFormat.format(mistake.createdAt),
                            reviewText = mistake.reviewAt?.let { reviewFormat.format(it) },
                            showMetrics = true,
                            thumbFile = mistake.imagePath?.let { viewModel.resolveThumb(it) },
                            fullFile = mistake.imagePath?.let { viewModel.resolveImage(it) },
                            onClick = { onOpenDetail(mistake.id) },
                        )
                    }
                }
                if (masteredList.isNotEmpty()) {
                    item(key = "header_mastered") {
                        QueueHeader(
                            title = "已掌握",
                            count = masteredList.size,
                            caption = "已退出排队，仍可进去重做检验",
                        )
                    }
                    items(masteredList, key = { "mastered_${it.id}" }) { mistake ->
                        MistakeItem(
                            mistake = mistake,
                            dateText = dateFormat.format(mistake.createdAt),
                            reviewText = null,
                            showMetrics = false,
                            thumbFile = mistake.imagePath?.let { viewModel.resolveThumb(it) },
                            fullFile = mistake.imagePath?.let { viewModel.resolveImage(it) },
                            onClick = { onOpenDetail(mistake.id) },
                        )
                    }
                }
                item { Spacer(Modifier.height(DesignTokens.SpacingMd)) }
            }
        }
    }
}

/** 队列小标题：段名 + 数量 + 一句排序口径说明 */
@Composable
private fun QueueHeader(title: String, count: Int, caption: String) {
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DesignTokens.SpacingSm),
        ) {
            Text(text = title, style = DesignTokens.PageTitle)
            Text(text = "$count 道", style = DesignTokens.Caption)
        }
        Text(text = caption, style = DesignTokens.Caption)
    }
}

/** 创建相机临时文件并生成 FileProvider Uri（Android 11 需经 FileProvider 共享） */
private fun launchCamera(context: Context, onReady: (File, Uri) -> Unit) {
    val dir = MistakeImageStore.cameraTempDir(context)
    val file = File(dir, "capture_${System.currentTimeMillis()}.jpg")
    val uri = FileProvider.getUriForFile(context, "com.studykit.fileprovider", file)
    onReady(file, uri)
}

/**
 * 错题条目：来源/错因/高置信标签 + 标题 + 摘要 + 缩略图。
 * 待复习条目额外显示预测保留率与欠账天数。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MistakeItem(
    mistake: Mistake,
    dateText: String,
    reviewText: String?,
    showMetrics: Boolean,
    thumbFile: File?,
    fullFile: File?,
    onClick: () -> Unit,
) {
    val now = System.currentTimeMillis()
    val retention = MistakeReviewLogic.predictedRetention(mistake, now)
    val overdue = MistakeReviewLogic.overdueDays(mistake, now)

    AppCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(DesignTokens.SpacingSm),
                ) {
                    SourceBadge(source = mistake.source)
                    Text(text = dateText, style = DesignTokens.Caption)
                    if (mistake.mastered) {
                        Tag(label = "已掌握", color = DesignTokens.Success)
                    }
                    if (mistake.highConfidenceError) {
                        Tag(label = "高置信答错", color = DesignTokens.Warning)
                    }
                }
                Spacer(Modifier.height(DesignTokens.SpacingSm))
                Text(
                    text = mistake.title,
                    style = DesignTokens.CardTitle,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (mistake.content.isNotBlank()) {
                    Spacer(Modifier.height(DesignTokens.SpacingXs))
                    Text(
                        text = mistake.content.replace("\n", " "),
                        style = DesignTokens.Caption,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (showMetrics) {
                    Spacer(Modifier.height(DesignTokens.SpacingSm))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(DesignTokens.SpacingSm),
                        verticalArrangement = Arrangement.spacedBy(DesignTokens.SpacingXs),
                    ) {
                        Text(
                            text = retention?.let { "预测保留 ${MistakeReviewLogic.retentionPercent(it)}%" }
                                ?: "尚未进入调度",
                            style = DesignTokens.Caption.copy(
                                color = if (retention != null && retention < 0.9) {
                                    DesignTokens.Warning
                                } else {
                                    DesignTokens.SecondaryText
                                },
                            ),
                        )
                        if (overdue > 0) {
                            Text(
                                text = "欠账 $overdue 天",
                                style = DesignTokens.Caption.copy(color = DesignTokens.Warning),
                            )
                        }
                        reviewText?.let {
                            Text(
                                text = if (mistake.pinned) "复习日 $it（已钉住）" else "复习日 $it",
                                style = DesignTokens.Caption,
                            )
                        }
                        Text(
                            text = if (mistake.cause.isBlank()) "未归因" else "错因：${mistake.cause}",
                            style = DesignTokens.Caption.copy(
                                color = if (mistake.cause.isBlank()) {
                                    DesignTokens.SecondaryText
                                } else {
                                    DesignTokens.Accent
                                },
                            ),
                        )
                        Text(
                            text = "连对 ${mistake.correctStreak} 次",
                            style = DesignTokens.Caption,
                        )
                    }
                }
            }
            val imageFile = thumbFile ?: fullFile
            if (imageFile != null && imageFile.exists()) {
                Spacer(Modifier.width(DesignTokens.SpacingMd))
                AsyncImage(
                    model = imageFile,
                    contentDescription = mistake.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(DesignTokens.SpacingSm)),
                )
            }
        }
    }
}
