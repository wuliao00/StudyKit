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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.studykit.data.entity.BookReview
import com.studykit.data.entity.Excerpt
import com.studykit.ui.components.AppButton
import com.studykit.ui.components.AppCard
import com.studykit.ui.components.SectionHeader
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

/** 书籍详情页：标题区 + 进度卡 + 书摘区块 + 书评区块 */
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
                    ExcerptItem(excerpt = excerpt, onClick = { onEditExcerpt(excerpt.id) })
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
private fun ExcerptItem(excerpt: Excerpt, onClick: () -> Unit) {
    AppCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Text(text = excerpt.content, style = DesignTokens.Body)
        Spacer(Modifier.height(DesignTokens.SpacingXs))
        Text(
            text = excerpt.pageNo?.let { "第 $it 页" } ?: "未记页码",
            style = DesignTokens.Caption,
        )
    }
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
