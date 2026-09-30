package com.studykit.ui.book

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.studykit.data.entity.BookRecall
import com.studykit.data.entity.BookReview
import com.studykit.data.entity.Excerpt
import com.studykit.tips.StudyTips
import com.studykit.tips.TipEvent
import com.studykit.ui.components.AppButton
import com.studykit.ui.components.AppCard
import com.studykit.ui.components.AppMultilineTextField
import com.studykit.ui.components.SectionHeader
import com.studykit.ui.components.TipCard
import com.studykit.ui.theme.DesignTokens
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 页码步进按钮：-10 / +10（宽度由调用方在 Row 中通过 weight 分配） */
@Composable
private fun StepButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .height(44.dp)
            .clip(RoundedCornerShape(DesignTokens.CornerRadius))
            .background(DesignTokens.Card)
            .border(1.dp, DesignTokens.Divider, RoundedCornerShape(DesignTokens.CornerRadius))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = DesignTokens.Auxiliary.copy(
                color = DesignTokens.Accent,
                fontWeight = FontWeight.SemiBold,
            ),
        )
    }
}

/** 自评三档 / 模板选择通用小块：选中=强调色描边，禁用=半透明 */
@Composable
private fun ChoiceChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Box(
        modifier = modifier
            .height(40.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .clip(RoundedCornerShape(DesignTokens.CornerRadius))
            .background(if (selected) DesignTokens.Accent.copy(alpha = 0.10f) else DesignTokens.Card)
            .border(
                width = 1.dp,
                color = if (selected) DesignTokens.Accent else DesignTokens.Divider,
                shape = RoundedCornerShape(DesignTokens.CornerRadius),
            )
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = DesignTokens.Auxiliary.copy(
                color = if (selected) DesignTokens.Accent else DesignTokens.PrimaryText,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            ),
            maxLines = 1,
        )
    }
}

/** 评分星行（只读展示） */
@Composable
fun RatingStars(rating: Int, modifier: Modifier = Modifier, starSize: androidx.compose.ui.unit.Dp = 16.dp) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        repeat(5) { index ->
            Icon(
                imageVector = Icons.Filled.Star,
                contentDescription = null,
                tint = if (index < rating) DesignTokens.Accent else DesignTokens.Divider,
                modifier = Modifier.size(starSize),
            )
        }
    }
}

/** 自评三档按钮行：没写完回忆时整行禁用（先答后评） */
@Composable
private fun SelfScoreRow(
    enabled: Boolean,
    onScore: (Int) -> Unit,
    modifier: Modifier = Modifier,
    prompt: String = "对照原文，你回想得如何？",
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(text = prompt, style = DesignTokens.Caption)
        Spacer(Modifier.height(DesignTokens.SpacingSm))
        Row(horizontalArrangement = Arrangement.spacedBy(DesignTokens.SpacingSm)) {
            ChoiceChip(
                text = "没想起来",
                selected = false,
                enabled = enabled,
                onClick = { onScore(0) },
                modifier = Modifier.weight(1f),
            )
            ChoiceChip(
                text = "部分想起",
                selected = false,
                enabled = enabled,
                onClick = { onScore(1) },
                modifier = Modifier.weight(1f),
            )
            ChoiceChip(
                text = "完整想起",
                selected = false,
                enabled = enabled,
                onClick = { onScore(2) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** 合书回忆展开区：选题 → 合书写回忆 → 自评三档 */
@Composable
private fun RecallEditor(
    templates: List<RecallQuestionTemplate>,
    selectedTemplate: Int,
    onTemplateSelected: (Int) -> Unit,
    answer: String,
    onAnswerChange: (String) -> Unit,
    onSave: (Int) -> Unit,
) {
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Text(text = "这一步先合上书", style = DesignTokens.CardTitle)
        Spacer(Modifier.height(DesignTokens.SpacingXs))
        Text(
            text = "挑一个问题，凭记忆写下答案，再翻开原文对照自评。",
            style = DesignTokens.Caption,
        )

        Spacer(Modifier.height(DesignTokens.SpacingMd))
        Text(text = "精加工提问", style = DesignTokens.Caption)
        Spacer(Modifier.height(DesignTokens.SpacingSm))
        Column(verticalArrangement = Arrangement.spacedBy(DesignTokens.SpacingSm)) {
            templates.forEachIndexed { index, tpl ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(DesignTokens.CornerRadius))
                        .background(
                            if (index == selectedTemplate) {
                                DesignTokens.Accent.copy(alpha = 0.08f)
                            } else {
                                DesignTokens.Background
                            },
                        )
                        .border(
                            width = 1.dp,
                            color = if (index == selectedTemplate) DesignTokens.Accent else DesignTokens.Divider,
                            shape = RoundedCornerShape(DesignTokens.CornerRadius),
                        )
                        .clickable { onTemplateSelected(index) }
                        .padding(DesignTokens.SpacingMd),
                ) {
                    Text(
                        text = tpl.text,
                        style = DesignTokens.Auxiliary.copy(
                            color = if (index == selectedTemplate) DesignTokens.Accent else DesignTokens.PrimaryText,
                        ),
                    )
                }
            }
        }

        Spacer(Modifier.height(DesignTokens.SpacingLg))
        AppMultilineTextField(
            value = answer,
            onValueChange = onAnswerChange,
            label = "合上书写下你的回忆",
            placeholder = "不用翻书，能想起来多少写多少…",
            minLines = 3,
        )

        Spacer(Modifier.height(DesignTokens.SpacingLg))
        SelfScoreRow(
            enabled = BookRecallLogic.canSelfAssess(answer),
            onScore = onSave,
        )
        if (!BookRecallLogic.canSelfAssess(answer)) {
            Spacer(Modifier.height(DesignTokens.SpacingSm))
            Text(
                text = "写下回忆之后才能自评。",
                style = DesignTokens.Caption,
            )
        }
    }
}

/** 书籍详情页：标题区 + 进度卡 + 检索练习提示 + 合书回忆区块 + 书摘区块 + 书评区块 */
@Composable
fun BookDetailScreen(
    bookId: Long,
    viewModel: BookViewModel,
    onBack: () -> Unit,
    onEditBook: (Long) -> Unit,
    onAddExcerpt: (Long) -> Unit,
    onEditExcerpt: (Long) -> Unit,
    onAddReview: (Long) -> Unit,
    onEditReview: (Long) -> Unit,
) {
    LaunchedEffect(bookId) { viewModel.loadDetail(bookId) }
    val detail by viewModel.detail.collectAsStateWithLifecycle()
    val recalls by viewModel.observeRecalls(bookId).collectAsStateWithLifecycle(initialValue = emptyList())
    val chapterTip by viewModel.chapterTip.collectAsStateWithLifecycle()

    var recallPanelOpen by rememberSaveable { mutableStateOf(false) }
    var selectedTemplate by rememberSaveable { mutableIntStateOf(0) }
    var recallAnswer by rememberSaveable { mutableStateOf("") }
    var excerptUnderReview by rememberSaveable { mutableStateOf(-1L) }

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
            IconButton(onClick = { onEditBook(bookId) }) {
                Icon(
                    imageVector = Icons.Filled.Edit,
                    contentDescription = "编辑书籍",
                    tint = DesignTokens.Accent,
                )
            }
        }

        val ui = detail
        if (ui == null) {
            Spacer(Modifier.height(DesignTokens.SpacingXl * 2))
            Text(
                text = "加载中…",
                style = DesignTokens.Caption,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
            return
        }

        // ── 标题区 ────────────────────────────────────────────────────────
        Text(text = ui.book.title, style = DesignTokens.LargeTitle)
        Spacer(Modifier.height(DesignTokens.SpacingXs))
        Text(
            text = ui.book.author.ifBlank { "佚名" },
            style = DesignTokens.Auxiliary.copy(color = DesignTokens.SecondaryText),
        )

        // ── 进度卡 ────────────────────────────────────────────────────────
        Spacer(Modifier.height(DesignTokens.SpacingLg))
        AppCard(modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${ui.percent}%",
                    style = DesignTokens.LargeTitle.copy(
                        color = if (ui.isFinished) DesignTokens.Success else DesignTokens.Accent,
                    ),
                )
                Spacer(Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "${ui.book.currentPage} / ${ui.book.totalPages} 页",
                        style = DesignTokens.Auxiliary,
                    )
                    Spacer(Modifier.height(DesignTokens.SpacingXs))
                    if (ui.isFinished) {
                        val finishedText = ui.book.finishedAt?.let {
                            "读完于 " + SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(it))
                        } ?: "已读完"
                        Text(
                            text = finishedText,
                            style = DesignTokens.Caption.copy(
                                color = DesignTokens.Success,
                                fontWeight = FontWeight.Medium,
                            ),
                        )
                    } else {
                        Text(text = "在读", style = DesignTokens.Caption.copy(color = DesignTokens.Accent))
                    }
                }
            }

            Spacer(Modifier.height(DesignTokens.SpacingMd))
            Row(horizontalArrangement = Arrangement.spacedBy(DesignTokens.SpacingSm)) {
                StepButton(text = "-10", onClick = { viewModel.stepProgress(-10) }, modifier = Modifier.weight(1f))
                StepButton(text = "+10", onClick = { viewModel.stepProgress(+10) }, modifier = Modifier.weight(1f))
                if (!ui.isFinished) {
                    Box(
                        modifier = Modifier
                            .height(44.dp)
                            .weight(1.4f)
                            .clip(RoundedCornerShape(DesignTokens.CornerRadius))
                            .background(DesignTokens.Success)
                            .clickable(onClick = { viewModel.markFinished() }),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "标记读完",
                            style = DesignTokens.Auxiliary.copy(
                                color = DesignTokens.Card,
                                fontWeight = FontWeight.SemiBold,
                            ),
                        )
                    }
                }
            }
        }

        // ── 到期书摘提示（页顶）───────────────────────────────────────
        if (ui.dueExcerptCount > 0) {
            Spacer(Modifier.height(DesignTokens.SpacingMd))
            AppCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "有 ${ui.dueExcerptCount} 条书摘到期待复习",
                    style = DesignTokens.Auxiliary.copy(
                        color = DesignTokens.Warning,
                        fontWeight = FontWeight.Medium,
                    ),
                )
                Spacer(Modifier.height(DesignTokens.SpacingXs))
                Text(
                    text = "先在心里回忆它说的是哪一段，再向下到书摘列表里逐条复习。",
                    style = DesignTokens.Caption,
                )
            }
        }

        // ── 章末提示：刚推进一章，引导做一次合书回忆 ─────────────────────
        chapterTip?.let { tip ->
            Spacer(Modifier.height(DesignTokens.SpacingMd))
            AppCard(modifier = Modifier.fillMaxWidth()) {
                TipCard(tip = tip, modifier = Modifier.fillMaxWidth(), showEvidence = true)
                AppButton(text = "知道了", secondary = true, onClick = { viewModel.dismissChapterTip() })
            }
        }

        // ── 检索练习指标区（取代「书摘总数」）─────────────────────────────
        Spacer(Modifier.height(DesignTokens.SpacingLg))
        AppCard(modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = "检索练习", style = DesignTokens.CardTitle)
                Spacer(Modifier.weight(1f))
                Text(
                    text = ui.recallLabel,
                    style = DesignTokens.Auxiliary.copy(
                        color = DesignTokens.Accent,
                        fontWeight = FontWeight.SemiBold,
                    ),
                )
            }
            Spacer(Modifier.height(DesignTokens.SpacingXs))
            Text(
                text = "进度 ${ui.percent}% 只说明读到哪一页，不代表读懂了多少。",
                style = DesignTokens.Caption,
            )
            if (ui.showRecallNotesTip) {
                StudyTips.forEvent(TipEvent.ExcerptOnlyNoRecall)?.let { tip ->
                    Spacer(Modifier.height(DesignTokens.SpacingSm))
                    TipCard(tip = tip, modifier = Modifier.fillMaxWidth(), showEvidence = true)
                }
            }
        }

        // ── 合书回忆区块 ──────────────────────────────────────────────────
        Spacer(Modifier.height(DesignTokens.SpacingLg))
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionHeader(title = "合书回忆")
            Spacer(Modifier.width(DesignTokens.SpacingSm))
            Text(text = "${ui.recallCount} 次", style = DesignTokens.Caption)
        }
        Spacer(Modifier.height(DesignTokens.SpacingMd))
        AppButton(
            text = if (recallPanelOpen) "收起" else "记录一次合书回忆",
            secondary = true,
            onClick = { recallPanelOpen = !recallPanelOpen },
        )
        if (recallPanelOpen) {
            Spacer(Modifier.height(DesignTokens.SpacingMd))
            RecallEditor(
                templates = BookRecallLogic.RECALL_QUESTION_TEMPLATES,
                selectedTemplate = selectedTemplate,
                onTemplateSelected = { selectedTemplate = it },
                answer = recallAnswer,
                onAnswerChange = { recallAnswer = it },
                onSave = { score ->
                    viewModel.saveRecall(
                        bookId = bookId,
                        pageNo = ui.book.currentPage,
                        question = BookRecallLogic.RECALL_QUESTION_TEMPLATES[selectedTemplate].text,
                        answer = recallAnswer,
                        selfScore = score,
                    ) {
                        recallAnswer = ""
                        recallPanelOpen = false
                    }
                },
            )
        }
        if (recalls.isNotEmpty()) {
            Spacer(Modifier.height(DesignTokens.SpacingMd))
            Column(verticalArrangement = Arrangement.spacedBy(DesignTokens.SpacingSm)) {
                recalls.forEach { recall -> RecallItem(recall = recall) }
            }
        }

        // ── 书摘区块 ──────────────────────────────────────────────────────
        Spacer(Modifier.height(DesignTokens.SpacingLg))
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionHeader(title = "书摘")
            Spacer(Modifier.width(DesignTokens.SpacingSm))
            Text(text = "${ui.excerpts.size} 条", style = DesignTokens.Caption)
        }
        Spacer(Modifier.height(DesignTokens.SpacingMd))
        if (ui.excerpts.isEmpty()) {
            AppCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "读到打动你的句子，就记在这里",
                    style = DesignTokens.Caption,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(DesignTokens.SpacingSm)) {
                ui.excerpts.forEach { excerpt ->
                    ExcerptItem(
                        excerpt = excerpt,
                        underReview = excerptUnderReview == excerpt.id,
                        onClick = { onEditExcerpt(excerpt.id) },
                        onStartReview = {
                            excerptUnderReview = if (excerptUnderReview == excerpt.id) -1L else excerpt.id
                        },
                        onScore = { score ->
                            viewModel.reviewExcerpt(excerpt, score)
                            excerptUnderReview = -1L
                        },
                    )
                }
            }
        }
        Spacer(Modifier.height(DesignTokens.SpacingMd))
        AppButton(text = "添加书摘", secondary = true, onClick = { onAddExcerpt(bookId) })

        // ── 书评区块 ──────────────────────────────────────────────────────
        Spacer(Modifier.height(DesignTokens.SpacingLg))
        SectionHeader(title = "书评")
        Spacer(Modifier.height(DesignTokens.SpacingMd))
        if (ui.reviews.isEmpty()) {
            AppCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "读完之后，写下你的感受",
                    style = DesignTokens.Caption,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(DesignTokens.SpacingMd))
            AppButton(text = "写书评", secondary = true, onClick = { onAddReview(bookId) })
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(DesignTokens.SpacingSm)) {
                ui.reviews.forEach { review ->
                    ReviewItem(review = review, onClick = { onEditReview(review.id) })
                }
            }
        }

        Spacer(Modifier.height(DesignTokens.SpacingXl))
    }
}

@Composable
private fun ExcerptItem(
    excerpt: Excerpt,
    underReview: Boolean,
    onClick: () -> Unit,
    onStartReview: () -> Unit,
    onScore: (Int) -> Unit,
) {
    AppCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Text(text = excerpt.content, style = DesignTokens.Body)
        Spacer(Modifier.height(DesignTokens.SpacingXs))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = excerpt.pageNo?.let { "第 $it 页" } ?: "未记页码",
                style = DesignTokens.Caption,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = reviewStateText(excerpt),
                style = DesignTokens.Caption,
            )
        }
        Spacer(Modifier.height(DesignTokens.SpacingSm))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onStartReview) {
                Text(
                    text = if (underReview) "取消复习" else "复习这条",
                    style = DesignTokens.Auxiliary.copy(color = DesignTokens.Accent),
                )
            }
        }
        if (underReview) {
            Spacer(Modifier.height(DesignTokens.SpacingSm))
            SelfScoreRow(
                enabled = true,
                onScore = onScore,
                prompt = "先回忆这条书摘在讲什么，再对照原文打分：",
            )
        }
    }
}

/** 书摘的复习状态文案：未入队 / 已复习 N 次 */
private fun reviewStateText(excerpt: Excerpt): String = when {
    excerpt.reps == 0 && excerpt.nextReviewAt == 0L -> "未加入复习队列"
    excerpt.reps == 0 -> "已排队待复习"
    else -> "已复习 ${excerpt.recallCount} 次"
}

@Composable
private fun RecallItem(recall: BookRecall) {
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Text(text = recall.question, style = DesignTokens.CardTitle)
        Spacer(Modifier.height(DesignTokens.SpacingSm))
        Text(text = recall.answer, style = DesignTokens.Body)
        Spacer(Modifier.height(DesignTokens.SpacingXs))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = recall.pageNo?.let { "第 $it 页" } ?: "未记页码",
                style = DesignTokens.Caption,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "自评：${selfScoreLabel(recall.selfScore)} · ${
                    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(recall.createdAt))
                }",
                style = DesignTokens.Caption,
            )
        }
    }
}

/** 自评档位的展示文案 */
private fun selfScoreLabel(score: Int): String = when (score.coerceIn(0, 2)) {
    0 -> "没想起来"
    1 -> "部分想起"
    else -> "完整想起"
}

@Composable
private fun ReviewItem(review: BookReview, onClick: () -> Unit) {
    AppCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        RatingStars(rating = review.rating)
        Spacer(Modifier.height(DesignTokens.SpacingSm))
        Text(text = review.content, style = DesignTokens.Body)
    }
}
