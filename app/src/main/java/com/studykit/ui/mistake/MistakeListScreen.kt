package com.studykit.ui.mistake

import android.Manifest
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import com.studykit.ui.motion.MotionSpec
import com.studykit.ui.theme.AppTheme
import com.studykit.ui.theme.DesignTokens
import com.studykit.util.MistakeImageStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 来源徽标：单词=accent 族、刷题=success 族、拍照=warning 族，统一走「柔底药丸」。
 *
 * 旧写法是实色 12% 底 + **同色文字**（accent 文字 3.04:1、success ≈2.2:1，都不达 AA），
 * 按 ledger 规则换 soft 容器 + 达标墨色：accent 族有专用 `accentInk`；
 * success/warning 族的 `successInk/warningInk` 归 T15 令牌批次，先用 `primaryText`
 * （深墨在 10%/16% soft 底上两主题都达 AA，与 T10 `WordStatusIndicator`、T13 `StatusTag` 同批做法）。
 * 未知来源退回透明底 + secondaryText。
 */
@Composable
private fun SourceBadge(source: String) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    val (label, container, ink) = when (source) {
        Mistake.SOURCE_WORD -> Triple("单词", colors.accentSoft, colors.accentInk)
        Mistake.SOURCE_PRACTICE -> Triple("刷题", colors.successSoft, colors.primaryText)
        Mistake.SOURCE_PHOTO -> Triple("拍照", colors.warningSoft, colors.primaryText)
        else -> Triple(source, Color.Transparent, colors.secondaryText)
    }
    Box(
        modifier = Modifier
            .background(
                color = container,
                shape = RoundedCornerShape(DesignTokens.SpacingXs),
            )
            .padding(horizontal = DesignTokens.SpacingSm, vertical = 2.dp),
    ) {
        Text(
            text = label,
            style = texts.caption.copy(color = ink, fontWeight = FontWeight.Medium),
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

/**
 * 学科筛选 Chip：选中态 `accentSoft` 底 + `accentInk` 字。
 *
 * brief 写「文字 accent」，按 T1 裁定（accent 作文字浅色只有 3.04:1）落回 ink 变体；
 * 未选中仍是卡面底 + primaryText，两态底色/墨色用 `animateColorAsState` + `tween(FadeMs)`
 * 交叉补间（颜色不能用 `MotionSpec` 的 Float spring，映射表规定的 tween 分支）。
 */
@Composable
private fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    val container by animateColorAsState(
        targetValue = if (selected) colors.accentSoft else colors.card,
        animationSpec = tween(durationMillis = MotionSpec.FadeMs),
        label = "filterChipContainer",
    )
    val ink by animateColorAsState(
        targetValue = if (selected) colors.accentInk else colors.primaryText,
        animationSpec = tween(durationMillis = MotionSpec.FadeMs),
        label = "filterChipInk",
    )
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(DesignTokens.CornerRadius))
            .background(container)
            .clickable(onClick = onClick)
            .padding(horizontal = DesignTokens.SpacingMd, vertical = DesignTokens.SpacingSm),
    ) {
        Text(
            text = label,
            style = texts.aux.copy(
                color = ink,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            ),
        )
    }
}

/**
 * 错题列表页（错题 Tab）：学科筛选 + 按学科分组 + 来源徽标 + 图片缩略图 + 拍照录入入口。
 *
 * 颜色与文字样式统一取 [AppTheme]，间距/圆角仍走 [DesignTokens] 的 dp 常量（T15 才迁度量）。
 *
 * 列表动效：分组标题与错题条目都挂 `Modifier.animateItem()`（[androidx.compose.foundation.lazy.LazyItemScope]）——
 * 拍照录入回来的新错题淡入、删除/切学科时移除的条目淡出，其余条目用 spring 让位，
 * 不再出现「整列瞬间跳一格」。进出动画要求条目带 `key`，本页已有的两组键就是前提
 * （`"header_$subject"` 与 `mistake.id`）。
 * 注意：`viewModel.mistakes` 走的是 `observeAll()`，**掌握后的条目不会从列表消失**
 * （只打「已掌握」标签，仍可在详情里回看），所以「掌握」这条路径看不到退场；
 * 退场现在发生在删除与切学科筛选时。
 */
@Composable
fun MistakeListScreen(
    viewModel: MistakeViewModel,
    onOpenDetail: (Long) -> Unit,
    onOpenCapture: () -> Unit,
) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
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
    // 相机权限被拒后展示引导文案
    var showCameraRationale by remember { androidx.compose.runtime.mutableStateOf(false) }
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
            Text(text = "错题本", style = texts.largeTitle)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { cameraPermissionLauncher.launch(Manifest.permission.CAMERA) }) {
                Text(
                    text = "拍照录入",
                    // 落在页面底色上的 accent 系文字一律走 ink（浅色 accent 仅 3.04:1）
                    style = texts.aux.copy(color = colors.accentInk, fontWeight = FontWeight.SemiBold),
                )
            }
        }
        Text(
            text = "共 ${mistakes.size} 道错题",
            style = texts.caption,
        )

        if (showCameraRationale) {
            Spacer(Modifier.height(DesignTokens.SpacingSm))
            Text(
                text = "拍照录入需要相机权限。请在系统设置 → 应用 → StudyKit → 权限中允许「相机」，" +
                    "或再次点击右上角「拍照录入」重新发起授权。",
                style = texts.caption.copy(color = colors.warning),
            )
        }

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
                            modifier = Modifier.animateItem(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(DesignTokens.SpacingSm),
                        ) {
                            Text(
                                text = group.subject,
                                style = texts.pageTitle,
                            )
                            Text(text = "${group.items.size} 道", style = texts.caption)
                        }
                    }
                    items(group.items, key = { it.id }) { mistake ->
                        MistakeItem(
                            modifier = Modifier.animateItem(),
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

/**
 * 错题条目：来源徽标 + 标题 + 摘要 + 缩略图（若有）。
 *
 * `modifier` 由调用方（`LazyItemScope`）传入 `Modifier.animateItem()`，挂到卡片根，
 * 于是条目的进出与让位都走动画；缩略图按 brief「图片卡」口径统一为
 * `CornerRadiusLg` 圆角 + 1dp `divider` 描边——夜间卡面 `#26241F` 与照片暗部同亮度时，
 * 没有这条发丝线图片会直接糊进卡里。
 */
@Composable
private fun MistakeItem(
    mistake: Mistake,
    dateText: String,
    thumbFile: File?,
    fullFile: File?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    val thumbShape = RoundedCornerShape(DesignTokens.CornerRadiusLg)
    AppCard(
        modifier = modifier
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
                    Text(text = dateText, style = texts.caption)
                    if (mistake.mastered) {
                        Text(
                            text = "已掌握",
                            // success 作文字浅色 ≈2.2:1，successInk 归 T15 令牌批次（沿用既有做法）
                            style = texts.caption.copy(
                                color = colors.success,
                                fontWeight = FontWeight.Medium,
                            ),
                        )
                    }
                }
                Spacer(Modifier.height(DesignTokens.SpacingSm))
                Text(
                    text = mistake.title,
                    style = texts.cardTitle,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (mistake.content.isNotBlank()) {
                    Spacer(Modifier.height(DesignTokens.SpacingXs))
                    Text(
                        text = mistake.content.replace("\n", " "),
                        style = texts.caption,
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
                        .clip(thumbShape)
                        .border(width = 1.dp, color = colors.divider, shape = thumbShape),
                )
            }
        }
    }
}
