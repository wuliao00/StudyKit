package com.studykit.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.studykit.data.entity.DesignPlan
import com.studykit.domain.design.DesignMode
import com.studykit.ui.components.AppCard
import com.studykit.ui.theme.AppTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 学习设计 Tab：8 个模板入口 + 已保存方案。
 *
 * 模板按「先讲清楚—再练—再调方法—再做教学设计」排，不是按字母序：
 * 前两个是学生自己用的，中间两个是学习方法，后四个是教师/带练者做课程与活动用的。
 * 每张卡片的 `basis` 只写方法论名字，不写“研究表明”这种没有出处的空话。
 */
@Composable
fun DesignHubScreen(
    viewModel: DesignViewModel,
    onNewPlan: (DesignMode) -> Unit,
    onOpenPlan: (DesignPlan) -> Unit,
) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    val plans by viewModel.plans.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = AppTheme.space.pageH,
            end = AppTheme.space.pageH,
            top = AppTheme.space.sm,
            bottom = AppTheme.space.xl,
        ),
        verticalArrangement = Arrangement.spacedBy(AppTheme.space.md),
    ) {
        item {
            Column {
                Text(text = "学习设计", style = texts.pageTitle)
                Spacer(Modifier.height(AppTheme.space.xs))
                Text(
                    text = "8 种模板 · 纯本地拼装 · 可保存、复制、分享。不是 AI 生成，填什么就写什么。",
                    style = texts.caption,
                )
            }
        }

        item {
            Spacer(Modifier.height(AppTheme.space.xs))
            Text(text = "我的方案", style = texts.cardTitle)
        }

        if (plans.isEmpty()) {
            item {
                AppCard(modifier = Modifier.fillMaxWidth()) {
                    Text(text = "还没有保存的方案", style = texts.body)
                    Spacer(Modifier.height(AppTheme.space.xs))
                    Text(
                        text = "从下面选一个模板开始；填完点“保存方案”，就会出现在这里。",
                        style = texts.caption,
                    )
                }
            }
        } else {
            items(plans, key = { it.id }) { plan ->
                PlanCard(plan = plan, onClick = { onOpenPlan(plan) })
            }
        }

        item {
            Spacer(Modifier.height(AppTheme.space.sm))
            Text(text = "模板", style = texts.cardTitle)
        }

        items(DesignMode.entries, key = { it.name }) { mode ->
            ModeCard(mode = mode, onClick = { onNewPlan(mode) })
        }
    }
}

private data class ModeVisual(val icon: ImageVector, val tint: Color, val container: Color)

@Composable
private fun modeVisual(mode: DesignMode): ModeVisual {
    val colors = AppTheme.colors
    val icon = when (mode) {
        DesignMode.FEYNMAN -> Icons.Outlined.Edit
        DesignMode.GUIDED_PRACTICE -> Icons.Outlined.CheckCircle
        DesignMode.LEARNING_TO_LEARN -> Icons.Outlined.Star
        DesignMode.WORKSHOP -> Icons.Outlined.Favorite
        DesignMode.SERIES -> Icons.Outlined.DateRange
        DesignMode.BACKWARD -> Icons.Outlined.Search
        DesignMode.COMPETENCY -> Icons.Outlined.Person
        DesignMode.SPIRAL -> Icons.Outlined.Refresh
    }
    val (tint, container) = when (mode) {
        DesignMode.FEYNMAN, DesignMode.LEARNING_TO_LEARN ->
            colors.accentInk to colors.accentSoft

        DesignMode.GUIDED_PRACTICE, DesignMode.SPIRAL ->
            colors.successInk to colors.successSoft

        DesignMode.WORKSHOP, DesignMode.SERIES ->
            colors.goldInk to colors.goldSoft

        DesignMode.BACKWARD, DesignMode.COMPETENCY ->
            colors.warningInk to colors.warningSoft
    }
    return ModeVisual(icon = icon, tint = tint, container = container)
}

@Composable
private fun ModeCard(mode: DesignMode, onClick: () -> Unit) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    val visual = modeVisual(mode)
    AppCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(visual.container),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = visual.icon,
                    contentDescription = null,
                    tint = visual.tint,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(AppTheme.space.sm))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = mode.label, style = texts.cardTitle)
                Spacer(Modifier.height(AppTheme.space.xs))
                Text(text = mode.tagline, style = texts.aux)
                Spacer(Modifier.height(AppTheme.space.xs))
                Text(text = "依据：${mode.basis}", style = texts.caption)
            }
            Spacer(Modifier.width(AppTheme.space.xs))
            Text(text = "新建 ›", style = texts.caption.copy(color = colors.accentInk))
        }
    }
}

@Composable
private fun PlanCard(plan: DesignPlan, onClick: () -> Unit) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    val mode = DesignMode.fromStorage(plan.mode)
    // 未知 mode 可能是手改库或未来改过枚举名。只读这张卡，不让人点进编辑页：
    // 编辑页会按新 mode 的字段覆盖旧 fields/content，一次误保存就把原文永久换掉。
    val clickable = if (mode != null) Modifier.clickable(onClick = onClick) else Modifier
    AppCard(
        modifier = Modifier
            .fillMaxWidth()
            .then(clickable),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = plan.title.ifBlank { "未命名方案" }, style = texts.cardTitle)
                Spacer(Modifier.height(AppTheme.space.xs))
                Text(
                    text = "${mode?.label ?: "未知模板"} · ${formatTime(plan.updatedAt)}",
                    style = texts.caption,
                )
                if (mode == null) {
                    Text(
                        text = "这份方案的模板版本不兼容，只保留原文，不在本页编辑。",
                        style = texts.caption.copy(color = colors.warningInk),
                    )
                }
            }
            if (mode != null) {
                Text(text = "继续 ›", style = texts.caption.copy(color = colors.accentInk))
            }
        }
    }
}

private fun formatTime(millis: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(millis))
