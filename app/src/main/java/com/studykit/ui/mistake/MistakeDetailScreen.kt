package com.studykit.ui.mistake

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
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.studykit.data.entity.MistakeReview
import com.studykit.srs.Confidence
import com.studykit.ui.components.AppButton
import com.studykit.ui.components.AppCard
import com.studykit.ui.components.AppMultilineTextField
import com.studykit.ui.components.AppTextField
import com.studykit.ui.components.TipCard
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
 * 错题详情页：默认重做式复习（先遮住答案与解析，自己走一遍，再对照），
 * 重做结果写进 review 记录并由算法决定下次复习时间与是否转掌握；
 * 另含错因归因、钉住某天、变体标记与重做历史。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MistakeDetailScreen(
    viewModel: MistakeViewModel,
    onBack: () -> Unit,
) {
    val mistake by viewModel.detail.collectAsStateWithLifecycle()
    val redo by viewModel.redo.collectAsStateWithLifecycle()
    val reviews by viewModel.reviews.collectAsStateWithLifecycle()
    var showFullImage by remember { mutableStateOf(false) }
    var showSubjectDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var variantSurface by rememberSaveable { mutableStateOf("") }
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
    val dayFormat = remember { SimpleDateFormat("MM月dd日", Locale.getDefault()) }

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
    val now = System.currentTimeMillis()
    val retention = MistakeReviewLogic.predictedRetention(current, now)
    val overdue = MistakeReviewLogic.overdueDays(current, now)

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
                Text(
                    text = if (current.mastered) "已掌握" else "待复习",
                    style = DesignTokens.Caption.copy(
                        color = if (current.mastered) DesignTokens.Success else DesignTokens.Warning,
                        fontWeight = FontWeight.Medium,
                    ),
                )
            }

            Text(text = current.title, style = DesignTokens.PageTitle)
            Spacer(Modifier.height(DesignTokens.SpacingXs))
            Text(
                text = "${current.subject} · ${sourceLabel(current.source)} · ${dateFormat.format(current.createdAt)}",
                style = DesignTokens.Caption,
            )
            Spacer(Modifier.height(DesignTokens.SpacingSm))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(DesignTokens.SpacingSm),
                verticalArrangement = Arrangement.spacedBy(DesignTokens.SpacingXs),
            ) {
                Text(
                    text = retention?.let { "预测保留 ${MistakeReviewLogic.retentionPercent(it)}%" }
                        ?: "尚未进入调度",
                    style = DesignTokens.Caption,
                )
                if (overdue > 0) {
                    Text(text = "欠账 $overdue 天", style = DesignTokens.Caption.copy(color = DesignTokens.Warning))
                }
                Text(text = "连对 ${current.correctStreak} 次", style = DesignTokens.Caption)
                if (current.highConfidenceError) {
                    Text(
                        text = "高置信答错",
                        style = DesignTokens.Caption.copy(color = DesignTokens.Warning, fontWeight = FontWeight.Medium),
                    )
                }
                if (current.pinned) {
                    Text(text = "复习日已钉住", style = DesignTokens.Caption.copy(color = DesignTokens.Accent))
                }
                Text(
                    text = if (current.cause.isBlank()) "未归因" else "错因：${current.cause}",
                    style = DesignTokens.Caption,
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

            // ── 重做式复习 ────────────────────────────────────────────────
            Spacer(Modifier.height(DesignTokens.SpacingLg))
            RedoCard(
                mistake = current,
                redo = redo,
                onMode = viewModel::setRedoMode,
                onConfidence = viewModel::selectRedoConfidence,
                onHint = viewModel::revealRedoHint,
                onOwnSolution = viewModel::setOwnSolution,
                onSubmit = viewModel::submitRedo,
                onRecord = viewModel::recordRedoResult,
                onRestart = viewModel::restartRedo,
                dayFormat = dayFormat,
            )

            // ── 错因归因 ──────────────────────────────────────────────────
            Spacer(Modifier.height(DesignTokens.SpacingLg))
            CauseCard(
                redo = redo,
                onPick = viewModel::pickCause,
                onSave = viewModel::saveCause,
            )

            // ── 排期 ──────────────────────────────────────────────────────
            Spacer(Modifier.height(DesignTokens.SpacingLg))
            ScheduleCard(
                mistake = current,
                reviewText = current.reviewAt?.let { dayFormat.format(it) },
                onPin = { days -> viewModel.pinReviewAt(current.id, dayOffset(days)) },
                onRelease = { viewModel.releasePin(current.id) },
            )

            // ── 变体重练 ──────────────────────────────────────────────────
            Spacer(Modifier.height(DesignTokens.SpacingLg))
            VariantCard(
                surface = variantSurface,
                markedCount = MistakeReviewLogic.variantCount(current.note),
                onSurfaceChange = { variantSurface = it },
                onMark = {
                    viewModel.markVariant(variantSurface)
                    variantSurface = ""
                },
            )

            // ── 重做历史 ──────────────────────────────────────────────────
            if (reviews.isNotEmpty()) {
                Spacer(Modifier.height(DesignTokens.SpacingLg))
                Text(text = "重做记录", style = DesignTokens.CardTitle)
                Spacer(Modifier.height(DesignTokens.SpacingSm))
                reviews.forEach { review ->
                    ReviewRow(review = review, dateText = dateFormat.format(review.reviewedAt))
                    Spacer(Modifier.height(DesignTokens.SpacingSm))
                }
            }

            // ── 归类与删除 ────────────────────────────────────────────────
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
            text = { Text(text = "删除后不可恢复，相关图片与重做记录也会一并清理。", style = DesignTokens.Auxiliary) },
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

/**
 * 重做卡：默认 REDO —— 遮住答案与解析，先自己走一遍（可写下自己的解法），
 * 提交之后才展开完整内容，再对照自评「做对了 / 没做对」。
 */
@Composable
private fun RedoCard(
    mistake: Mistake,
    redo: RedoUiState,
    onMode: (MistakeMastery.ReviewMode) -> Unit,
    onConfidence: (Confidence) -> Unit,
    onHint: () -> Unit,
    onOwnSolution: (String) -> Unit,
    onSubmit: () -> Unit,
    onRecord: (Boolean) -> Unit,
    onRestart: () -> Unit,
    dayFormat: SimpleDateFormat,
) {
    val locked = MistakeReviewLogic.solutionIsHidden(redo.mode, redo.attempted)
    val outcome = redo.outcome

    AppCard(modifier = Modifier.fillMaxWidth()) {
        Text(text = "复习方式", style = DesignTokens.Caption.copy(fontWeight = FontWeight.Medium))
        Spacer(Modifier.height(DesignTokens.SpacingSm))
        Row(horizontalArrangement = Arrangement.spacedBy(DesignTokens.SpacingSm)) {
            Chip(
                text = "重做（推荐）",
                color = DesignTokens.Accent,
                selected = redo.mode == MistakeMastery.ReviewMode.REDO,
                interactive = outcome == null,
                modifier = Modifier.weight(1f),
                onClick = { onMode(MistakeMastery.ReviewMode.REDO) },
            )
            Chip(
                text = "看解析",
                color = DesignTokens.SecondaryText,
                selected = redo.mode == MistakeMastery.ReviewMode.SOLUTION,
                interactive = outcome == null,
                modifier = Modifier.weight(1f),
                onClick = { onMode(MistakeMastery.ReviewMode.SOLUTION) },
            )
        }
        Spacer(Modifier.height(DesignTokens.SpacingSm))
        Text(text = MistakeReviewLogic.modeCaption(redo.mode), style = DesignTokens.Caption)

        // ── 自评信心：没有把握度就没有超纠正信号 ──────────────────────────
        Spacer(Modifier.height(DesignTokens.SpacingMd))
        Text(
            text = if (redo.attempted) "本次自评：${confidenceLabel(redo.confidence)}" else "先自评把握，再动手做",
            style = DesignTokens.Caption,
        )
        Spacer(Modifier.height(DesignTokens.SpacingSm))
        Row(horizontalArrangement = Arrangement.spacedBy(DesignTokens.SpacingSm)) {
            Confidence.entries.forEach { level ->
                Chip(
                    text = confidenceLabel(level),
                    color = DesignTokens.Accent,
                    selected = redo.confidence == level,
                    interactive = !redo.attempted,
                    modifier = Modifier.weight(1f),
                    onClick = { onConfidence(level) },
                )
            }
        }

        // ── 挤牙膏提示：三档，档档都不给最终答案 ──────────────────────────
        Spacer(Modifier.height(DesignTokens.SpacingMd))
        MistakeReviewLogic.revealedHints(redo.hintLevel).forEach { hint ->
            Text(
                text = "第 ${hint.level} 档｜${hint.label}",
                style = DesignTokens.Auxiliary.copy(color = DesignTokens.SecondaryText),
                modifier = Modifier.padding(bottom = DesignTokens.SpacingXs),
            )
        }
        AppButton(
            text = if (MistakeReviewLogic.canRevealMoreHints(redo.hintLevel)) {
                "挤一档提示（已用 ${redo.hintLevel} / ${MistakeMastery.HINT_LEVELS}）"
            } else {
                "提示已经挤到第三档"
            },
            secondary = true,
            enabled = MistakeReviewLogic.canRevealMoreHints(redo.hintLevel),
            onClick = onHint,
        )

        // ── 题目内容：重做未提交时遮掉答案与解析行 ────────────────────────
        Spacer(Modifier.height(DesignTokens.SpacingMd))
        Text(text = "题目内容", style = DesignTokens.Caption.copy(fontWeight = FontWeight.Medium))
        Spacer(Modifier.height(DesignTokens.SpacingXs))
        Text(
            text = MistakeReviewLogic.visibleContent(redo.mode, redo.attempted, mistake.content),
            style = DesignTokens.Body,
        )
        if (locked) {
            Spacer(Modifier.height(DesignTokens.SpacingSm))
            Text(
                text = "答案与解析已经遮住。写下你自己的解法，再提交对照。",
                style = DesignTokens.Caption.copy(color = DesignTokens.Accent),
            )
        }

        // ── 自己的解法 ────────────────────────────────────────────────────
        if (!redo.attempted) {
            Spacer(Modifier.height(DesignTokens.SpacingMd))
            AppMultilineTextField(
                value = redo.ownSolution,
                onValueChange = onOwnSolution,
                label = "我的解法（可留空，但写下来效果更好）",
                placeholder = "用自己的话写思路、列式或关键词…",
                minLines = 3,
            )
            Spacer(Modifier.height(DesignTokens.SpacingMd))
            AppButton(
                text = "提交重做，展开解析",
                enabled = MistakeReviewLogic.canSubmitRedo(redo.confidence),
                onClick = onSubmit,
            )
        }

        // ── 对照之后自评结果 ──────────────────────────────────────────────
        if (redo.attempted && outcome == null) {
            Spacer(Modifier.height(DesignTokens.SpacingMd))
            Text(
                text = "对照解析再评，别凭感觉：这次算是做对了吗？",
                style = DesignTokens.Caption,
            )
            Spacer(Modifier.height(DesignTokens.SpacingSm))
            Row(horizontalArrangement = Arrangement.spacedBy(DesignTokens.SpacingSm)) {
                Chip(
                    text = "做对了",
                    color = DesignTokens.Success,
                    selected = false,
                    interactive = true,
                    modifier = Modifier.weight(1f),
                    onClick = { onRecord(true) },
                )
                Chip(
                    text = "没做对",
                    color = DesignTokens.Warning,
                    selected = false,
                    interactive = true,
                    modifier = Modifier.weight(1f),
                    onClick = { onRecord(false) },
                )
            }
        }

        // ── 本次结果与算法排期 ────────────────────────────────────────────
        if (outcome != null) {
            Spacer(Modifier.height(DesignTokens.SpacingMd))
            OutcomeBlock(outcome = outcome, dayFormat = dayFormat)
            Spacer(Modifier.height(DesignTokens.SpacingMd))
            AppButton(text = "再做一次", secondary = true, onClick = onRestart)
        }
    }
}

/** 重做结果：连对次数、跨间隔天数、是否转掌握、下次复习日 */
@Composable
private fun OutcomeBlock(outcome: RedoOutcome, dayFormat: SimpleDateFormat) {
    Column {
        Text(
            text = if (outcome.correct) "这次记为做对了" else "这次记为没做对",
            style = DesignTokens.CardTitle.copy(
                color = if (outcome.correct) DesignTokens.Success else DesignTokens.Warning,
            ),
        )
        Spacer(Modifier.height(DesignTokens.SpacingSm))
        Text(
            text = "连对 ${outcome.streak} 次，距上次重做隔了 ${outcome.gapDays} 天。",
            style = DesignTokens.Auxiliary,
        )
        Text(
            text = if (outcome.mastered) {
                "已达跨间隔连对 ${MistakeMastery.REQUIRED_CONSECUTIVE_CORRECT} 次的门槛，这道题退出待复习队列。"
            } else {
                "掌握要看跨间隔：同一天连对不算，需累计到 ${MistakeMastery.REQUIRED_CONSECUTIVE_CORRECT} 次且间隔大于 0 天。"
            },
            style = DesignTokens.Caption,
        )
        Spacer(Modifier.height(DesignTokens.SpacingSm))
        Text(
            text = if (outcome.keptPinned) {
                "复习日仍是你钉住的 ${dayFormat.format(outcome.reviewAt)}，算法没有改动它。"
            } else {
                "下次复习排在 ${dayFormat.format(outcome.reviewAt)}（间隔 ${outcome.intervalDays} 天）。"
            },
            style = DesignTokens.Caption,
        )
        outcome.tip?.let { tip ->
            Spacer(Modifier.height(DesignTokens.SpacingSm))
            TipCard(tip = tip, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** 错因归因：四选一标签 + 对应的自我解释提示 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CauseCard(
    redo: RedoUiState,
    onPick: (String) -> Unit,
    onSave: () -> Unit,
) {
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Text(text = "错因归因", style = DesignTokens.CardTitle)
        Spacer(Modifier.height(DesignTokens.SpacingXs))
        Text(
            text = "选一个最像的原因，再照着下面这句问自己一遍。",
            style = DesignTokens.Caption,
        )
        Spacer(Modifier.height(DesignTokens.SpacingMd))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(DesignTokens.SpacingSm),
            verticalArrangement = Arrangement.spacedBy(DesignTokens.SpacingSm),
        ) {
            MistakeMastery.CAUSES.forEach { cause ->
                Chip(
                    text = cause.label,
                    color = DesignTokens.Accent,
                    selected = redo.causeChoice == cause.label,
                    interactive = true,
                    onClick = { onPick(cause.label) },
                )
            }
        }
        MistakeReviewLogic.selfExplainPrompt(redo.causeChoice)?.let { prompt ->
            Spacer(Modifier.height(DesignTokens.SpacingMd))
            Text(text = prompt, style = DesignTokens.Auxiliary)
        }
        Spacer(Modifier.height(DesignTokens.SpacingMd))
        AppButton(
            text = "保存错因",
            secondary = true,
            enabled = redo.causeChoice.isNotBlank(),
            onClick = onSave,
        )
    }
}

/** 排期：默认交给算法，也可以钉住某一天 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScheduleCard(
    mistake: Mistake,
    reviewText: String?,
    onPin: (Int) -> Unit,
    onRelease: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(text = "复习排期", style = DesignTokens.CardTitle)
        Spacer(Modifier.height(DesignTokens.SpacingXs))
        Text(
            text = reviewText?.let {
                if (mistake.pinned) "已钉住：$it（算法不会改动）" else "算法安排：$it"
            } ?: "尚未安排复习时间",
            style = DesignTokens.Caption,
        )
        Spacer(Modifier.height(DesignTokens.SpacingMd))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(DesignTokens.SpacingSm),
            verticalArrangement = Arrangement.spacedBy(DesignTokens.SpacingSm),
        ) {
            ReviewOption("钉住明天") { onPin(1) }
            ReviewOption("钉住三天后") { onPin(3) }
            ReviewOption("钉住一周后") { onPin(7) }
        }
        Spacer(Modifier.height(DesignTokens.SpacingSm))
        if (mistake.pinned) {
            AppButton(text = "交还给自动排期", secondary = true, onClick = onRelease)
        } else {
            Text(
                text = "默认由算法按记忆状态与欠账程度排期；钉住某天之后它就只在你选的那天提醒你。",
                style = DesignTokens.Caption,
            )
        }
    }
}

/** 变体练习：考点不变、外壳换成你自己写的另一情境 */
@Composable
private fun VariantCard(
    surface: String,
    markedCount: Int,
    onSurfaceChange: (String) -> Unit,
    onMark: () -> Unit,
) {
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Text(text = "换个外壳重练", style = DesignTokens.CardTitle)
        Spacer(Modifier.height(DesignTokens.SpacingXs))
        Text(
            text = "同一个考点换一种情境再做，比反复做同一道题更能把方法迁走。" +
                "新情境由你自己写，不做自动生成。",
            style = DesignTokens.Caption,
        )
        Spacer(Modifier.height(DesignTokens.SpacingMd))
        AppTextField(
            value = surface,
            onValueChange = onSurfaceChange,
            label = "新情境",
            placeholder = "如：把水平面上的匀加速换成斜面下滑",
        )
        Spacer(Modifier.height(DesignTokens.SpacingMd))
        AppButton(text = "标记变体重练", secondary = true, enabled = surface.isNotBlank(), onClick = onMark)
        if (markedCount > 0) {
            Spacer(Modifier.height(DesignTokens.SpacingSm))
            Text(text = "已标记 $markedCount 次变体重练", style = DesignTokens.Caption)
        }
    }
}

/** 一条重做记录 */
@Composable
private fun ReviewRow(review: MistakeReview, dateText: String) {
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (review.correct) "做对" else "没做对",
                style = DesignTokens.CardTitle.copy(
                    color = if (review.correct) DesignTokens.Success else DesignTokens.Warning,
                ),
            )
            Spacer(Modifier.width(DesignTokens.SpacingMd))
            Text(text = dateText, style = DesignTokens.Caption)
        }
        Spacer(Modifier.height(DesignTokens.SpacingXs))
        Text(
            text = "自评 ${confidenceLabel(Confidence.entries.getOrNull(review.confidence))} · " +
                "提示 ${review.hintLevel} 档 · 间隔 ${review.gapDays} 天",
            style = DesignTokens.Caption,
        )
    }
}

/** 信心三档中文标签 */
private fun confidenceLabel(confidence: Confidence?): String = when (confidence) {
    Confidence.GUESS -> "瞎猜"
    Confidence.VAGUE -> "有点印象"
    Confidence.SURE -> "非常确定"
    null -> "未自评"
}

/** 描边选择块：模式、信心、错因共用 */
@Composable
private fun Chip(
    text: String,
    color: Color,
    selected: Boolean,
    interactive: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(DesignTokens.CornerRadius))
            .background(
                when {
                    selected -> color.copy(alpha = 0.12f)
                    else -> DesignTokens.Card
                },
            )
            .borderOf(selected, color)
            .clickable(enabled = interactive, onClick = onClick)
            .padding(horizontal = DesignTokens.SpacingMd, vertical = DesignTokens.SpacingSm),
    ) {
        Text(
            text = text,
            style = DesignTokens.Auxiliary.copy(
                color = when {
                    selected -> color
                    !interactive -> DesignTokens.Divider
                    else -> DesignTokens.PrimaryText
                },
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            ),
        )
    }
}

private fun Modifier.borderOf(selected: Boolean, color: Color): Modifier =
    border(
        width = if (selected) 1.5.dp else 1.dp,
        color = if (selected) color else DesignTokens.Divider,
        shape = RoundedCornerShape(DesignTokens.CornerRadius),
    )

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
