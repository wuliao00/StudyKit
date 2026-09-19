package com.studykit.ui.book

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.studykit.data.entity.BookReview
import com.studykit.data.entity.Excerpt
import com.studykit.ui.components.AppButton
import com.studykit.ui.components.AppCard
import com.studykit.ui.components.SectionHeader
import com.studykit.ui.theme.AppTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 引用竖条宽度（书摘条目左侧那条） */
private val QuoteBarWidth: Dp = 3.dp

/** 页码步进按钮：-10 / +10（宽度由调用方在 Row 中通过 weight 分配） */
@Composable
private fun StepButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    Box(
        modifier = modifier
            .height(44.dp)
            .clip(RoundedCornerShape(AppTheme.radius.md))
            .background(colors.card)
            .border(
                width = 1.dp,
                color = colors.divider,
                shape = RoundedCornerShape(AppTheme.radius.md),
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = texts.aux.copy(
                color = colors.accentInk,
                fontWeight = FontWeight.SemiBold,
            ),
        )
    }
}

/** 评分星行（只读展示）：实心走 accent（图形非文字），空星走 divider */
@Composable
fun RatingStars(rating: Int, modifier: Modifier = Modifier, starSize: Dp = 16.dp) {
    val colors = AppTheme.colors
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        repeat(5) { index ->
            Icon(
                imageVector = Icons.Filled.Star,
                contentDescription = null,
                tint = if (index < rating) colors.accent else colors.divider,
                modifier = Modifier.size(starSize),
            )
        }
    }
}

/** 书籍详情页：标题区 + 进度卡 + 书摘区块（引用样式）+ 书评区块 */
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
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    LaunchedEffect(bookId) { viewModel.loadDetail(bookId) }
    val detail by viewModel.detail.collectAsStateWithLifecycle()
    // 路由键守卫（终审 C1 的同类站点）：只认「答的就是本书」的那份应答。`loadDetail(bookId)`
    // 是异步的，换书进来的那一帧 VM 里留着的还是上一本书的 detail —— 不挡的话这里渲染的是上一本书，
    // 而 -10 / +10 / 标记读完 走的是 `viewModel.stepProgress()`（它读 VM 当前 detail），
    // 进度与「读完」会写到另一本书上。
    // 声明与读取放在一处（与 `MistakeDetailScreen` / `HabitCalendarScreen` 同一结构，波 4 项 4）；
    // 早返回留在下面的 Column 里，让加载期间页头两颗按钮照常可点 —— 三页统一的是**结构**，观感一分不动。
    val ui = detail?.takeIf { it.book.id == bookId }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
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
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { onEditBook(bookId) }) {
                Icon(
                    imageVector = Icons.Filled.Edit,
                    contentDescription = "编辑书籍",
                    tint = colors.accentInk,
                )
            }
        }

        if (ui == null) {
            Spacer(Modifier.height(AppTheme.space.xl * 2))
            Text(
                text = "加载中…",
                style = texts.caption,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
            return
        }

        // ── 标题区 ────────────────────────────────────────────────────────
        Text(text = ui.book.title, style = texts.largeTitle)
        Spacer(Modifier.height(AppTheme.space.xs))
        Text(
            text = ui.book.author.ifBlank { "佚名" },
            style = texts.aux.copy(color = colors.secondaryText),
        )

        // ── 进度卡 ────────────────────────────────────────────────────────
        Spacer(Modifier.height(AppTheme.space.lg))
        AppCard(modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${ui.percent}%",
                    style = texts.largeTitle.copy(
                        color = if (ui.isFinished) colors.successInk else colors.accentInk,
                    ),
                )
                Spacer(Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "${ui.book.currentPage} / ${ui.book.totalPages} 页",
                        style = texts.aux,
                    )
                    Spacer(Modifier.height(AppTheme.space.xs))
                    if (ui.isFinished) {
                        val finishedText = ui.book.finishedAt?.let {
                            "读完于 " + SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(it))
                        } ?: "已读完"
                        Text(
                            text = finishedText,
                            style = texts.caption.copy(
                                color = colors.successInk,
                                fontWeight = FontWeight.Medium,
                            ),
                        )
                    } else {
                        Text(text = "在读", style = texts.caption.copy(color = colors.accentInk))
                    }
                }
            }

            Spacer(Modifier.height(AppTheme.space.md))
            Row(horizontalArrangement = Arrangement.spacedBy(AppTheme.space.sm)) {
                StepButton(text = "-10", onClick = { viewModel.stepProgress(-10) }, modifier = Modifier.weight(1f))
                StepButton(text = "+10", onClick = { viewModel.stepProgress(+10) }, modifier = Modifier.weight(1f))
                if (!ui.isFinished) {
                    // 完成态动作走 successSoft 柔底 + successInk 墨色（与书架 StatusTag 同一族颜色）：
                    // 旧写法是 success 实底压硬白字，浅色主题下白字只有 ≈2.2:1，夜间还会把深墨字压在
                    // 亮绿上；白字压实底按 T15 裁定只留给 accent 一处，其余一律「soft 底 + ink 字」。
                    Box(
                        modifier = Modifier
                            .height(44.dp)
                            .weight(1.4f)
                            .clip(RoundedCornerShape(AppTheme.radius.md))
                            .background(colors.successSoft)
                            .clickable(onClick = { viewModel.markFinished() }),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "标记读完",
                            style = texts.aux.copy(
                                color = colors.successInk,
                                fontWeight = FontWeight.SemiBold,
                            ),
                        )
                    }
                }
            }
        }

        // ── 书摘区块 ──────────────────────────────────────────────────────
        Spacer(Modifier.height(AppTheme.space.lg))
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionHeader(title = "书摘")
            Spacer(Modifier.width(AppTheme.space.sm))
            Text(text = "${ui.excerpts.size} 条", style = texts.caption)
        }
        Spacer(Modifier.height(AppTheme.space.md))
        if (ui.excerpts.isEmpty()) {
            AppCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "读到打动你的句子，就记在这里",
                    style = texts.caption,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(AppTheme.space.sm)) {
                ui.excerpts.forEach { excerpt ->
                    ExcerptItem(excerpt = excerpt, onClick = { onEditExcerpt(excerpt.id) })
                }
            }
        }
        Spacer(Modifier.height(AppTheme.space.md))
        AppButton(text = "添加书摘", secondary = true, onClick = { onAddExcerpt(bookId) })

        // ── 书评区块 ──────────────────────────────────────────────────────
        Spacer(Modifier.height(AppTheme.space.lg))
        SectionHeader(title = "书评")
        Spacer(Modifier.height(AppTheme.space.md))
        if (ui.reviews.isEmpty()) {
            AppCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "读完之后，写下你的感受",
                    style = texts.caption,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(AppTheme.space.md))
            AppButton(text = "写书评", secondary = true, onClick = { onAddReview(bookId) })
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(AppTheme.space.sm)) {
                ui.reviews.forEach { review ->
                    ReviewItem(review = review, onClick = { onEditReview(review.id) })
                }
            }
        }

        Spacer(Modifier.height(AppTheme.space.xl))
    }
}

/**
 * 书摘条目：引用样式。
 *
 * 左侧 3dp `accentInk` 竖条 + 正文 `texts.aux` + 页码与「—— 摘录」落款。竖条走 ink 而非 `accent`
 * 是 T1/T12 那条「线条/文字这类细笔画用 ink」的延续：3dp 宽的条落在白卡上时，accent 只有 3.04:1，
 * 细笔画在这个宽度下比色块更容易糊掉。
 *
 * 竖条高度用 `matchParentSize()`（见 [BookShelfScreen] 书脊色带同一写法）：它盖在引用区之上、
 * 按整条书摘的实测高度拉到底，而不是被 [AppCard] 的版心截成一段。
 */
@Composable
private fun ExcerptItem(excerpt: Excerpt, onClick: () -> Unit) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    AppCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = AppTheme.space.md),
            ) {
                Text(text = excerpt.content, style = texts.aux)
                Spacer(Modifier.height(AppTheme.space.xs))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = excerpt.pageNo?.let { "第 $it 页" } ?: "未记页码",
                        style = texts.caption,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = "—— 摘录",
                        style = texts.caption.copy(color = colors.accentInk),
                    )
                }
            }
            Box(
                modifier = Modifier.matchParentSize(),
                contentAlignment = Alignment.CenterStart,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(QuoteBarWidth)
                        .background(colors.accentInk),
                )
            }
        }
    }
}

/** 书评条目：星级 + 正文 */
@Composable
private fun ReviewItem(review: BookReview, onClick: () -> Unit) {
    val texts = AppTheme.texts
    AppCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        RatingStars(rating = review.rating)
        Spacer(Modifier.height(AppTheme.space.sm))
        Text(text = review.content, style = texts.body)
    }
}
