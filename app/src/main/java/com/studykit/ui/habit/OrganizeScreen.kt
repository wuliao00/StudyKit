package com.studykit.ui.habit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.studykit.data.entity.Habit
import com.studykit.ui.components.AppCard
import com.studykit.ui.components.EmptyState
import com.studykit.ui.theme.AppTheme

/**
 * 整理页（v2.4 批次四）：按分类分组列出全部习惯，批量改分类、上移/下移、归档开关。
 *
 * **与 spec 的偏离**：spec 写"长按拖动排序"，这里改成上移/下移按钮 —— 拖拽排序在 Compose 里
 * 要自写一套手势 + 自动滚动的逻辑，一个批次塞不下，先交付能力：按钮换位与拖拽换的是同一个
 * `sort_order` 字段，日后补拖拽手势时不迁移数据、不改纯函数。
 * 页顶说明一句"排序在同分类内生效"，把 [HabitViewModel.moveHabit] 的换位口径亮给用户。
 *
 * 结构与 [ContractsScreen] 同一套纪律：根 Column `fillMaxSize + verticalScroll + padding(pageH)`，
 * insets 全交给 AppNav 根 Scaffold 的 innerPadding；颜色/文字只取 AppTheme tokens。
 */
@Composable
fun OrganizeScreen(
    viewModel: HabitViewModel,
    onBack: () -> Unit,
) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    val habits by viewModel.organizeHabits.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = AppTheme.space.pageH),
    ) {
        Spacer(modifier = Modifier.height(AppTheme.space.sm))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = colors.accentInk,
                )
            }
            Spacer(modifier = Modifier.width(AppTheme.space.xs))
            Text(text = "整理", style = texts.pageTitle, modifier = Modifier.weight(1f))
        }
        Text(
            text = "排序在同分类内生效 —— 上下移动只交换同分类里的相邻位置",
            style = texts.caption.copy(color = colors.secondaryText),
            modifier = Modifier.padding(top = AppTheme.space.xs),
        )
        Spacer(modifier = Modifier.height(AppTheme.space.md))

        if (habits.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(modifier = Modifier.height(AppTheme.space.xl * 2))
                EmptyState(
                    title = "没有可整理的习惯",
                    caption = "先在习惯页创建一个吧",
                )
            }
        } else {
            val active = habits.filter { habit -> !habit.archived }
            // 未知分类值（旧库脏数据）也能落到自己的组，而不是被静默丢掉
            val orderedCategories = Habit.CATEGORIES +
                active.map { it.category }.filter { category -> category !in Habit.CATEGORIES }.distinct()
            orderedCategories.forEach { category ->
                val group = active.filter { habit -> habit.category == category }
                if (group.isNotEmpty()) {
                    Text(
                        text = categoryLabel(category = category),
                        style = texts.cardTitle,
                        modifier = Modifier.padding(top = AppTheme.space.sm, bottom = AppTheme.space.xs),
                    )
                    group.forEachIndexed { index, habit ->
                        OrganizeRow(
                            habit = habit,
                            // 上下按钮是否可用与 moveHabit 的换位口径一致：组端点禁用
                            canMoveUp = index > 0,
                            canMoveDown = index < group.lastIndex,
                            onMove = { delta -> viewModel.moveHabit(id = habit.id, delta = delta) },
                            onCategoryChange = { viewModel.setCategory(id = habit.id, category = it) },
                            onArchivedChange = { viewModel.setArchived(id = habit.id, archived = it) },
                        )
                        Spacer(modifier = Modifier.height(AppTheme.space.sm))
                    }
                }
            }
            val archived = habits.filter { habit -> habit.archived }
            if (archived.isNotEmpty()) {
                Text(
                    text = "已归档",
                    style = texts.cardTitle,
                    modifier = Modifier.padding(top = AppTheme.space.sm, bottom = AppTheme.space.xs),
                )
                archived.forEach { habit ->
                    OrganizeRow(
                        habit = habit,
                        // 归档组沉底独立展示，不参与组内排序（与 moveHabit 的口径一致）
                        canMoveUp = false,
                        canMoveDown = false,
                        onMove = { },
                        onCategoryChange = { viewModel.setCategory(id = habit.id, category = it) },
                        onArchivedChange = { viewModel.setArchived(id = habit.id, archived = it) },
                    )
                    Spacer(modifier = Modifier.height(AppTheme.space.sm))
                }
            }
        }

        Spacer(modifier = Modifier.height(AppTheme.space.xl))
    }
}

/**
 * 整理页单行：名称 + 上移/下移 + 归档开关，下一行是时段分类的 Chips（ ChoiceTile 同风格：
 * 选中 accentSoft 底 + accentInk 字，未选中 card 底 + divider 描边）。
 */
@Composable
private fun OrganizeRow(
    habit: Habit,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMove: (Int) -> Unit,
    onCategoryChange: (String) -> Unit,
    onArchivedChange: (Boolean) -> Unit,
) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = habit.name,
                style = texts.aux.copy(
                    fontWeight = FontWeight.Medium,
                    color = if (habit.archived) colors.secondaryText else colors.primaryText,
                ),
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = AppTheme.space.xs),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            IconButton(
                onClick = { onMove(-1) },
                enabled = canMoveUp,
            ) {
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowUp,
                    contentDescription = "上移",
                    tint = colors.accentInk,
                )
            }
            IconButton(
                onClick = { onMove(1) },
                enabled = canMoveDown,
            ) {
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowDown,
                    contentDescription = "下移",
                    tint = colors.accentInk,
                )
            }
            TextButton(onClick = { onArchivedChange(!habit.archived) }) {
                Text(
                    text = if (habit.archived) "取消归档" else "归档",
                    style = texts.caption.copy(color = colors.accentInk),
                )
            }
        }
        Spacer(modifier = Modifier.height(AppTheme.space.xs))
        // 7 个分类一行放不下：横向滚动，不换 FlowRow（实验 API，收益不值一次 opt-in）
        Row(
            horizontalArrangement = Arrangement.spacedBy(AppTheme.space.xs),
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        ) {
            Habit.CATEGORIES.forEach { category ->
                CategoryChip(
                    label = categoryLabel(category = category),
                    selected = habit.category == category,
                    onClick = { onCategoryChange(category) },
                )
            }
        }
    }
}

/**
 * 分类小 chip：ChoiceTile 同风格。格体只有 ~34dp 高，够不上 48dp 最小可点目标 ——
 * `minimumInteractiveComponentSize` 挂链首只撑不可见的点击槽位（终审 I9）。
 */
@Composable
private fun CategoryChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    Box(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .clip(RoundedCornerShape(AppTheme.radius.md))
            .background(if (selected) colors.accentSoft else colors.card)
            .border(
                width = 1.dp,
                color = if (selected) colors.accentSoft else colors.divider,
                shape = RoundedCornerShape(AppTheme.radius.md),
            )
            .clickable(
                onClick = onClick,
                role = Role.RadioButton,
            )
            .padding(horizontal = AppTheme.space.sm, vertical = AppTheme.space.xs),
    ) {
        Text(
            text = label,
            style = texts.aux.copy(
                color = if (selected) colors.accentInk else colors.secondaryText,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            ),
        )
    }
}
