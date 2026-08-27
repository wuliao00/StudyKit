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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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

/** 来源徽标：单词/刷题/拍照 */
@Composable
private fun SourceBadge(source: String) {
    val (label, color) = when (source) {
        Mistake.SOURCE_WORD -> "单词" to DesignTokens.Accent
        Mistake.SOURCE_PRACTICE -> "刷题" to DesignTokens.Success
        Mistake.SOURCE_PHOTO -> "拍照" to DesignTokens.Warning
        else -> source to DesignTokens.SecondaryText
    }
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
 * 错题列表页（错题 Tab）：学科筛选 + 按学科分组 + 来源徽标 + 图片缩略图 + 拍照录入入口。
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
    val groups by viewModel.groups.collectAsStateWithLifecycle()
    val dateFormat = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }

    // ── 拍照流程：CAMERA 权限 → TakePicture → 暂存临时文件 → 跳录入页 ────────
    var pendingUriFile by remember { androidx.compose.runtime.mutableStateOf<File?>(null) }
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
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) launchCamera(context) { file, uri ->
            pendingUriFile = file
            takePictureLauncher.launch(uri)
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
            text = "共 ${mistakes.size} 道错题",
            style = DesignTokens.Caption,
        )

        Spacer(Modifier.height(DesignTokens.SpacingMd))
        SubjectFilterRow(subjects = subjects, selected = selected, onSelect = viewModel::selectSubject)

        Spacer(Modifier.height(DesignTokens.SpacingMd))
        if (groups.isEmpty()) {
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
                groups.forEach { group ->
                    item(key = "header_${group.subject}") {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(DesignTokens.SpacingSm),
                        ) {
                            Text(
                                text = group.subject,
                                style = DesignTokens.PageTitle,
                            )
                            Text(text = "${group.items.size} 道", style = DesignTokens.Caption)
                        }
                    }
                    items(group.items, key = { it.id }) { mistake ->
                        MistakeItem(
                            mistake = mistake,
                            dateText = dateFormat.format(mistake.createdAt),
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

/** 创建相机临时文件并生成 FileProvider Uri（Android 11 需经 FileProvider 共享） */
private fun launchCamera(context: Context, onReady: (File, Uri) -> Unit) {
    val dir = MistakeImageStore.cameraTempDir(context)
    val file = File(dir, "capture_${System.currentTimeMillis()}.jpg")
    val uri = FileProvider.getUriForFile(context, "com.studykit.fileprovider", file)
    onReady(file, uri)
}

/** 错题条目：来源徽标 + 标题 + 摘要 + 缩略图（若有） */
@Composable
private fun MistakeItem(
    mistake: Mistake,
    dateText: String,
    thumbFile: File?,
    fullFile: File?,
    onClick: () -> Unit,
) {
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
                        Text(
                            text = "已掌握",
                            style = DesignTokens.Caption.copy(
                                color = DesignTokens.Success,
                                fontWeight = FontWeight.Medium,
                            ),
                        )
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
