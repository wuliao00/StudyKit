package com.studykit.ui.habit

import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.studykit.data.entity.CheckIn
import com.studykit.data.entity.Habit
import com.studykit.ui.components.AppButton
import com.studykit.ui.components.AppCard
import com.studykit.ui.components.ConfettiBurst
import com.studykit.ui.components.EmptyState
import com.studykit.ui.components.HeatmapWeeks
import com.studykit.ui.components.RingGauge
import com.studykit.ui.components.StatTile
import com.studykit.ui.motion.MotionSpec
import com.studykit.ui.theme.AppTheme
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

/**
 * 圆形打卡按钮：未打卡为描边空心；打卡后变实心成功色，
 * 并伴随一次「弹跳」缩放动效（0.86 按压 → 1.18 回弹）。
 * 数量型/已打卡（改备注）点击交由调用方路由到打卡弹层。
 *
 * 动效全部走 [MotionSpec] 的 spring（`press` 按压回弹 / `snap` 填充淡入），不再用 tween：
 * 逐帧跟随 vsync，高刷屏按 90/120Hz 渲染。填充色只能整色换、不能逐帧插值
 * （`MotionSpec.press/snap` 都是 `spring<Float>`），于是把「实心 ↔ 透明」降成一格 alpha
 * 用同一个 Float spring 补间，观感等价，也不用自造 Color spring。
 */
@Composable
private fun CheckInButton(
    checked: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
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
            delay(MotionSpec.FadeMs.toLong())
            pop = false
        }
    }
    val scale by animateFloatAsState(
        targetValue = when {
            pressed -> 0.86f
            pop -> 1.18f
            else -> 1f
        },
        animationSpec = MotionSpec.press,
        label = "checkScale",
    )
    // spring 会过冲，alpha 可能短暂越过 1f，故显式钳制
    val fillAlpha by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = MotionSpec.snap,
        label = "checkFill",
    )
    Box(
        modifier = modifier
            .size(52.dp)
            .graphicsLayer(scaleX = scale, scaleY = scale)
            .clip(CircleShape)
            .background(colors.success.copy(alpha = fillAlpha.coerceIn(0f, 1f)))
            .border(
                width = 2.dp,
                color = if (checked) colors.success else colors.secondaryText.copy(alpha = 0.45f),
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
                // 勾落在 success 实底上：旧值 DesignTokens.Card 是硬白，夜间主题会把深墨勾
                // 压在亮绿上（1.9:1）；onAccent 才是「实底 accent/success 容器上的字色」令牌
                tint = colors.onAccent,
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

/** 热力图窗口宽度：卡片文案「近 N 周坚持」与网格列数同源，改这里即同时改两处 */
private const val HEATMAP_WEEKS = 8

/** 热力图画布高度：7 行 × 12dp 方格 + 6 × 4dp 间距（[HeatmapWeeks] 取宽高中较小者定边长并居中） */
private val heatmapCanvasHeight: Dp = 108.dp

/**
 * 习惯列表页：页标题 + 日历入口 + 近 8 周热力图 + 统计磁贴 + 待打卡卡片 + 已打卡折叠区 + 导出分享入口。
 *
 * 颜色与文字样式统一取 `AppTheme`（`gold` 仍是达成态专用色），间距/圆角仍走 [DesignTokens] 的 dp 常量。
 * 进度环改用共享组件 [RingGauge]（达成换色逻辑保留在本页），打卡庆祝改用 [ConfettiBurst]：
 * 叠在**页面级** `Box` 的 `matchParentSize` 层上，而不是卡片内部 —— 打卡成功的卡片会在同一帧
 * 从「待打卡」迁进默认折叠的「今日已打卡」区而卸载，卡片内的粒子根本出不了画；
 * 且 [AppCard] 的 Surface 带圆角裁剪，会把飞越框外的粒子切成方框（见 ConfettiBurst KDoc）。
 * 列表条目挂 `Modifier.animateItem()`：卡片在待打卡/已打卡两区之间搬家时有让位与淡入淡出。
 */
@Composable
fun HabitListScreen(
    viewModel: HabitViewModel,
    onOpenHabit: (Long) -> Unit,
    onAddClick: () -> Unit,
    onOpenCalendar: () -> Unit,
) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var expandedDone by remember { mutableStateOf(false) }
    var sheetTarget by remember { mutableStateOf<SheetTarget?>(null) }

    // 庆祝事件 = (刚打上卡的习惯 id, 事件序号)；序号保证同一习惯二次触发也会重播粒子
    var celebration by remember { mutableStateOf<Pair<Long, Int>?>(null) }
    var celebrationCount by remember { mutableStateOf(0) }
    // null = 还没见过任何一帧非空数据；用它把「冷启动时早已打好的卡」挡在事件之外
    var checkedIdsSnapshot by remember { mutableStateOf<Set<Long>?>(null) }
    LaunchedEffect(state.items) {
        val checked = state.items.filter { it.checkedInToday }.mapTo(mutableSetOf()) { it.habit.id }
        val previous = checkedIdsSnapshot
        if (previous == null) {
            if (state.items.isNotEmpty()) checkedIdsSnapshot = checked
            return@LaunchedEffect
        }
        checkedIdsSnapshot = checked
        val newcomer = (checked - previous).firstOrNull()
        if (newcomer != null) {
            celebrationCount += 1
            celebration = newcomer to celebrationCount
        }
    }

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

    Box(modifier = Modifier.fillMaxSize()) {
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
                Text(text = "习惯", style = texts.largeTitle)
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
            CalendarEntryCard(onClick = onOpenCalendar)
            // 空账号不出全灰热力图（没有任何事实可画时它只是噪声），有习惯才亮出这一卡
            if (state.items.isNotEmpty()) {
                Spacer(Modifier.height(DesignTokens.SpacingMd))
                HeatmapCard(activeDays = state.items.flatMap { it.checkedDates }.toSet())
            }
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
                            modifier = Modifier.animateItem(),
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
                                modifier = Modifier.animateItem(),
                            )
                        }
                        if (expandedDone) {
                            items(done, key = { "done_${it.habit.id}" }) { item ->
                                HabitCard(
                                    item = item,
                                    modifier = Modifier.animateItem(),
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

        celebration?.let { (habitId, event) ->
            ConfettiBurst(
                trigger = habitId to event,
                modifier = Modifier.matchParentSize(),
            )
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
}

/** 全局日历入口卡片：打卡记录 × 系统日程 */
@Composable
private fun CalendarEntryCard(onClick: () -> Unit) {
    val colors = AppTheme.colors
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
                    .background(colors.accentSoft),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.DateRange,
                    contentDescription = null,
                    tint = colors.accentInk,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.width(DesignTokens.SpacingMd))
            Column(Modifier.weight(1f)) {
                Text(text = "日历", style = texts.cardTitle)
                Spacer(Modifier.height(2.dp))
                Text(text = "打卡记录 × 系统日程，看看明天的安排", style = texts.caption)
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "进入日历",
                tint = colors.secondaryText,
            )
        }
    }
}

/**
 * 近 8 周打卡热力图卡：全部习惯的打卡日期并集，格子只区分「打过 / 没打过」。
 *
 * 语义提醒：热力图按天聚合，多个习惯同日打卡也只是一个格子（不是更深的色阶），
 * 「窗口起点早于习惯创建日」的那几天同样落在灰格子里 —— 与 GitHub 一样的读法。
 */
@Composable
private fun HeatmapCard(activeDays: Set<LocalDate>) {
    val texts = AppTheme.texts
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Text(text = "近 $HEATMAP_WEEKS 周坚持", style = texts.cardTitle)
        Spacer(Modifier.height(DesignTokens.SpacingSm))
        HeatmapWeeks(
            activeDays = activeDays,
            weeks = HEATMAP_WEEKS,
            modifier = Modifier
                .fillMaxWidth()
                .height(heatmapCanvasHeight),
        )
    }
}

/** 「今日已打卡」折叠区头部：计数 + 展开/收起箭头 */
@Composable
private fun DoneFoldHeader(
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = MotionSpec.snap,
        label = "foldArrow",
    )
    AppCard(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(colors.successSoft),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = colors.success,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.width(DesignTokens.SpacingMd))
            Column(Modifier.weight(1f)) {
                Text(text = "今日已打卡 · $count", style = texts.cardTitle)
                Spacer(Modifier.height(2.dp))
                Text(text = "完成的事，安静地收在这里", style = texts.caption)
            }
            Text(
                text = if (expanded) "收起" else "展开",
                style = texts.caption.copy(color = colors.accentInk),
            )
            Spacer(Modifier.width(DesignTokens.SpacingXs))
            Icon(
                imageVector = Icons.Filled.KeyboardArrowDown,
                contentDescription = if (expanded) "收起" else "展开",
                tint = colors.accentInk,
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
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Text(text = "记录与分享", style = texts.cardTitle)
        Spacer(Modifier.height(2.dp))
        Text(
            text = "生成「我坚持了 X 天」分享文本，或导出全部打卡记录",
            style = texts.caption,
        )
        Spacer(Modifier.height(DesignTokens.SpacingMd))
        Row(horizontalArrangement = Arrangement.spacedBy(DesignTokens.SpacingSm)) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(DesignTokens.CornerRadius))
                    .background(colors.accentSoft)
                    .clickable(onClick = onShare)
                    .padding(vertical = DesignTokens.SpacingSm),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "分享成就",
                    style = texts.aux.copy(
                        color = colors.accentInk,
                        fontWeight = FontWeight.Medium,
                    ),
                )
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(DesignTokens.CornerRadius))
                    .background(colors.accentSoft)
                    .clickable(onClick = onExport)
                    .padding(vertical = DesignTokens.SpacingSm),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "导出记录（CSV）",
                    style = texts.aux.copy(
                        color = colors.accentInk,
                        fontWeight = FontWeight.Medium,
                    ),
                )
            }
        }
    }
}

/**
 * 习惯卡片：图标 + 名称（达成标记）+ 进度文案 + 倒计时 + 最近备注 + 进度环 + 打卡按钮。
 * 达成后整体转金色态；进度环用共享组件 [RingGauge]，换色策略仍留在本页（达成 = gold）。
 */
@Composable
private fun HabitCard(
    item: HabitItemUi,
    onClick: () -> Unit,
    onCheckIn: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    AppCard(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(
                        if (item.achieved) colors.goldSoft else colors.accentSoft,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = habitIconEmoji(item.habit.icon), fontSize = texts.aux.fontSize)
            }
            Spacer(Modifier.width(DesignTokens.SpacingMd))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = item.habit.name,
                        style = texts.cardTitle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (item.achieved) {
                        Spacer(Modifier.width(DesignTokens.SpacingSm))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(colors.goldSoft)
                                .padding(horizontal = DesignTokens.SpacingSm, vertical = 2.dp),
                        ) {
                            Text(
                                text = "已达成",
                                style = texts.caption.copy(
                                    color = colors.gold,
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
                    style = texts.caption,
                )
                Spacer(Modifier.height(1.dp))
                Text(
                    text = countdownText(item),
                    style = texts.caption.copy(
                        color = if (item.achieved) colors.gold else colors.secondaryText,
                        fontWeight = if (item.achieved) FontWeight.Medium else FontWeight.Normal,
                    ),
                )
                if (item.latestNote.isNotBlank()) {
                    Spacer(Modifier.height(1.dp))
                    Text(
                        text = "「${item.latestNote}」",
                        style = texts.caption,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(DesignTokens.SpacingSm))
            Box(contentAlignment = Alignment.Center) {
                RingGauge(
                    progress = item.progress,
                    modifier = Modifier.size(52.dp),
                    strokeWidth = 5.dp,
                    color = if (item.achieved) colors.gold else colors.accent,
                )
                Text(
                    text = if (item.achieved) "✓" else "${(item.progress * 100).toInt()}%",
                    style = texts.caption.copy(
                        fontWeight = FontWeight.Medium,
                        color = if (item.achieved) colors.gold else colors.primaryText,
                    ),
                )
            }
            Spacer(Modifier.width(DesignTokens.SpacingMd))
            CheckInButton(checked = item.checkedInToday, onClick = onCheckIn)
        }
    }
}
