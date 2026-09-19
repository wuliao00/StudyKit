package com.studykit.ui.study

import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.studykit.ui.components.AppCard
import com.studykit.ui.components.RingGauge
import com.studykit.ui.components.StatTile
import com.studykit.ui.motion.MotionSpec
import com.studykit.ui.motion.StaggeredIn
import com.studykit.ui.theme.AppTheme
import com.studykit.ui.theme.DesignTokens

/**
 * 学习首页（学习 Tab）：页标题 + 今日任务 hero 卡（进度环 + 火焰徽章）、统计磁贴、三张入口卡片。
 * 颜色与文字样式统一取 `AppTheme`；间距/圆角仍走 [DesignTokens] 的 dp 常量。
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
    val colors = AppTheme.colors
    val texts = AppTheme.texts
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
            Text(text = "学习", style = texts.largeTitle)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onAddWord) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = null,
                    tint = colors.accentInk,
                )
                Spacer(Modifier.width(DesignTokens.SpacingXs))
                Text(
                    text = "录入",
                    style = texts.aux.copy(
                        color = colors.accentInk,
                        fontWeight = FontWeight.Medium,
                    ),
                )
            }
        }

        Spacer(Modifier.height(DesignTokens.SpacingMd))
        TodayHeroCard(
            todayDone = state.todayDone,
            dueCount = state.dueCount,
            streakDays = state.streakDays,
        )

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

        StaggeredIn(index = 0) {
            EntryCard(
                icon = Icons.Outlined.Star,
                iconColor = colors.accent,
                iconContainerColor = colors.accentSoft,
                title = "背单词",
                caption = "今日待复习 ${state.dueCount} 个 · 卡片翻面记忆",
                onClick = onOpenWords,
            )
        }
        Spacer(Modifier.height(DesignTokens.SpacingMd))
        StaggeredIn(index = 1) {
            EntryCard(
                icon = Icons.Outlined.CheckCircle,
                iconColor = colors.success,
                iconContainerColor = colors.successSoft,
                title = "题库练习",
                caption = "按学科刷题 · 答错自动入错题本",
                onClick = onStartQuiz,
                trailing = {
                    IconButton(onClick = onAddQuestion) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = "录入题目",
                            tint = colors.accentInk,
                        )
                    }
                },
            )
        }
        Spacer(Modifier.height(DesignTokens.SpacingMd))
        StaggeredIn(index = 2) {
            EntryCard(
                icon = Icons.Outlined.Close,
                iconColor = colors.warning,
                iconContainerColor = colors.warningSoft,
                title = "错题本",
                caption = "${state.mistakeCount} 道待掌握",
                onClick = onOpenMistakes,
            )
        }
        Spacer(Modifier.height(DesignTokens.SpacingLg))
    }
}

/**
 * 今日任务 hero 卡：左侧 88dp 进度环（完成次数 / 今日总任务，达成转 gold），
 * 右侧「今日待办」标题 + `todayDone / total` 大数 + 连续学习火焰徽章。
 *
 * `todayDone` 统计的是**复习/练习次数**（会话数），不是学习天数，因此文案只报「今日待办」计数、
 * 不出现「天」字；「连续 X 天」只属于 [FlameBadge]（`streakDays`，由单词复习与题目练习共同驱动）。
 * 标题用「待办」而非「任务」：`dueCount` 含逾期项，「任务」会高估今日口径。
 *
 * 参数只收 hero 渲染所需的三个字段（而非整个 [StudyHomeUiState]）：
 * `mistakeCount/totalCount` 等其余字段变化时不触发本卡重组。
 * 容器保持 88dp 正方形：`RingGauge` 已在容器内双向居中（弧不再锚定左上角），但直径仍取
 * `minDimension`，正方形才能让环的大小与容器高度解耦。
 */
@Composable
private fun TodayHeroCard(
    todayDone: Int,
    dueCount: Int,
    streakDays: Int,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    val total = todayDone + dueCount
    // 空日（total == 0）显示空环而非满环：gold/达成态须由真实完成数驱动，
    // progress >= 1f 在 0/0 下不再可能
    val progress = if (total == 0) 0f else todayDone.toFloat() / total
    AppCard(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(88.dp),
                contentAlignment = Alignment.Center,
            ) {
                RingGauge(
                    progress = progress,
                    modifier = Modifier.fillMaxSize(),
                    strokeWidth = 9.dp,
                    color = if (progress >= 1f && total > 0) colors.gold else colors.accent,
                )
                Text(text = "$todayDone", style = texts.statValue.copy(fontSize = 28.sp))
            }
            Spacer(Modifier.width(DesignTokens.SpacingLg))
            Column(Modifier.weight(1f)) {
                Text(text = "今日待办", style = texts.caption)
                Spacer(Modifier.height(DesignTokens.SpacingXs))
                Text(text = "$todayDone / $total", style = texts.pageTitle)
                Spacer(Modifier.height(DesignTokens.SpacingSm))
                if (streakDays > 0) FlameBadge(days = streakDays)
            }
        }
    }
}

/** 连续学习火焰徽章：进场时 0.4 → 1 的 snap spring 弹入，goldSoft 底 + gold 文案 */
@Composable
private fun FlameBadge(days: Int) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    // saveable：转屏后 born 直接恢复为 true ⇒ 目标值不再从 0.4f 起步，徽章不会凭空再弹一次
    var born by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { born = true }
    val s by animateFloatAsState(
        targetValue = if (born) 1f else 0.4f,
        animationSpec = MotionSpec.snap,
        label = "flame",
    )
    Box(
        modifier = Modifier
            .graphicsLayer { scaleX = s; scaleY = s }
            .clip(RoundedCornerShape(DesignTokens.CornerRadius))
            .background(colors.goldSoft)
            .padding(horizontal = DesignTokens.SpacingSm, vertical = 4.dp),
    ) {
        Text(
            text = "🔥 连续 $days 天",
            style = texts.caption.copy(color = colors.gold, fontWeight = FontWeight.SemiBold),
        )
    }
}

/** 入口卡片：圆形图标（soft 底色）+ 标题 + 说明，可选尾部操作按钮；点击默认 ripple */
@Composable
private fun EntryCard(
    icon: ImageVector,
    iconColor: Color,
    iconContainerColor: Color,
    title: String,
    caption: String,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    val texts = AppTheme.texts
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
                    .background(iconContainerColor),
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
                Text(text = title, style = texts.cardTitle)
                Spacer(Modifier.height(2.dp))
                Text(text = caption, style = texts.caption)
            }
            trailing?.invoke()
        }
    }
}
