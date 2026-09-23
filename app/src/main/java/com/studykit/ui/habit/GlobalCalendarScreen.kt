package com.studykit.ui.habit

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.studykit.data.SystemEvent
import com.studykit.data.entity.Habit
import com.studykit.ui.components.AppButton
import com.studykit.ui.components.AppCard
import com.studykit.ui.components.AppPill
import com.studykit.ui.motion.MotionSpec
import com.studykit.ui.theme.AppTheme
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.Instant
import java.time.ZoneId

// 表头与网格格数（WeekHeader / MonthGridCells / padToFullWeeks）在 `MonthGridCommon.kt`，
// 与 `HabitCalendarScreen` 共用一份。下面这几样只有本页用得到，故留在原处：
// 星期全称只服务「8月28日 周五」这类日期文案，习惯日历页的网格用的是单字表头。
private val WeekdayZh = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
private val TimeFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/** 中文星期：如「周五」 */
private fun chineseWeekday(date: LocalDate): String = WeekdayZh[date.dayOfWeek.value - 1]

/** 「8月28日 周五」格式 */
private fun chineseDate(date: LocalDate): String =
    "${date.monthValue}月${date.dayOfMonth}日 ${chineseWeekday(date)}"

/** 事件时间文案：全天事件显示「全天」 */
private fun eventTimeText(event: SystemEvent): String {
    if (event.allDay) return "全天"
    val zone = ZoneId.systemDefault()
    val start = Instant.ofEpochMilli(event.startMillis).atZone(zone).toLocalTime().format(TimeFmt)
    val end = Instant.ofEpochMilli(event.endMillis).atZone(zone).toLocalTime().format(TimeFmt)
    return if (end == start) start else "$start - $end"
}

/**
 * 全局日历页：明日日期卡片 + 月历（叠加打卡点与系统事件徽标）+ 选中日详情。
 * 系统日历需 READ_CALENDARS 权限，被拒时降级为提示卡片，仍可看打卡。
 *
 * 颜色与文字样式统一取 `AppTheme`（间距/圆角/阴影取 `AppTheme.space` / `.radius` / `.elevation`）。
 * 实底强调色容器（明日卡、选中日格）一律 `accentInk` 底 + `onAccent` 字，与 [AppButton] 的
 * 实底按钮同一套；描边与圆点这类不带文字的元素仍用 `accent`。
 * 动效：选中日格的底色与数字墨色**错峰**淡入（底色 `tween(MotionSpec.FadeMs / 3)` 提前落定、
 * 墨色仍 `tween(MotionSpec.FadeMs)`，同速时中段的半实底托半透明白字只有 ≈1.06:1），整格再按
 * `MotionSpec.press` 弹到 1.08 倍；翻月时表头与网格整片走 `AnimatedContent`（淡入 + 1/6 宽短距
 * 滑入，方向跟手势），网格恒为 6 行使两帧等高，出场那一帧的旧网格 `enabled = false` 不吃点击。
 */
@Composable
fun GlobalCalendarScreen(
    viewModel: GlobalCalendarViewModel,
    habitViewModel: HabitViewModel,
    onBack: () -> Unit,
) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    val checkInsByDate by viewModel.checkInsByDate.collectAsStateWithLifecycle()
    val system by viewModel.system.collectAsStateWithLifecycle()
    val habitItems by habitViewModel.uiState.collectAsStateWithLifecycle()
    val makeupAllowed by habitViewModel.makeupAllowed.collectAsStateWithLifecycle()
    // 补打卡：选中「过去 7 天内没打卡」的空日时，日详情卡里给出习惯条
    var makeUpFor by remember { mutableStateOf<Habit?>(null) }
    var month by remember { mutableStateOf(YearMonth.now()) }
    var selectedDate by remember { mutableStateOf(LocalDate.now()) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> viewModel.onPermissionResult(granted) }

    LaunchedEffect(Unit) {
        if (viewModel.hasCalendarPermission()) {
            viewModel.onPermissionResult(true)
        } else {
            permissionLauncher.launch(viewModel.calendarPermissionToRequest())
        }
    }

    val tomorrow = remember { LocalDate.now().plusDays(1) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = AppTheme.space.pageH),
    ) {
        Spacer(Modifier.height(AppTheme.space.sm))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = colors.accentInk,
                )
            }
            Spacer(Modifier.width(AppTheme.space.xs))
            Text(text = "日历", style = texts.pageTitle)
        }

        Spacer(Modifier.height(AppTheme.space.md))

        TomorrowCard(
            tomorrow = tomorrow,
            events = system.tomorrowEvents,
            showEvents = system.permissionGranted && !system.loadError,
        )

        if (!system.permissionGranted) {
            Spacer(Modifier.height(AppTheme.space.md))
            PermissionNoticeCard(onRetry = { permissionLauncher.launch(viewModel.calendarPermissionToRequest()) })
        } else if (system.loadError) {
            Spacer(Modifier.height(AppTheme.space.md))
            Text(text = "系统日历加载失败，可返回重试", style = texts.caption)
        }

        Spacer(Modifier.height(AppTheme.space.md))

        MonthCard(
            month = month,
            onMonthChange = { month = it },
            checkInsByDate = checkInsByDate,
            eventsByDate = system.eventsByDate,
            selectedDate = selectedDate,
            onSelectDate = { selectedDate = it },
        )

        Spacer(Modifier.height(AppTheme.space.md))

        DayDetailCard(
            date = selectedDate,
            checkedHabits = checkInsByDate[selectedDate].orEmpty(),
            events = system.eventsByDate[selectedDate].orEmpty(),
            showEvents = system.permissionGranted && !system.loadError,
            habits = habitItems.items.map { it.habit },
            showMakeUp = makeupAllowed,
            onMakeUp = { makeUpFor = it },
        )
        makeUpFor?.let { habit ->
            CheckInSheet(
                habit = habit,
                date = selectedDate,
                existing = null,
                isMakeUp = true,
                onDismiss = { makeUpFor = null },
                onConfirm = { note, amount ->
                    habitViewModel.submitCheckIn(habit, selectedDate, note, amount, isMakeup = true)
                    makeUpFor = null
                },
            )
        }

        Spacer(Modifier.height(AppTheme.space.lg))
    }
}

/**
 * 明日日期卡片：实底强调色（`accentInk` + `onAccent` 字，双主题各自达 AA）醒目展示
 * 「明天是 X月X日 周X」及明日日程。
 */
@Composable
private fun TomorrowCard(
    tomorrow: LocalDate,
    events: List<SystemEvent>,
    showEvents: Boolean,
) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(AppTheme.radius.lg),
        color = colors.accentInk,
        contentColor = colors.onAccent,
        shadowElevation = AppTheme.elevation.low,
    ) {
        Column(modifier = Modifier.padding(AppTheme.space.card)) {
            Text(
                text = "明天",
                style = texts.caption.copy(color = colors.onAccent.copy(alpha = 0.85f)),
            )
            Spacer(Modifier.height(AppTheme.space.xs))
            Text(
                text = chineseDate(tomorrow),
                style = texts.largeTitle.copy(color = colors.onAccent),
            )
            Spacer(Modifier.height(AppTheme.space.sm))
            if (!showEvents) {
                Text(
                    text = "授权系统日历后可查看明日日程",
                    style = texts.aux.copy(color = colors.onAccent.copy(alpha = 0.85f)),
                )
            } else if (events.isEmpty()) {
                Text(
                    text = "明天暂无日程，安心安排打卡吧",
                    style = texts.aux.copy(color = colors.onAccent.copy(alpha = 0.85f)),
                )
            } else {
                events.take(3).forEach { event ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = AppTheme.space.xs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(colors.onAccent),
                        )
                        Spacer(Modifier.width(AppTheme.space.sm))
                        Text(
                            text = "${eventTimeText(event)}  ${event.title}",
                            style = texts.aux.copy(color = colors.onAccent),
                            maxLines = 1,
                        )
                    }
                }
                if (events.size > 3) {
                    Spacer(Modifier.height(AppTheme.space.xs))
                    Text(
                        text = "还有 ${events.size - 3} 项日程…",
                        style = texts.caption.copy(color = colors.onAccent.copy(alpha = 0.85f)),
                    )
                }
            }
        }
    }
}

/** 权限未授权时的降级提示卡片 */
@Composable
private fun PermissionNoticeCard(onRetry: () -> Unit) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(colors.warningSoft),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.DateRange,
                    contentDescription = null,
                    tint = colors.warning,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(AppTheme.space.md))
            Column(Modifier.weight(1f)) {
                Text(text = "需要日历权限", style = texts.cardTitle)
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "授权后才能显示系统日历事件，打卡记录不受影响",
                    style = texts.caption,
                )
            }
        }
        Spacer(Modifier.height(AppTheme.space.md))
        AppButton(text = "去授权", onClick = onRetry)
    }
}

/**
 * 月历卡片：月份切换 + 网格（叠加打卡点与事件徽标）。
 *
 * 翻月用 [AnimatedContent] 换整片表头+网格：淡入淡出叠 1/6 宽的横向短距滑入，
 * 方向由本页 `slide` 记录（点左箭头 = 从左侧进），月份标题不参与转场。
 * 网格恒 6 行（[MonthGridCells] + [padToFullWeeks]）使转场两帧等高 —— 本页还挂在
 * `verticalScroll` 里，两帧不等高时除了卡片自己抖一下，会连着把下方 DayDetailCard 一起顶一格；
 * 转场期间只有 `shownMonth == month` 的那一片可点，出场的旧网格降级为纯动效，
 * 避免陈旧点击与 TalkBack 读两遍日期。
 */
@Composable
private fun MonthCard(
    month: YearMonth,
    onMonthChange: (YearMonth) -> Unit,
    checkInsByDate: Map<LocalDate, List<String>>,
    eventsByDate: Map<LocalDate, List<SystemEvent>>,
    selectedDate: LocalDate,
    onSelectDate: (LocalDate) -> Unit,
) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    // 翻月方向：+1 = 往未来（新网格从右侧进），-1 = 回过去；与 month 同一帧写入
    var slide by remember { mutableStateOf(0) }

    AppCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            IconButton(onClick = {
                slide = -1
                onMonthChange(month.minusMonths(1))
            }) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    contentDescription = "上个月",
                    tint = colors.accentInk,
                )
            }
            Text(
                text = "${month.year} 年 ${month.monthValue} 月",
                style = texts.cardTitle,
            )
            IconButton(onClick = {
                slide = 1
                onMonthChange(month.plusMonths(1))
            }) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = "下个月",
                    tint = colors.accentInk,
                )
            }
        }

        AnimatedContent(
            targetState = month,
            transitionSpec = {
                val dir = if (slide >= 0) 1 else -1
                // 与 HabitCalendarScreen 同法：淡入淡出走 MotionSpec 工厂，滑入按方向自己给 tween
                val enter = MotionSpec.fadeEnter() +
                    slideInHorizontally(animationSpec = tween(durationMillis = MotionSpec.FadeMs)) {
                        it / 6 * dir
                    }
                val exit = MotionSpec.fadeExit() +
                    slideOutHorizontally(animationSpec = tween(durationMillis = MotionSpec.FadeMs)) {
                        -it / 6 * dir
                    }
                enter.togetherWith(exit)
            },
            modifier = Modifier.fillMaxWidth(),
            label = "globalMonthGrid",
        ) { shownMonth ->
            Column(modifier = Modifier.fillMaxWidth()) {
                Spacer(Modifier.height(AppTheme.space.xs))

                Row(modifier = Modifier.fillMaxWidth()) {
                    WeekHeader.forEach { day ->
                        Text(
                            text = day,
                            style = texts.caption,
                            modifier = Modifier.weight(1f),
                            textAlign = TextAlign.Center,
                        )
                    }
                }

                Spacer(Modifier.height(AppTheme.space.sm))

                val today = LocalDate.now()
                val leadingBlanks = shownMonth.atDay(1).dayOfWeek.value - 1
                val days = List(leadingBlanks) { null } +
                    (1..shownMonth.lengthOfMonth()).map { shownMonth.atDay(it) }
                // 固定 6 行：不足 42 格的月用空位补齐（[padToFullWeeks]，与习惯日历页同一份）。
                // 行高仍由 weight + aspectRatio 自己算、行距仍是行尾的 `AppTheme.space.sm`，
                // 故「6×(cell+gap)」是布局推出来的，不新增任何 dp
                val cells = padToFullWeeks(days = days)

                cells.chunked(7).forEach { row ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = AppTheme.space.sm),
                    ) {
                        row.forEach { date ->
                            if (date == null) {
                                // 空格子与日格同样占满一格：否则整行皆空的补位行会塌成 padding 高，
                                // 6 行的固定高度也就白定了
                                Spacer(Modifier.weight(1f).aspectRatio(1f))
                            } else {
                                GlobalDayCell(
                                    date = date,
                                    isToday = date == today,
                                    isSelected = date == selectedDate,
                                    hasCheckIn = date in checkInsByDate,
                                    hasEvent = date in eventsByDate,
                                    // 转场期间出场的那一片只负责动效，不再改选中态
                                    enabled = shownMonth == month,
                                    onClick = { onSelectDate(date) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                        repeat(7 - row.size) { Spacer(Modifier.weight(1f).aspectRatio(1f)) }
                    }
                }
            }
        }

        // 图例
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LegendDot(color = colors.success, label = "打卡")
            Spacer(Modifier.width(AppTheme.space.md))
            LegendDot(color = colors.accent, label = "系统日程")
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    val texts = AppTheme.texts
    Box(
        modifier = Modifier
            .size(6.dp)
            .clip(CircleShape)
            .background(color),
    )
    Spacer(Modifier.width(AppTheme.space.xs))
    Text(text = label, style = texts.caption)
}

/**
 * 月历单日格：选中为实底强调色（`accentInk` 底 + `onAccent` 字），今天描边；
 * 下方叠加打卡点（`success`）与事件徽标（`accent`）。
 *
 * 选中那一刻底色与数字墨色**错峰**补间：底色 `tween(MotionSpec.FadeMs / 3)` 提前落定，墨色仍
 * `tween(MotionSpec.FadeMs)`。同速补间时中段（f≈0.2–0.8）是「半透明 accentInk 托半透明白字」，
 * 对比 ≈1.06:1，数字会糊掉约 130ms；墨色本身仍必须补间，否则一上来就是 onAccent（浅色＝白）
 * 压在还没实底的白卡上。整格用 `MotionSpec.press` 弹到 1.08 倍，两个日格之间切换时旧的格回落、
 * 新的格弹起，即本页的「水波」反馈。
 *
 * 这里的 1.08 是**常驻**在选中格上的（简报字面要求，且选中格全屏只一枚，不会有习惯日历那种
 * 整月参差不齐的问题）；那页改成了 `checked` 翻真时弹一下即回落，见 `HabitCalendarScreen.DayCell`。
 *
 * `enabled` 供翻月转场把出场的那一片降级为纯动效：`clickable(enabled = false)` 既不吃手势，
 * 也不再挂 accessibility action，TalkBack 于是只读得到当前月份那一遍日期。
 */
@Composable
private fun GlobalDayCell(
    date: LocalDate,
    isToday: Boolean,
    isSelected: Boolean,
    hasCheckIn: Boolean,
    hasEvent: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    val cellColor by animateColorAsState(
        targetValue = if (isSelected) colors.accentInk else Color.Transparent,
        animationSpec = tween(durationMillis = MotionSpec.FadeMs / 3),
        label = "globalDayColor",
    )
    // 墨色走完整 FadeMs、底色只走 1/3（见 KDoc 的错峰理由）：底色已在路上落定，
    // 这一条负责把数字从常规墨色缓到实底上的反白，中途不再出现「白字压白卡」。
    // 下面两枚圆点选中时也复用这个值，一起过渡，避免同一类闪烁。
    val inkColor by animateColorAsState(
        targetValue = when {
            isSelected -> colors.onAccent
            isToday    -> colors.accentInk
            else       -> colors.primaryText
        },
        animationSpec = tween(durationMillis = MotionSpec.FadeMs),
        label = "globalDayInk",
    )
    val cellScale by animateFloatAsState(
        targetValue = if (isSelected) 1.08f else 1f,
        animationSpec = MotionSpec.press,
        label = "globalDayScale",
    )
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .graphicsLayer(scaleX = cellScale, scaleY = cellScale)
                .clip(CircleShape)
                .background(cellColor)
                .border(
                    width = if (isToday && !isSelected) 1.5.dp else 0.dp,
                    color = colors.accent,
                    shape = CircleShape,
                ),
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = "${date.dayOfMonth}",
                    style = texts.aux.copy(
                        fontWeight = if (isSelected || isToday) FontWeight.SemiBold else FontWeight.Normal,
                        color = inkColor,
                    ),
                )
                if (hasCheckIn || hasEvent) {
                    Spacer(Modifier.height(2.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        if (hasCheckIn) {
                            Box(
                                modifier = Modifier
                                    .size(4.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (isSelected) inkColor else colors.success,
                                    ),
                            )
                        }
                        if (hasEvent) {
                            Box(
                                modifier = Modifier
                                    .size(4.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (isSelected) inkColor.copy(alpha = 0.7f)
                                        else colors.accent,
                                    ),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 选中日详情卡片：打卡情况 + 系统日历事件列表；过去 7 天的空日给补卡入口 */
@Composable
private fun DayDetailCard(
    date: LocalDate,
    checkedHabits: List<String>,
    events: List<SystemEvent>,
    showEvents: Boolean,
    habits: List<Habit>,
    showMakeUp: Boolean,
    onMakeUp: (Habit) -> Unit,
) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    val today = LocalDate.now()
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = chineseDate(date),
                style = texts.cardTitle,
            )
            if (date == today) {
                Spacer(Modifier.width(AppTheme.space.sm))
                // 「今天」标记：与习惯卡「已达成」同一枚药丸（波 3 收口，不再各写 6dp 圆角）
                AppPill(
                    container = colors.accentSoft,
                    ink = colors.accentInk,
                    label = "今天",
                )
            }
        }

        Spacer(Modifier.height(AppTheme.space.md))
        Text(text = "打卡记录", style = texts.caption)
        Spacer(Modifier.height(AppTheme.space.xs))
        if (checkedHabits.isEmpty()) {
            Text(text = "该日暂无打卡", style = texts.aux.copy(color = colors.secondaryText))
            // 补卡入口只给「过去 7 天内且未打卡」的空日（canMakeUp 是唯一判据，
            // 与写入侧守卫同一份实现，别在这里再发明一套日期算术）
            if (canMakeUp(date) && showMakeUp && habits.isNotEmpty()) {
                Spacer(Modifier.height(AppTheme.space.sm))
                Text(text = "补打卡：", style = texts.caption)
                habits.forEach { habit ->
                    Text(
                        text = "· ${habit.name}",
                        style = texts.body.copy(color = colors.accentInk),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onMakeUp(habit) }
                            .padding(vertical = AppTheme.space.xs),
                    )
                }
            }
        } else {
            checkedHabits.forEach { name ->
                Row(
                    modifier = Modifier.padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        tint = colors.success,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(AppTheme.space.sm))
                    Text(text = name, style = texts.aux)
                }
            }
        }

        if (showEvents) {
            Spacer(Modifier.height(AppTheme.space.md))
            Text(text = "系统日程", style = texts.caption)
            Spacer(Modifier.height(AppTheme.space.xs))
            if (events.isEmpty()) {
                Text(
                    text = "该日暂无日程",
                    style = texts.aux.copy(color = colors.secondaryText),
                )
            } else {
                events.forEach { event -> EventRow(event) }
            }
        }
    }
}

/**
 * 事件条目卡：日历颜色圆点 + 标题 + 时间与日历账户名。
 *
 * 容器并入 [AppCard] 的观感语言（大圆角 + 1dp 柔光描边代替阴影），但底色改用 `colors.background`
 * 而非 `card` —— 它嵌在 DayDetailCard 的卡面里，同色会看不见轮廓，凹进去一层才读得出「一条一项」。
 */
@Composable
private fun EventRow(event: SystemEvent) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    // 这里保持位置参只是沿用 T3 起 `RoundedCornerShape(AppTheme.radius.md)` 的既有写法。
    // 澄清一处以讹传讹：T11 记的「@JvmInline value class 命名实参会撞 internal 构造器」那条陷阱
    // 只属于 androidx.compose.ui.geometry.CornerRadius(radiusX = …)（见 HabitSnake.kt 就地注释），
    // 与 RoundedCornerShape 无关；后者的真实约束是四角重载的默认值（只写 topStart = 会把另三角
    // 落成 0）。度量令牌现已收在 AppTheme.space / AppTheme.radius，这条注释只留给
    // RoundedCornerShape 的重载坑，别把它当成「圆角构造一律位置参」的规矩。
    val shape = RoundedCornerShape(AppTheme.radius.md)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = AppTheme.space.sm)
            .clip(shape)
            .background(colors.background)
            .border(width = 1.dp, color = colors.divider.copy(alpha = 0.6f), shape = shape)
            .padding(horizontal = AppTheme.space.sm, vertical = AppTheme.space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(Color(event.calendarColor)),
        )
        Spacer(Modifier.width(AppTheme.space.sm))
        Column(Modifier.weight(1f)) {
            Text(text = event.title, style = texts.aux, maxLines = 1)
            Spacer(Modifier.height(1.dp))
            val suffix = buildString {
                append(event.calendarName)
                if (event.location.isNotBlank()) append(" · ${event.location}")
            }
            Text(
                text = "${eventTimeText(event)} · $suffix",
                style = texts.caption,
                maxLines = 1,
            )
        }
    }
}
