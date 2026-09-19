package com.studykit.ui.book

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.studykit.ui.components.AppButton
import com.studykit.ui.components.AppCard
import com.studykit.ui.components.EmptyState
import com.studykit.ui.components.StatTile
import com.studykit.ui.motion.MotionSpec
import com.studykit.ui.motion.StaggeredIn
import com.studykit.ui.theme.AppTheme
import com.studykit.ui.theme.DesignTokens

/** 书脊色带宽度：贴在卡片左边缘的装饰，不占内容排版宽度 */
private val SpineBandWidth: Dp = 6.dp

/**
 * 入场错峰的下标上限（T2 裁定：列表里调用 [StaggeredIn] 必须由调用方限幅，
 * 否则第 20 本书要等 800ms 才开始淡入，首屏下方一片空白）。
 */
private const val StaggerIndexCap = 8

/**
 * 状态标签（柔底药丸）：`在读 = accentSoft + accentInk`、`读完 = successSoft + primaryText`。
 *
 * 旧写法是「Success/Accent 实色 12% 底 + 同色文字」，而 accent 作文字色只有 3.04:1（T1 裁定：
 * 文本态必须走 `accentInk`）；success 作文字色同样只有 ≈2.2:1，故读完态药丸的墨色取 `primaryText`
 * —— 容器已经是 successSoft，颜色语义由容器承担，不必再让文字去扛对比度。
 */
@Composable
private fun StatusTag(finished: Boolean) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    val container = if (finished) colors.successSoft else colors.accentSoft
    val ink = if (finished) colors.primaryText else colors.accentInk
    Box(
        modifier = Modifier
            .background(color = container, shape = RoundedCornerShape(DesignTokens.CornerRadius))
            .padding(horizontal = DesignTokens.SpacingSm, vertical = DesignTokens.SpacingXs),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (finished) "读完" else "在读",
            style = texts.caption.copy(color = ink, fontWeight = FontWeight.Medium),
        )
    }
}

/** 书架页：页标题 + 添加入口、统计磁贴、带书脊色带的书籍卡片 */
@Composable
fun BookShelfScreen(
    viewModel: BookViewModel,
    onOpenBook: (Long) -> Unit,
    onAddClick: () -> Unit,
) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    val state by viewModel.shelfState.collectAsStateWithLifecycle()

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
            Text(text = "读书", style = texts.largeTitle)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onAddClick) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = null,
                    tint = colors.accentInk,
                )
                Spacer(Modifier.width(DesignTokens.SpacingXs))
                Text(
                    text = "添加",
                    style = texts.aux.copy(
                        color = colors.accentInk,
                        fontWeight = FontWeight.Medium,
                    ),
                )
            }
        }

        Spacer(Modifier.height(DesignTokens.SpacingMd))
        Row(horizontalArrangement = Arrangement.spacedBy(DesignTokens.SpacingSm)) {
            StatTile(
                value = "${state.readingCount}",
                label = "在读",
                modifier = Modifier.weight(1f),
            )
            StatTile(
                value = "${state.finishedCount}",
                label = "已读完",
                modifier = Modifier.weight(1f),
            )
            StatTile(
                value = "${state.excerptCount}",
                label = "书摘总数",
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(DesignTokens.SpacingLg))

        if (state.items.isEmpty()) {
            Spacer(Modifier.height(DesignTokens.SpacingXl * 2))
            EmptyState(
                title = "书架还是空的",
                caption = "添加一本书，开始记录你的阅读旅程",
                icon = Icons.Outlined.Favorite,
            )
            Spacer(Modifier.height(DesignTokens.SpacingLg))
            AppButton(text = "添加第一本书", onClick = onAddClick)
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(DesignTokens.SpacingMd),
                modifier = Modifier.fillMaxSize(),
            ) {
                // 按书 id 为键：新书记录插在首位（`ORDER BY started_at DESC`），既有卡片只会让位、
                // 不会丢失各自的入场状态（StaggeredIn 的 shown 跟着条目 identity 走）。
                itemsIndexed(state.items, key = { _, item -> item.book.id }) { index, item ->
                    StaggeredIn(
                        index = minOf(index, StaggerIndexCap),
                        modifier = Modifier.animateItem(),
                    ) {
                        BookCard(
                            item = item,
                            onClick = { onOpenBook(item.book.id) },
                        )
                    }
                }
                item { Spacer(Modifier.height(DesignTokens.SpacingMd)) }
            }
        }
    }
}

/**
 * 书籍卡片：书脊色带 + 书名 + 作者 + 进度条 + 状态标签 + 页码与百分比。
 *
 * **书脊色带**是 [SpinePalette] 里由 [spineColorIndex] 按书名取的一个色位（同名恒定同色）。
 * 它是盖在卡片左边缘的 overlay，而不是内容行的第一格：[AppCard] 自带 16dp 版心与 20dp 圆角，
 * 塞进内容行只能得到一根「离边缘 16dp 的短色柱」，读起来不像书脊。
 * 写法取 `matchParentSize()`（子项按父 Box 实测尺寸定高，且**不反过来撑大**父容器）：
 * 外层盒裁成卡片左端圆角轮廓，内层色柱 `fillMaxHeight()` 拿到的就是卡片实高。
 *
 * 进度条补间用 [MotionSpec.ring]（与 [com.studykit.ui.components.RingGauge] 同一条进度 spring）：
 * 逐帧跟随 vsync，高刷屏按 90/120Hz 渲染；spring 有轻微过冲，故喂给指示器前显式钳回 0..1。
 */
@Composable
private fun BookCard(
    item: BookItemUi,
    onClick: () -> Unit,
) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    val animatedProgress by animateFloatAsState(
        targetValue = item.progress,
        animationSpec = MotionSpec.ring,
        label = "bookProgress",
    )
    val spineColor = SpinePalette[spineColorIndex(item.book.title, SpinePalette.size)]

    Box(modifier = Modifier.fillMaxWidth()) {
        AppCard(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(text = item.book.title, style = texts.cardTitle)
                    Spacer(Modifier.height(2.dp))
                    Text(text = item.book.author, style = texts.caption)
                }
                StatusTag(finished = item.isFinished)
            }

            Spacer(Modifier.height(DesignTokens.SpacingMd))
            LinearProgressIndicator(
                progress = { animatedProgress.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = if (item.isFinished) colors.success else colors.accent,
                trackColor = colors.divider,
                strokeCap = StrokeCap.Round,
            )
            Spacer(Modifier.height(DesignTokens.SpacingSm))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${item.book.currentPage} / ${item.book.totalPages} 页",
                    style = texts.caption,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "${item.percent}%",
                    style = texts.caption.copy(
                        color = if (item.isFinished) colors.success else colors.accentInk,
                        fontWeight = FontWeight.SemiBold,
                    ),
                )
            }
        }

        // 书脊色带（overlay，见函数 KDoc）
        Box(
            modifier = Modifier
                .matchParentSize()
                .clip(
                    RoundedCornerShape(
                        topStart = DesignTokens.CornerRadiusLg,
                        bottomStart = DesignTokens.CornerRadiusLg,
                    ),
                ),
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(SpineBandWidth)
                    .background(spineColor),
            )
        }
    }
}
