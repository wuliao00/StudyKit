package com.studykit.ui.habit

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.studykit.data.entity.CheckIn
import com.studykit.data.entity.Habit
import com.studykit.ui.components.AppButton
import com.studykit.ui.components.AppCard
import com.studykit.ui.components.EmptyState
import com.studykit.ui.components.StatTile
import com.studykit.ui.theme.DesignTokens
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate

/** 打卡弹层目标：习惯 + 日期 + 既有记录 + 是否补卡 */
private data class SheetTarget(
    val habit: Habit,
    val date: LocalDate,
    val existing: CheckIn?,
    val isMakeUp: Boolean,
)

/** 目标进度环：底环为分割线色，进度弧为强调色，达成后转金色 */
@Composable
private fun ProgressRing(progress: Float, modifier: Modifier = Modifier) {
    val animated by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(DesignTokens.AnimDurationMs, easing = DesignTokens.AnimEasing),
        label = "progressRing",
    )
    val arcColor = if (progress >= 1f) DesignTokens.Gold else DesignTokens.Accent
    Canvas(modifier = modifier) {
        val strokeWidth = 5.dp.toPx()
        val inset = strokeWidth / 2f
        val arcSize = size.minDimension - strokeWidth
        val topLeft = androidx.compose.ui.geometry.Offset(inset, inset)
        drawArc(
            color = DesignTokens.Divider,
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = topLeft,
            size = androidx.compose.ui.geometry.Size(arcSize, arcSize),
            style = Stroke(strokeWidth),
        )
        if (animated > 0f) {
            drawArc(
                color = arcColor,
                startAngle = -90f,
                sweepAngle = 360f * animated,
                useCenter = false,
                topLeft = topLeft,
                size = androidx.compose.ui.geometry.Size(arcSize, arcSize),
                style = Stroke(strokeWidth, cap = StrokeCap.Round),
            )
        }
    }
}

/**
 * 圆形打卡按钮：未打卡为描边空心；打卡后变实心成功色，
 * 并伴随一次「弹跳」缩放动效（0.86 按压 → 1.18 回弹）。
 * 数量型/已打卡（改备注）点击交由调用方路由到打卡弹层。
 */
@Composable
private fun CheckInButton(
    checked: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    var appeared by remember { mutableStateOf(false) }
    var pop by remember { mutableStateOf(false) }
    LaunchedEffect(checked) {
        if (!appeared) {
            appeared = true
            return@LaunchedEffect
        }
        if (checked) {
            pop = true
            delay(DesignTokens.AnimDurationMs.toLong())
            pop = false
        }
    }
    val scale by animateFloatAsState(
        targetValue = when {
            pressed -> 0.86f
            pop -> 1.18f
            else -> 1f
        },
        animationSpec = tween(200, easing = DesignTokens.AnimEasing),
        label = "checkScale",
    )
    val fill by animateColorAsState(
        targetValue = if (checked) DesignTokens.Success else Color.Transparent,
        animationSpec = tween(200, easing = DesignTokens.AnimEasing),
        label = "checkFill",
    )
    Box(
        modifier = modifier
            .size(52.dp)
            .graphicsLayer(scaleX = scale, scaleY = scale)
            .clip(CircleShape)
            .background(fill)
            .border(
                width = 2.dp,
                color = if (checked) DesignTokens.Success else DesignTokens.SecondaryText.copy(alpha = 0.45f),
                shape = CircleShape,
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .semantics {
                contentDescription = when {
                    checked -> "今日已打卡"
                    else -> "打卡"
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = DesignTokens.Card,
                modifier = Modifier.size(26.dp),
            )
        }
    }
}

/** 倒计时/达成文案 */
private fun countdownText(item: HabitItemUi): String = when {
    item.achieved -> "目标已达成"
    item.remainingDays >= 0 -> "剩 ${item.remainingDays} 天到目标"
    else -> "已超额 ${-item.remainingDays} 天"
}

/** 习惯列表页：页标题 + 统计磁贴 + 待打卡卡片 + 已打卡折叠区 + 导出分享入口 */
@Composable
fun HabitListScreen(
    viewModel: HabitViewModel,
    onOpenHabit: (Long) -> Unit,
    onAddClick: () -> Unit,
    onOpenCalendar: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var expandedDone by remember { mutableStateOf(false) }
    var sheetTarget by remember { mutableStateOf<SheetTarget?>(null) }

    /** 打开打卡弹层（先读取当日既有记录用于预填） */
    fun openSheet(habit: Habit) {
        scope.launch {
            val today = LocalDate.now()
            val existing = viewModel.findCheckIn(habit.id, today)
            sheetTarget = SheetTarget(habit, today, existing, isMakeUp = false)
        }
    }

    /** 打卡按钮路由：数量型进弹层；天数型未打卡一键打卡；已打卡可改备注 */
    fun onCheckInClick(item: HabitItemUi) {
        when {
            item.isCountType -> openSheet(item.habit)
            !item.checkedInToday -> viewModel.checkIn(item.habit)
            else -> openSheet(item.habit)
        }
    }

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
            Text(text = "习惯", style = DesignTokens.LargeTitle)
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
        CalendarEntryCard(onClick = onOpenCalendar)
        Spacer(Modifier.height(DesignTokens.SpacingMd))
        Row(horizontalArrangement = Arrangement.spacedBy(DesignTokens.SpacingSm)) {
            StatTile(
                value = "${state.checkedTodayCount}",
                label = "今日已打卡",
                modifier = Modifier.weight(1f),
            )
            StatTile(
                value = "${state.items.size}",
                label = "习惯总数",
                modifier = Modifier.weight(1f),
            )
            StatTile(
                value = "${state.maxStreak}",
                label = "最长连续",
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(DesignTokens.SpacingLg))

        if (state.items.isEmpty()) {
            Spacer(Modifier.height(DesignTokens.SpacingXl * 2))
            EmptyState(
                title = "还没有习惯",
                caption = "每天坚持一小步，21 天养成一个习惯",
            )
            Spacer(Modifier.height(DesignTokens.SpacingLg))
            AppButton(text = "创建第一个习惯", onClick = onAddClick)
        } else {
            val pending = state.items.filter { !it.checkedInToday }
            val done = state.items.filter { it.checkedInToday }

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(DesignTokens.SpacingMd),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(pending, key = { it.habit.id }) { item ->
                    HabitCard(
                        item = item,
                        onClick = { onOpenHabit(item.habit.id) },
                        onCheckIn = { onCheckInClick(item) },
                    )
                }
                if (done.isNotEmpty()) {
                    item(key = "done_fold_header") {
                        DoneFoldHeader(
                            count = done.size,
                            expanded = expandedDone,
                            onToggle = { expandedDone = !expandedDone },
                        )
                    }
                    if (expandedDone) {
                        items(done, key = { "done_${it.habit.id}" }) { item ->
                            HabitCard(
                                item = item,
                                onClick = { onOpenHabit(item.habit.id) },
                                onCheckIn = { onCheckInClick(item) },
                            )
                        }
                    }
                }
                item(key = "record_actions") {
                    RecordActionsCard(
                        onShare = { viewModel.shareAchievement() },
                        onExport = { viewModel.exportCsv() },
                    )
                }
                item { Spacer(Modifier.height(DesignTokens.SpacingMd)) }
            }
        }
    }

    sheetTarget?.let { target ->
        CheckInSheet(
            habit = target.habit,
            date = target.date,
            existing = target.existing,
            isMakeUp = target.isMakeUp,
            onDismiss = { sheetTarget = null },
            onConfirm = { note, amount ->
                viewModel.submitCheckIn(target.habit, target.date, note, amount)
                sheetTarget = null
            },
        )
    }
}

/** 全局日历入口卡片：打卡记录 × 系统日程 */
@Composable
private fun CalendarEntryCard(onClick: () -> Unit) {
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
                    .background(DesignTokens.Accent.copy(alpha = 0.10f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.DateRange,
                    contentDescription = null,
                    tint = DesignTokens.Accent,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.width(DesignTokens.SpacingMd))
            Column(Modifier.weight(1f)) {
                Text(text = "日历", style = DesignTokens.CardTitle)
                Spacer(Modifier.height(2.dp))
                Text(text = "打卡记录 × 系统日程，看看明天的安排", style = DesignTokens.Caption)
            }
            Icon(
                imageVector = Icons.Filled.KeyboardArrowRight,
                contentDescription = "进入日历",
                tint = DesignTokens.SecondaryText,
            )
        }
    }
}

/** 「今日已打卡」折叠区头部：计数 + 展开/收起箭头 */
@Composable
private fun DoneFoldHeader(
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(DesignTokens.AnimDurationMs, easing = DesignTokens.AnimEasing),
        label = "foldArrow",
    )
    AppCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(DesignTokens.Success.copy(alpha = 0.10f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = DesignTokens.Success,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.width(DesignTokens.SpacingMd))
            Column(Modifier.weight(1f)) {
                Text(text = "今日已打卡 · $count", style = DesignTokens.CardTitle)
                Spacer(Modifier.height(2.dp))
                Text(text = "完成的事，安静地收在这里", style = DesignTokens.Caption)
            }
            Text(
                text = if (expanded) "收起" else "展开",
                style = DesignTokens.Caption.copy(color = DesignTokens.Accent),
            )
            Spacer(Modifier.width(DesignTokens.SpacingXs))
            Icon(
                imageVector = Icons.Filled.KeyboardArrowDown,
                contentDescription = if (expanded) "收起" else "展开",
                tint = DesignTokens.Accent,
                modifier = Modifier.graphicsLayer(rotationZ = arrowRotation),
            )
        }
    }
}

/** 记录与分享卡片：成就分享文本 + CSV 导出 */
@Composable
private fun RecordActionsCard(
    onShare: () -> Unit,
    onExport: () -> Unit,
) {
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Text(text = "记录与分享", style = DesignTokens.CardTitle)
        Spacer(Modifier.height(2.dp))
        Text(
            text = "生成「我坚持了 X 天」分享文本，或导出全部打卡记录",
            style = DesignTokens.Caption,
        )
        Spacer(Modifier.height(DesignTokens.SpacingMd))
        Row(horizontalArrangement = Arrangement.spacedBy(DesignTokens.SpacingSm)) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(DesignTokens.CornerRadius))
                    .background(DesignTokens.Accent.copy(alpha = 0.10f))
                    .clickable(onClick = onShare)
                    .padding(vertical = DesignTokens.SpacingSm),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "分享成就",
                    style = DesignTokens.Auxiliary.copy(
                        color = DesignTokens.Accent,
                        fontWeight = FontWeight.Medium,
                    ),
                )
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(DesignTokens.CornerRadius))
                    .background(DesignTokens.Accent.copy(alpha = 0.10f))
                    .clickable(onClick = onExport)
                    .padding(vertical = DesignTokens.SpacingSm),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "导出记录（CSV）",
                    style = DesignTokens.Auxiliary.copy(
                        color = DesignTokens.Accent,
                        fontWeight = FontWeight.Medium,
                    ),
                )
            }
        }
    }
}

/**
 * 习惯卡片：图标 + 名称（达成标记）+ 进度文案 + 倒计时 + 最近备注 + 进度环 + 打卡按钮。
 * 达成后整体转金色态。
 */
@Composable
private fun HabitCard(
    item: HabitItemUi,
    onClick: () -> Unit,
    onCheckIn: () -> Unit,
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
                    .background(
                        if (item.achieved) DesignTokens.Gold.copy(alpha = 0.12f)
                        else DesignTokens.Accent.copy(alpha = 0.10f),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = habitIconEmoji(item.habit.icon), fontSize = DesignTokens.Auxiliary.fontSize)
            }
            Spacer(Modifier.width(DesignTokens.SpacingMd))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = item.habit.name,
                        style = DesignTokens.CardTitle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (item.achieved) {
                        Spacer(Modifier.width(DesignTokens.SpacingSm))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(DesignTokens.Gold.copy(alpha = 0.15f))
                                .padding(horizontal = DesignTokens.SpacingSm, vertical = 2.dp),
                        ) {
                            Text(
                                text = "已达成",
                                style = DesignTokens.Caption.copy(
                                    color = DesignTokens.Gold,
                                    fontWeight = FontWeight.SemiBold,
                                ),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = if (item.isCountType) {
                        "累计 ${item.progressText} · 连续 ${item.streak} 天"
                    } else {
                        "连续 ${item.streak} 天 · 累计 ${item.totalCheckDays} 天"
                    },
                    style = DesignTokens.Caption,
                )
                Spacer(Modifier.height(1.dp))
                Text(
                    text = countdownText(item),
                    style = DesignTokens.Caption.copy(
                        color = if (item.achieved) DesignTokens.Gold else DesignTokens.SecondaryText,
                        fontWeight = if (item.achieved) FontWeight.Medium else FontWeight.Normal,
                    ),
                )
                if (item.latestNote.isNotBlank()) {
                    Spacer(Modifier.height(1.dp))
                    Text(
                        text = "「${item.latestNote}」",
                        style = DesignTokens.Caption,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(DesignTokens.SpacingSm))
            Box(contentAlignment = Alignment.Center) {
                ProgressRing(
                    progress = item.progress,
                    modifier = Modifier.size(52.dp),
                )
                Text(
                    text = if (item.achieved) "✓" else "${(item.progress * 100).toInt()}%",
                    style = DesignTokens.Caption.copy(
                        fontWeight = FontWeight.Medium,
                        color = if (item.achieved) DesignTokens.Gold else DesignTokens.PrimaryText,
                    ),
                )
            }
            Spacer(Modifier.width(DesignTokens.SpacingMd))
            CheckInButton(checked = item.checkedInToday, onClick = onCheckIn)
        }
    }
}
