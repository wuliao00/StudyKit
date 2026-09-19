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
 * 掌握态筛选 Chip 行：`待复习` / `已掌握`。
 *
 * 它是 spec「标记已掌握后划线消失」的另一半：默认列表只出未掌握，`markMastered` 后那行
 * 当场退场（[androidx.compose.foundation.lazy.LazyItemScope.animateItem] 播退场），
 * 但没有这枚 chip 用户就再也回不到已划掉的题上 —— 复习入口会断。
 * 与学科行是**两个正交维度**（掌握态 × 学科），故各占一行、共用同一枚 [FilterChip] 样式；
 * 学科 chips 的候选集取自两态并集（见 `MistakeViewModel.subjects`），切这一行不会让学科行重排。
 */
@Composable
private fun MasteryFilterRow(
    showMastered: Boolean,
    onSelect: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(DesignTokens.SpacingSm),
    ) {
        FilterChip(label = "待复习", selected = !showMastered) { onSelect(false) }
        FilterChip(label = "已掌握", selected = showMastered) { onSelect(true) }
    }
}

/**
 * 筛选 Chip（学科行与掌握态行共用）：选中态 `accentSoft` 底 + `accentInk` 字。
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
 * 错题列表页（错题 Tab）：掌握态筛选 + 学科筛选 + 按学科分组 + 来源徽标 + 图片缩略图 + 拍照录入入口。
 *
 * 颜色与文字样式统一取 [AppTheme]，间距/圆角仍走 [DesignTokens] 的 dp 常量（T15 才迁度量）。
 *
 * 列表动效：分组标题与错题条目都挂 `Modifier.animateItem()`（[androidx.compose.foundation.lazy.LazyItemScope]）——
 * 拍照录入回来的新错题淡入、删除/标记掌握/切筛选时移除的条目淡出，其余条目用 spring 让位，
 * 不再出现「整列瞬间跳一格」。进出动画要求条目带 `key`，本页已有的两组键就是前提
 * （`"header_$subject"` 与 `mistake.id`）。
 * 数据源按 spec「标记已掌握后划线消失」取**未掌握**（`viewModel.mistakes`），
 * 于是「掌握」这条路径也有退场；已掌握清单走页顶「已掌握」chip（[MasteryFilterRow]），
 * 复习入口不断。切 chip 时整列换源：旧的一批按 key 退场、新的一批进场，
 * 学科 chips 的候选集不随 chip 变（取两态并集），避免学科行跟着抖动。
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
    val showMastered by viewModel.showMastered.collectAsStateWithLifecycle()
    val hasAnyMistake by viewModel.hasAnyMistake.collectAsStateWithLifecycle()
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
            // 列表按掌握态分侧，计数文案跟着说明「这一列是哪一侧」
            text = if (showMastered) "已掌握 ${mistakes.size} 道" else "共 ${mistakes.size} 道错题",
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
        MasteryFilterRow(showMastered = showMastered, onSelect = viewModel::selectShowMastered)

        Spacer(Modifier.height(DesignTokens.SpacingSm))
        SubjectFilterRow(subjects = subjects, selected = selected, onSelect = viewModel::selectSubject)

        Spacer(Modifier.height(DesignTokens.SpacingMd))
        if (groups.isEmpty()) {
            // 四种子空态各说各话：整库空 / 学科筛选筛空 / 待复习被清空 / 已掌握侧还没题。
            // 「被学科筛空」必须先判：`groups` 空有两种成因，只有当前侧本身就空才是掌握态造成的。
            val emptyTitle = when {
                !hasAnyMistake -> "错题本还是空的"
                mistakes.isNotEmpty() -> "该学科下暂无错题"
                !showMastered -> "全部已掌握"
                else -> "还没有已掌握的错题"
            }
            val emptyCaption = when {
                !hasAnyMistake || mistakes.isNotEmpty() ->
                    "去「题库练习」答题自动收录，或点右上角「拍照录入」"
                !showMastered -> "上面的题都划掉了，切到上方「已掌握」可以回看"
                else -> "在错题详情里点「标记掌握」，题目就会挪到这里"
            }
            Spacer(Modifier.height(DesignTokens.SpacingXl * 2))
            EmptyState(
                title = emptyTitle,
                caption = emptyCaption,
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
 * 行内的「已掌握」小票**已删**：信息已由页顶掌握态 chip 承担（「待复习」一侧不可能有掌握项，
 * 「已掌握」一侧整列都是），留着只是同义反复；保留的是页头的计数文案。
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
