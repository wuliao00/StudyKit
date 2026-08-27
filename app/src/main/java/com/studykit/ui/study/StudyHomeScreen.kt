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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.studykit.ui.components.AppCard
import com.studykit.ui.components.StatTile
import com.studykit.ui.theme.DesignTokens

/**
 * 学习首页（学习 Tab）：页标题 + 录入入口、统计磁贴、三张入口卡片。
 */
@Composable
fun StudyHomeScreen(
    viewModel: StudyViewModel,
    onOpenWords: () -> Unit,
    onStartQuiz: () -> Unit,
    onOpenMistakes: () -> Unit,
    onAddWord: () -> Unit,
    onAddQuestion: () -> Unit,
) {
    val state by viewModel.homeState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = DesignTokens.PageHorizontalPadding),
    ) {
        Spacer(Modifier.height(DesignTokens.SpacingSm))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = "学习", style = DesignTokens.LargeTitle)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onAddWord) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = null,
                    tint = DesignTokens.Accent,
                )
                Spacer(Modifier.width(DesignTokens.SpacingXs))
                Text(
                    text = "录入",
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
                value = "${state.dueCount}",
                label = "今日待复习",
                modifier = Modifier.weight(1f),
            )
            StatTile(
                value = "${state.totalCount}",
                label = "单词总数",
                modifier = Modifier.weight(1f),
            )
            StatTile(
                value = "${state.masteredCount}",
                label = "已掌握",
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(DesignTokens.SpacingLg))

        EntryCard(
            icon = Icons.Outlined.Star,
            iconColor = DesignTokens.Accent,
            title = "背单词",
            caption = "今日待复习 ${state.dueCount} 个 · 卡片翻面记忆",
            onClick = onOpenWords,
        )
        Spacer(Modifier.height(DesignTokens.SpacingMd))
        EntryCard(
            icon = Icons.Outlined.CheckCircle,
            iconColor = DesignTokens.Success,
            title = "题库练习",
            caption = "按学科刷题 · 答错自动入错题本",
            onClick = onStartQuiz,
            trailing = {
                IconButton(onClick = onAddQuestion) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = "录入题目",
                        tint = DesignTokens.Accent,
                    )
                }
            },
        )
        Spacer(Modifier.height(DesignTokens.SpacingMd))
        EntryCard(
            icon = Icons.Outlined.Close,
            iconColor = DesignTokens.Warning,
            title = "错题本",
            caption = "${state.mistakeCount} 道待掌握",
            onClick = onOpenMistakes,
        )
        Spacer(Modifier.height(DesignTokens.SpacingLg))
    }
}

/** 入口卡片：圆形图标 + 标题 + 说明，可选尾部操作按钮 */
@Composable
private fun EntryCard(
    icon: ImageVector,
    iconColor: Color,
    title: String,
    caption: String,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    AppCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(iconColor.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconColor,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.width(DesignTokens.SpacingMd))
            Column(Modifier.weight(1f)) {
                Text(text = title, style = DesignTokens.CardTitle)
                Spacer(Modifier.height(2.dp))
                Text(text = caption, style = DesignTokens.Caption)
            }
            trailing?.invoke()
        }
    }
}
