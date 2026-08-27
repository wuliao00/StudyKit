package com.studykit.ui.book

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.studykit.ui.components.AppButton
import com.studykit.ui.components.AppCard
import com.studykit.ui.components.EmptyState
import com.studykit.ui.components.StatTile
import com.studykit.ui.theme.DesignTokens

/** 状态标签：在读=强调色，读完=成功色 */
@Composable
private fun StatusTag(finished: Boolean) {
    val color = if (finished) DesignTokens.Success else DesignTokens.Accent
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(DesignTokens.SpacingSm))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = DesignTokens.SpacingSm, vertical = DesignTokens.SpacingXs),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text  = if (finished) "读完" else "在读",
            style = DesignTokens.Caption.copy(color = color, fontWeight = FontWeight.Medium),
        )
    }
}

/** 书架页：页标题 + 添加入口、统计磁贴、书籍卡片 */
@Composable
fun BookShelfScreen(
    viewModel: BookViewModel,
    onOpenBook: (Long) -> Unit,
    onAddClick: () -> Unit,
) {
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
            Text(text = "读书", style = DesignTokens.LargeTitle)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onAddClick) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = null,
                    tint = DesignTokens.Accent,
                )
                Spacer(Modifier.width(DesignTokens.SpacingXs))
                Text(
                    text = "添加",
                    style = DesignTokens.Auxiliary.copy(
                        color = DesignTokens.Accent,
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
                items(state.items, key = { it.book.id }) { item ->
                    BookCard(
                        item = item,
                        onClick = { onOpenBook(item.book.id) },
                    )
                }
                item { Spacer(Modifier.height(DesignTokens.SpacingMd)) }
            }
        }
    }
}

/** 书籍卡片：书名 + 作者 + 进度条 + 状态标签 */
@Composable
private fun BookCard(
    item: BookItemUi,
    onClick: () -> Unit,
) {
    val animatedProgress by animateFloatAsState(
        targetValue = item.progress,
        animationSpec = tween(DesignTokens.AnimDurationMs, easing = DesignTokens.AnimEasing),
        label = "bookProgress",
    )
    AppCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(text = item.book.title, style = DesignTokens.CardTitle)
                Spacer(Modifier.height(2.dp))
                Text(text = item.book.author, style = DesignTokens.Caption)
            }
            StatusTag(item.isFinished)
        }

        Spacer(Modifier.height(DesignTokens.SpacingMd))
        LinearProgressIndicator(
            progress = { animatedProgress },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp)),
            color = if (item.isFinished) DesignTokens.Success else DesignTokens.Accent,
            trackColor = DesignTokens.Divider,
            strokeCap = StrokeCap.Round,
        )
        Spacer(Modifier.height(DesignTokens.SpacingSm))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${item.book.currentPage} / ${item.book.totalPages} 页",
                style = DesignTokens.Caption,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "${item.percent}%",
                style = DesignTokens.Caption.copy(
                    color = if (item.isFinished) DesignTokens.Success else DesignTokens.Accent,
                    fontWeight = FontWeight.SemiBold,
                ),
            )
        }
    }
}
