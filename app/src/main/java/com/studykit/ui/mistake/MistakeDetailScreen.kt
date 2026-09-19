package com.studykit.ui.mistake

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
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
import com.studykit.ui.motion.MotionSpec
import com.studykit.ui.theme.AppTheme
import kotlinx.coroutines.delay
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
 *
 * 颜色与文字样式统一取 [AppTheme]，间距/圆角取 `AppTheme.space` / `AppTheme.radius` 的 dp 常量；
 * 「标记掌握 / 已掌握 / 删除」这类**文字**走 `successInk/warningInk`（品牌色作字在浅色主题不达 AA）。
 *
 * 「标记掌握」是本页唯一的手势动效：点击后按钮内文字用 `MotionSpec.press` **放大回弹**，
 * 过了放大峰值（[MotionSpec.FadeMs] 后）才 `popBackStack` —— 立刻返回会把这一帧吃掉，
 * 用户看到的只是「按钮自己消失了」。`markMastered` 让 `current.mastered` 当场翻 true，
 * 所以按钮的可见条件是「未掌握 **或** 回弹进行中」，否则动画的第一帧就被状态变更抹掉。
 * 该门控用 `rememberSaveable`：转屏后既不重播弹跳、也不会把已按下的按钮复活。
 * 返回是**一次性**的（`leaveOnce`）：本页三条返回路径（两处返回箭头、删除、弹跳到期）共用同一道门，
 * 免得「手动返回 + 弹跳到期」在 280ms popExit 窗口里叠成两次 pop。
 * 另：`markMastered` 之后该题从列表默认的「待复习」列里消失（spec 划线消失，由列表侧
 * `animateItem()` 播退场），已掌握清单改由列表页顶的「已掌握」chip 进入。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MistakeDetailScreen(
    viewModel: MistakeViewModel,
    onBack: () -> Unit,
) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    val mistake by viewModel.detail.collectAsStateWithLifecycle()
    var showFullImage by remember { mutableStateOf(false) }
    var showSubjectDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    // 回弹进行中：true 之后按钮要继续留在屏幕上，否则动画第一帧就被 mastered 状态变更抹掉
    var masteredBounce by rememberSaveable { mutableStateOf(false) }
    // 本页只允许返回一次：弹跳的 220ms 里用户可能已经手动返回，而该 entry 在 280ms popExit
    // 期间仍在组合，`LaunchedEffect` 会再 pop 一次 —— 于是多弹一层，直接落到「学习」页。
    // 门控用 rememberSaveable：转屏重建组合后也不给第二次 pop 开门（`AppNav` 那头还有一道
    // 「entry 不是栈顶就不 pop」的兜底，系统返回键绕过这里时同样不会多弹）。
    var returned by rememberSaveable { mutableStateOf(false) }
    val leaveOnce = {
        if (!returned) {
            returned = true
            onBack()
        }
    }
    val masteredScale by animateFloatAsState(
        targetValue = if (masteredBounce) 1.16f else 1f,
        animationSpec = MotionSpec.press,
        label = "masteredBounce",
    )
    LaunchedEffect(masteredBounce) {
        if (masteredBounce) {
            // `press`（ζ=0.55, k=420）的首个峰值在 π/ωd ≈ 183ms，220ms 已越过峰值开始回落，
            // 这一帧交接给返回转场正好读得出「按下去 → 弹回来」
            delay(MotionSpec.FadeMs.toLong())
            leaveOnce()
        }
    }
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
    val reviewFormat = remember { SimpleDateFormat("MM月dd日", Locale.getDefault()) }

    val current = mistake
    if (current == null) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = AppTheme.space.pageH),
        ) {
            Spacer(Modifier.height(AppTheme.space.sm))
            IconButton(onClick = { leaveOnce() }) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    // 图标落在页面底色上，按 T1 裁定走 accentInk（accent 仅 3.04:1）
                    tint = colors.accentInk,
                )
            }
            Text(text = "错题不存在或已删除", style = texts.caption)
        }
        return
    }

    val imageFile = current.imagePath?.let { viewModel.resolveImage(it) }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = AppTheme.space.pageH),
        ) {
            Spacer(Modifier.height(AppTheme.space.sm))
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { leaveOnce() }) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        // 图标落在页面底色上，按 T1 裁定走 accentInk（accent 仅 3.04:1）
                        tint = colors.accentInk,
                    )
                }
                Spacer(Modifier.weight(1f))
                // 未掌握、或回弹正在进行时都保留按钮：后者是「点击 → 放大回弹 → 返回」的可见前提
                if (!current.mastered || masteredBounce) {
                    TextButton(
                        onClick = {
                            if (!masteredBounce) {
                                viewModel.markMastered(current.id)
                                masteredBounce = true
                            }
                        },
                    ) {
                        Text(
                            text = "标记掌握",
                            style = texts.aux.copy(
                                color = colors.successInk,
                                fontWeight = FontWeight.Medium,
                            ),
                            // 缩放只走绘制层，不反过来把 TopBar 撑高
                            modifier = Modifier.graphicsLayer {
                                scaleX = masteredScale
                                scaleY = masteredScale
                            },
                        )
                    }
                }
            }

            Text(text = current.title, style = texts.pageTitle)
            Spacer(Modifier.height(AppTheme.space.xs))
            Text(
                text = "${current.subject} · ${sourceLabel(current.source)} · ${dateFormat.format(current.createdAt)}",
                style = texts.caption,
            )
            // 回弹那一瞬按钮还在，两个标签同帧会互相抢读，故等动画交接完再显示
            if (current.mastered && !masteredBounce) {
                Spacer(Modifier.height(AppTheme.space.xs))
                Text(
                    text = "已掌握",
                    // success 作文字浅色只有 2.22:1，走 T15 墨水批次的 successInk（白卡 5.39:1）
                    style = texts.caption.copy(
                        color = colors.successInk,
                        fontWeight = FontWeight.Medium,
                    ),
                )
            }

            // ── 大图 ──────────────────────────────────────────────────────
            if (imageFile != null && imageFile.exists()) {
                Spacer(Modifier.height(AppTheme.space.md))
                AsyncImage(
                    model = imageFile,
                    contentDescription = current.title,
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(AppTheme.radius.lg))
                        // 图片卡：1dp divider 发丝描边，夜间卡面与照片暗部分层
                        .border(
                            width = 1.dp,
                            color = colors.divider,
                            shape = RoundedCornerShape(AppTheme.radius.lg),
                        )
                        .clickable { showFullImage = true },
                )
            }

            // ── 内容 / 备注 ──────────────────────────────────────────────
            if (current.content.isNotBlank()) {
                Spacer(Modifier.height(AppTheme.space.md))
                AppCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "题目内容",
                        style = texts.caption.copy(fontWeight = FontWeight.Medium),
                    )
                    Spacer(Modifier.height(AppTheme.space.xs))
                    Text(text = current.content, style = texts.body)
                }
            }
            if (current.note.isNotBlank()) {
                Spacer(Modifier.height(AppTheme.space.md))
                AppCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "备注",
                        style = texts.caption.copy(fontWeight = FontWeight.Medium),
                    )
                    Spacer(Modifier.height(AppTheme.space.xs))
                    Text(text = current.note, style = texts.body)
                }
            }

            // ── 复习时间 ──────────────────────────────────────────────────
            Spacer(Modifier.height(AppTheme.space.lg))
            Text(text = "复习提醒", style = texts.cardTitle)
            Spacer(Modifier.height(AppTheme.space.xs))
            Text(
                text = current.reviewAt?.let { "已设置：${reviewFormat.format(it)}" } ?: "尚未设置复习时间",
                style = texts.caption,
            )
            Spacer(Modifier.height(AppTheme.space.md))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(AppTheme.space.sm),
                verticalArrangement = Arrangement.spacedBy(AppTheme.space.sm),
            ) {
                ReviewOption("明天") { viewModel.setReviewAt(current.id, dayOffset(1)) }
                ReviewOption("三天后") { viewModel.setReviewAt(current.id, dayOffset(3)) }
                ReviewOption("一周后") { viewModel.setReviewAt(current.id, dayOffset(7)) }
            }

            // ── 学科归类 ──────────────────────────────────────────────────
            Spacer(Modifier.height(AppTheme.space.lg))
            AppButton(text = "编辑学科归类", secondary = true, onClick = { showSubjectDialog = true })

            Spacer(Modifier.height(AppTheme.space.md))
            AppButton(
                text = "删除错题",
                secondary = true,
                onClick = { showDeleteDialog = true },
            )
            Spacer(Modifier.height(AppTheme.space.xl))
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
            title = { Text(text = "删除错题", style = texts.cardTitle) },
            text = { Text(text = "删除后不可恢复，相关图片也会一并清理。", style = texts.aux) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    viewModel.delete(current.id) { leaveOnce() }
                }) {
                    Text(text = "删除", color = colors.warningInk)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(text = "取消", color = colors.accentInk)
                }
            },
        )
    }
}

/**
 * 复习时间快捷项：`accentSoft` 底 + `accentInk` 字的柔底药丸。
 *
 * 旧写法是 `accent` 10% 底 + `accent` 文字（浅色下 3.04:1 不达 AA），按 ledger 的
 * 「soft pill → accentSoft + accentInk」口径换墨色；容器仍是 `clip → background(color)`
 * 那一档可点药丸写法（`clip` 在前才把按压 ripple 裁成圆角）。
 */
@Composable
private fun ReviewOption(label: String, onClick: () -> Unit) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(AppTheme.radius.md))
            .background(colors.accentSoft)
            .clickable(onClick = onClick)
            .padding(horizontal = AppTheme.space.md, vertical = AppTheme.space.sm),
    ) {
        Text(
            text = label,
            style = texts.aux.copy(color = colors.accentInk, fontWeight = FontWeight.Medium),
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

/**
 * 全屏看图：黑底占满，点击关闭。
 *
 * 底色**刻意不用** `colors.background`：这是照片灯箱，纯黑是取景框（两张照片对比时不受页面底色
 * 偏色影响），且夜间主题的暖纸底色会把白底题目照片糊成一片。它不随主题变（两主题同为 `#000000`），
 * 但仍是设计意图而非临时值，故 T15 收进 `colors.lightbox` 令牌 —— 页面里不再留任何硬编码色值；
 * 允许写死色值的只有两处：`ui/theme/Palette.kt`（色板本体）与 `ui/book/BookSpine.kt`（书脊身份色，见其 KDoc）。
 */
@Composable
private fun FullImageOverlay(file: File, onDismiss: () -> Unit) {
    val colors = AppTheme.colors
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.lightbox)
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
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "编辑学科归类", style = texts.cardTitle) },
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
                Text(text = "保存", color = colors.accentInk)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "取消", color = colors.secondaryText)
            }
        },
    )
}
