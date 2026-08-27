package com.studykit.ui.study

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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.studykit.data.entity.Word
import com.studykit.ui.components.AppButton
import com.studykit.ui.components.AppCard
import com.studykit.ui.components.EmptyState
import com.studykit.ui.theme.DesignTokens

/** 单词状态标签：新词=次文本色、学习中=强调色、已掌握=成功色 */
@Composable
private fun WordStatusTag(status: String) {
    val (text, color) = when (status) {
        Word.STATUS_MASTERED -> "已掌握" to DesignTokens.Success
        Word.STATUS_LEARNING -> "学习中" to DesignTokens.Accent
        else -> "新词" to DesignTokens.SecondaryText
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(DesignTokens.SpacingSm))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = DesignTokens.SpacingSm, vertical = DesignTokens.SpacingXs),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = DesignTokens.Caption.copy(color = color, fontWeight = FontWeight.Medium),
        )
    }
}

/** 单词列表页：开始学习主按钮 + 全部单词（单词 + 释义一行 + 状态标签） */
@Composable
fun WordListScreen(
    viewModel: StudyViewModel,
    onBack: () -> Unit,
    onStartStudy: () -> Unit,
) {
    val words by viewModel.words.collectAsStateWithLifecycle()
    val home by viewModel.homeState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
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
            Spacer(Modifier.width(DesignTokens.SpacingXs))
            Text(text = "单词库", style = DesignTokens.PageTitle)
            Spacer(Modifier.weight(1f))
            Text(
                text = "共 ${words.size} 个",
                style = DesignTokens.Caption,
            )
        }

        Spacer(Modifier.height(DesignTokens.SpacingMd))
        AppButton(
            text = "开始学习（今日待复习 ${home.dueCount} 个）",
            onClick = onStartStudy,
        )
        Spacer(Modifier.height(DesignTokens.SpacingMd))

        if (words.isEmpty()) {
            Spacer(Modifier.height(DesignTokens.SpacingXl * 2))
            EmptyState(
                title = "还没有单词",
                caption = "回到学习首页，点击右上角「录入」添加第一个单词",
                icon = Icons.Outlined.Star,
            )
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(DesignTokens.SpacingSm),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(words, key = { it.id }) { word ->
                    AppCard(modifier = Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = word.word,
                                style = DesignTokens.CardTitle,
                            )
                            Spacer(Modifier.width(DesignTokens.SpacingMd))
                            Text(
                                text = word.meaning,
                                style = DesignTokens.Caption,
                                maxLines = 1,
                                modifier = Modifier.weight(1f),
                            )
                            WordStatusTag(word.status)
                        }
                    }
                }
                item { Spacer(Modifier.height(DesignTokens.SpacingMd)) }
            }
        }
    }
}
