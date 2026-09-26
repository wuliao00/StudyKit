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
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Create
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
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
import com.studykit.ui.components.AppPill
import com.studykit.ui.components.ConfettiBurst
import com.studykit.ui.components.EmptyState
import com.studykit.ui.components.HabitSnake
import com.studykit.ui.components.RingGauge
import com.studykit.ui.components.SnakeTrackHeight
import com.studykit.ui.components.StatTile
import com.studykit.ui.motion.MotionSpec
import com.studykit.ui.theme.AppTheme
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
 * 圆形打卡按钮：未打卡为描边空心；打卡后铺一层 `successSoft`（勾走 `successInk`）+ `success` 描边，
 * 并伴随一次「弹跳」缩放动效（0.86 按压 → 1.18 回弹）。
 * 数量型/已打卡（改备注）点击交由调用方路由到打卡弹层。
 *
 * 动效全部走 [MotionSpec] 的 spring（`press` 按压回弹 / `snap` 填充淡入），不再用 tween：
 * 逐帧跟随 vsync，高刷屏按 90/120Hz 渲染。填充色只能整色换、不能逐帧插值
 * （`MotionSpec.press/snap` 都是 `spring<Float>`），于是把「`successSoft` ↔ 透明」降成一格 alpha
 * 用同一个 Float spring 补间（拿令牌自身的 alpha 去乘，落定值就是 `successSoft`），
 * 观感等价，也不用自造 Color spring。
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
            .size(AppTheme.size.pill)
            .graphicsLayer(scaleX = scale, scaleY = scale)
            .clip(CircleShape)
            // 未打卡 = **实心 accentInk + onAccent 的勾**：打卡是这一屏每天唯一的动作，
            // 而它原来是全卡最弱的东西（一圈 45% 灰描边、里面什么都没有）—— 一屏五张卡里
            // 最该被点的那一枚反而最不像能被点。实底档按 T15 只认 accent 这一对
            // （`accentInk` 容器 + `onAccent` 内容），与 AppButton 的实底同源。
            // 已打卡 = 原来的 successSoft 柔底 + successInk 勾，保持不变（"做完了"该退下去）。
            .background(
                if (checked) {
                    colors.successSoft.copy(alpha = colors.successSoft.alpha * fillAlpha.coerceIn(0f, 1f))
                } else {
                    colors.accentInk
                },
            )
            .border(
                width = if (checked) 2.dp else 0.dp,
                color = if (checked) colors.success else Color.Transparent,
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
        // 两个状态都画勾：未打卡画**空心勾**（它就是这一枚按钮的动作提示），
        // 已打卡画实心勾。以前未打卡态里面是空的，读起来像个装饰圆点。
        Icon(
            imageVector = if (checked) Icons.Filled.Check else Icons.Outlined.Check,
            contentDescription = null,
            // 图形充当"文字槽位"（旁边没有等价文字），按 T15 走 ink 档：
            // 已打卡 successInk 压 successSoft（4.96:1）；未打卡 onAccent 压 accentInk（5.81:1）。
            tint = if (checked) colors.successInk else colors.onAccent,
            modifier = Modifier.size(26.dp),
        )
    }
}

/** 倒计时/达成文案 */
private fun countdownText(item: HabitItemUi): String = when {
    item.achieved -> "目标已达成"
    item.remainingDays >= 0 -> "剩 ${item.remainingDays} 天到目标"
    else -> "已超额 ${-item.remainingDays} 天"
}

/** 热力图窗口宽度：卡片文案「近 N 周坚持」与网格列数同源，改这里即同时改两处 */
// 20 周 ≈ 4.5 个月：卡内可用宽约 288dp，20 列 × (10dp 方格 + 4dp 间距) 正好铺满；
// 旧版方格热力图时代，周数受卡片宽度限制（8 列时格子被 108dp 画布限到 12dp，整块只占卡宽四成）。
// 换成可左右滑的单条贪吃蛇之后，周数只决定轨道**长度**（一节一天），不再决定格子大小，
// 所以这里可以放心留 20 周；轨道尺寸见 ui/components/HabitSnake.kt 的那几枚 private 度量。
private const val HEATMAP_WEEKS = 20

/**
 * 习惯列表页：页标题 + 日历入口 + 近 20 周热力图 + 统计磁贴 + 待打卡卡片 + 已打卡折叠区 + 导出分享入口。
 *
 * 颜色与文字样式统一取 `AppTheme`（达成态走 `goldInk`：金色作字/作细环在浅色主题不达 AA），
 * 间距/圆角取 `AppTheme.space` / `AppTheme.radius` 的 dp 常量。
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
    onFocusHabit: (Long) -> Unit,
    onAddClick: () -> Unit,
    onOpenCalendar: () -> Unit,
    onOpenContracts: () -> Unit,
    onOpenOrganize: () -> Unit,
) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snakeBest by viewModel.snakeBest.collectAsStateWithLifecycle()
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
        // 整页只有这一个可滚动列表：此前表头（日历入口 + 热力图 + 统计磁贴）钉在不滚动的
        // Column 里，把列表视口压到只剩约 193dp（真机实测：习惯卡片只能露出一张多半张），
        // 主内容反而要在一条窄缝里滚。改成表头也作为 item 随内容一起滚。
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = AppTheme.space.pageH),
            contentPadding = PaddingValues(
                top = AppTheme.space.sm,
                bottom = AppTheme.space.xl,
            ),
            verticalArrangement = Arrangement.spacedBy(AppTheme.space.md),
        ) {
            item(key = "header") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(text = "习惯", style = texts.largeTitle)
                    Spacer(Modifier.weight(1f))
                    // 整理入口（v2.4 批次四）：一个文字按钮即可 —— 与「添加」同款写法
                    TextButton(onClick = onOpenOrganize) {
                        Text(
                            text = "整理",
                            style = texts.aux.copy(
                                color = colors.accentInk,
                                fontWeight = FontWeight.Medium,
                            ),
                        )
                    }
                    TextButton(onClick = onAddClick) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = null,
                            tint = colors.accentInk,
                        )
                        Spacer(Modifier.width(AppTheme.space.xs))
                        Text(
                            text = "添加",
                            style = texts.aux.copy(
                                color = colors.accentInk,
                                fontWeight = FontWeight.Medium,
                            ),
                        )
                    }
                }
            }
            item(key = "calendar_entry") { CalendarEntryCard(onClick = onOpenCalendar) }
            // 自我契约入口（v2.4 批次五）：与日历卡同款形态，紧挨着放
            item(key = "contracts_entry") { ContractsEntryCard(onClick = onOpenContracts) }
            // 空账号不出全灰热力图（没有任何事实可画时它只是噪声），有习惯才亮出这一卡
            if (state.items.isNotEmpty()) {
                item(key = "heatmap") {
                    HeatmapCard(
                        activeDays = state.items.flatMap { it.checkedDates }.toSet(),
                        best = snakeBest,
                        onScore = viewModel::submitSnakeScore,
                    )
                }
            }
            item(key = "stat_tiles") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Max),
                    horizontalArrangement = Arrangement.spacedBy(AppTheme.space.sm),
                ) {
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
            }
            if (state.items.isEmpty()) {
                item(key = "empty") {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Spacer(Modifier.height(AppTheme.space.xl * 2))
                        EmptyState(
                            title = "还没有习惯",
                            caption = "每天坚持一小步，21 天养成一个习惯",
                        )
                        Spacer(Modifier.height(AppTheme.space.lg))
                        AppButton(text = "创建第一个习惯", onClick = onAddClick)
                    }
                }
            } else {
                val pending = state.items.filter { !it.checkedInToday }
                val done = state.items.filter { it.checkedInToday }

                items(pending, key = { it.habit.id }) { item ->
                    HabitCard(
                        item = item,
                        modifier = Modifier.animateItem(),
                        onClick = { onOpenHabit(item.habit.id) },
                        onCheckIn = { onCheckInClick(item) },
                        onFocus = { onFocusHabit(item.habit.id) },
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
                                onFocus = { onFocusHabit(item.habit.id) },
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

/**
 * 自我契约入口卡片（v2.4 批次五）：与日历卡同款形态，文案把"对账"两个字挑明 ——
 * 契约不是许愿，到期是要按打卡记录算账的。
 */
@Composable
private fun ContractsEntryCard(onClick: () -> Unit) {
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
                    .background(colors.goldSoft),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Create,
                    contentDescription = null,
                    tint = colors.goldInk,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.width(AppTheme.space.md))
            Column(Modifier.weight(1f)) {
                Text(text = "自我契约", style = texts.cardTitle)
                Spacer(Modifier.height(2.dp))
                Text(text = "写下来，到期对账", style = texts.caption)
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "进入自我契约",
                tint = colors.secondaryText,
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
            Spacer(Modifier.width(AppTheme.space.md))
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
 * 近 20 周坚持卡（可左右滑的贪吃蛇）：全部习惯的打卡日期并集，一天一节，
 * 打过的那节发亮、漏掉的那节是暗色，蛇头在今天，进来时自动滚到那一端。
 *
 * 语义提醒：按天聚合，多个习惯同日打卡也只是一节亮（不是更深的色阶），
 * 「窗口起点早于习惯创建日」的那几天仍是暗色节。轨道是**单条横向**的，
 * 星期不再对应某一行；要看星期分布去「日历」页按周看。
 */
@Composable
private fun HeatmapCard(activeDays: Set<LocalDate>, best: Int, onScore: (Int) -> Unit) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    var playing by rememberSaveable { mutableStateOf(false) }
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (playing) "贪吃蛇 · 近 20 周" else "近 $HEATMAP_WEEKS 周坚持",
                style = texts.cardTitle,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { playing = !playing }) {
                Text(
                    text = if (playing) "← 返回热力图" else "玩一把",
                    style = texts.caption,
                    color = colors.accentInk,
                )
            }
        }
        if (playing) {
            SnakeBoard(
                activeDays = activeDays,
                best = best,
                onScore = onScore,
            )
        } else {
            Text(text = "一天一节，亮的是打过卡的日子 · 左右滑动看更早的", style = texts.caption)
            Spacer(Modifier.height(AppTheme.space.sm))
            HabitSnake(
                activeDays = activeDays,
                weeks = HEATMAP_WEEKS,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(SnakeTrackHeight),
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
            Spacer(Modifier.width(AppTheme.space.md))
            Column(Modifier.weight(1f)) {
                Text(text = "今日已打卡 · $count", style = texts.cardTitle)
                Spacer(Modifier.height(2.dp))
                Text(text = "完成的事，安静地收在这里", style = texts.caption)
            }
            Text(
                text = if (expanded) "收起" else "展开",
                style = texts.caption.copy(color = colors.accentInk),
            )
            Spacer(Modifier.width(AppTheme.space.xs))
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
        Spacer(Modifier.height(AppTheme.space.md))
        Row(horizontalArrangement = Arrangement.spacedBy(AppTheme.space.sm)) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    // 两块都只有约 36dp 高（15sp 文案 + 上下 8dp），不到 48dp 最小可点目标
                    // （终审 I9）。挂在链首只撑大**不可见的点击槽位**，
                    // clip/background 在它下游 ⇒ 看到的色块大小与配色一分不动。
                    .minimumInteractiveComponentSize()
                    .clip(RoundedCornerShape(AppTheme.radius.md))
                    .background(colors.accentSoft)
                    .clickable(onClick = onShare)
                    .padding(vertical = AppTheme.space.sm),
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
                    .minimumInteractiveComponentSize()
                    .clip(RoundedCornerShape(AppTheme.radius.md))
                    .background(colors.accentSoft)
                    .clickable(onClick = onExport)
                    .padding(vertical = AppTheme.space.sm),
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
 * 习惯卡片：图标 + 名称（达成标记）+ 进度文案 + 倒计时 + 最近备注 + 进度环 + 打卡按钮 + 专注入口。
 * 达成后整体转金色态（`goldInk` 档）；进度环用共享组件 [RingGauge]，换色策略仍留在本页（达成 = goldInk）。
 */
@Composable
private fun HabitCard(
    item: HabitItemUi,
    onClick: () -> Unit,
    onCheckIn: () -> Unit,
    onFocus: () -> Unit,
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
                // 表情图标位：纯装饰（紧邻的习惯名就是它的等价朗读），不清掉语义的话
                // TalkBack 会在每个习惯前多念一个表情名（「书本 阅读」「靶子 刷题」，终审 I9）。
                Text(
                    text = habitIconEmoji(item.habit.icon),
                    fontSize = texts.aux.fontSize,
                    modifier = Modifier.clearAndSetSemantics { },
                )
            }
            Spacer(Modifier.width(AppTheme.space.md))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 标题必须是**带 weight 的那一个**：Row 先量无 weight 的药丸（拿到自身固有宽度），
                    // 再把剩下的宽度给标题。反过来（标题无 weight）药丸就会被挤成「已达…」，
                    // 而 `weight(1f, fill = false)` 实测会把标题压成「…」——两个方向都真机踩过。
                    // 于是长习惯名由省略号收尾，状态药丸永远完整。
                    Text(
                        text = item.habit.name,
                        style = texts.cardTitle,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (item.achieved) {
                        Spacer(Modifier.width(AppTheme.space.sm))
                        // 「已达成」标记：与单词库/书架/错题本的药丸同一份实现（波 3 收口），
                        // 金色档按 T15 走 goldSoft 底 + goldInk 字
                        AppPill(
                            container = colors.goldSoft,
                            ink = colors.goldInk,
                            label = "已达成",
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    // 时段分类（v2.4 批次四）：副标题行追加时段名；ANY 是"没绑例程"，不显示
                    text = buildString {
                        append(
                            if (item.isCountType) {
                                "累计 ${item.progressText} · 连续 ${item.streak} 天"
                            } else {
                                "连续 ${item.streak} 天 · 累计 ${item.totalCheckDays} 天"
                            },
                        )
                        if (item.habit.category != Habit.CATEGORY_ANY) {
                            append(" · ${categoryLabel(category = item.habit.category)}")
                        }
                    },
                    style = texts.caption,
                )
                Spacer(Modifier.height(1.dp))
                Text(
                    text = countdownText(item),
                    style = texts.caption.copy(
                        color = if (item.achieved) colors.goldInk else colors.secondaryText,
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
            Spacer(Modifier.width(AppTheme.space.sm))
            Box(contentAlignment = Alignment.Center) {
                RingGauge(
                    progress = item.progress,
                    modifier = Modifier.size(AppTheme.size.pill),
                    strokeWidth = 5.dp,
                    color = if (item.achieved) colors.goldInk else colors.accent,
                )
                Text(
                    text = if (item.achieved) "✓" else "${(item.progress * 100).toInt()}%",
                    // 达成态这里画的是一个「✓」字符，而同一张卡上已经有「已达成」药丸与
                    // 「目标已达成」那一行 —— 三处同义，读屏只需要一份（终审 I9），故把
                    // 这枚对勾标成装饰性。**未达成时不摘**：那串百分比是唯一一处播报进度，
                    // 清掉就等于把进度对读屏彻底弄丢了。图形（环 + 对勾）不受影响。
                    modifier = if (item.achieved) {
                        Modifier.clearAndSetSemantics { }
                    } else {
                        Modifier
                    },
                    style = texts.caption.copy(
                        fontWeight = FontWeight.Medium,
                        color = if (item.achieved) colors.goldInk else colors.primaryText,
                    ),
                )
            }
        }
        Spacer(Modifier.height(AppTheme.space.sm))
        // ── 动作行（2026-09-26 重排）─────────────────────────────────
        // 这一行曾经和上面的文字**挤在同一个 Row 里**。那份布局从未成立过：
        // 图标 44dp + 进度环 52dp + 「专注」按钮 ~58dp + 打卡圆钮 52dp + 三段间距，
        // 在 360dp 宽的屏上固定元素合计 ~246dp，`weight(1f)` 的文字列只剩 **42dp** ——
        // 一行放不下一个词，于是「连续 0 天 · 累计 1 天」变成一个字一行的竖条
        // （2026-09-24 的旧截图 `walk/s105_habits_cards.png` 就是这个样子，不是 2.4.3 改坏的）。
        // 拆成两行之后文字列拿到 ~176dp，副标题回到一行；两枚动作靠右、仍然都够 48dp 触达。
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End,
        ) {
            Spacer(Modifier.weight(1f))
            // 「专注」入口（v2.4 批次三）：与玩一把同一份小文字按钮写法，
            // 紧挨打卡按钮 —— 它本来就是"换一种方式完成今天"的备选
            TextButton(onClick = onFocus) {
                Text(
                    text = "专注",
                    style = texts.caption.copy(color = colors.accentInk),
                )
            }
            Spacer(Modifier.width(AppTheme.space.md))
            CheckInButton(checked = item.checkedInToday, onClick = onCheckIn)
        }
    }
}
