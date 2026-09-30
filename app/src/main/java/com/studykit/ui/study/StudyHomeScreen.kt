package com.studykit.ui.study

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
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

        MemoryBoardCard(
            dueToday = state.dueToday,
            dueTomorrow = state.dueTomorrow,
            retentionPercent = state.predictedRetentionPercent,
            scheduledTotal = state.scheduledTotal,
            forgettingCurve = state.forgettingCurve,
        )
        Spacer(Modifier.height(DesignTokens.SpacingLg))

        EntryCard(
            icon = Icons.Outlined.Star,
            iconColor = DesignTokens.Accent,
            title = "背单词",
            caption = "检索优先 · FSRS 排期 · 预测保留率 ${state.predictedRetentionPercent}%",
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

/**
 * 记忆看板：把调度器状态直接讲给用户——今日到期、明日预计、预测保留率，
 * 外加一条未来 14 天的平均遗忘曲线。取代「明天没有排期」这类无信息文案。
 */
@Composable
private fun MemoryBoardCard(
    dueToday: Int,
    dueTomorrow: Int,
    retentionPercent: Int,
    scheduledTotal: Int,
    forgettingCurve: List<Double>,
) {
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Text(text = "记忆看板", style = DesignTokens.CardTitle)
        Spacer(Modifier.height(DesignTokens.SpacingMd))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            BoardFigure(value = "$dueToday", label = "今日到期", color = DesignTokens.Accent)
            BoardFigure(value = "$dueTomorrow", label = "明日预计", color = DesignTokens.SecondaryText)
            BoardFigure(value = "$retentionPercent%", label = "预测保留率", color = DesignTokens.Success)
        }
        Spacer(Modifier.height(DesignTokens.SpacingMd))
        if (scheduledTotal == 0 || forgettingCurve.size < 2) {
            Text(
                text = "还没有进入排期的单词，先学一轮，系统会按遗忘曲线帮你安排复习。",
                style = DesignTokens.Caption,
            )
        } else {
            Text(
                text = CardSessionLogic.boardCurveCaption(scheduledTotal),
                style = DesignTokens.Caption,
            )
            Spacer(Modifier.height(DesignTokens.SpacingSm))
            ForgettingCurveChart(curve = forgettingCurve, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun BoardFigure(value: String, label: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = value, style = DesignTokens.PageTitle.copy(color = color))
        Spacer(Modifier.height(DesignTokens.SpacingXs))
        Text(text = label, style = DesignTokens.Caption)
    }
}

/** 平均遗忘曲线：只用 DesignTokens 配色画一条从左上到右下的衰减路径 */
@Composable
private fun ForgettingCurveChart(curve: List<Double>, modifier: Modifier = Modifier) {
    val lineColor = DesignTokens.Accent
    val fillColor = DesignTokens.Accent.copy(alpha = 0.10f)
    val gridColor = DesignTokens.Divider
    Canvas(
        modifier = modifier
            .height(DesignTokens.SpacingXl * 2)
            .clip(RoundedCornerShape(DesignTokens.SpacingSm))
            .background(DesignTokens.Background),
    ) {
        if (curve.size < 2) return@Canvas
        val stepX = size.width / (curve.size - 1)
        fun yAt(value: Double): Float = size.height * (1f - value.coerceIn(0.0, 1.0).toFloat())

        // 90% 参考线：FSRS 的稳定性定义点
        drawLine(
            color = gridColor,
            start = Offset(0f, yAt(0.9)),
            end = Offset(size.width, yAt(0.9)),
            strokeWidth = 1.dp.toPx(),
        )

        val line = Path().apply {
            moveTo(0f, yAt(curve.first()))
            curve.forEachIndexed { i, v -> lineTo(i * stepX, yAt(v)) }
        }
        val fill = Path().apply {
            moveTo(0f, size.height)
            curve.forEachIndexed { i, v -> lineTo(i * stepX, yAt(v)) }
            lineTo(size.width, size.height)
            close()
        }
        drawPath(fill, color = fillColor)
        drawPath(line, color = lineColor, style = Stroke(width = 2.dp.toPx()))
    }
}
