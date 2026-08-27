package com.studykit.ui.habit

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.studykit.data.SystemEvent
import com.studykit.ui.components.AppButton
import com.studykit.ui.components.AppCard
import com.studykit.ui.theme.DesignTokens
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.Instant
import java.time.ZoneId

private val WeekHeader = listOf("一", "二", "三", "四", "五", "六", "日")
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
 */
@Composable
fun GlobalCalendarScreen(
    viewModel: GlobalCalendarViewModel,
    onBack: () -> Unit,
) {
    val checkInsByDate by viewModel.checkInsByDate.collectAsStateWithLifecycle()
    val system by viewModel.system.collectAsStateWithLifecycle()
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
            Text(text = "日历", style = DesignTokens.PageTitle)
        }

        Spacer(Modifier.height(DesignTokens.SpacingMd))

        TomorrowCard(
            tomorrow = tomorrow,
            events = system.tomorrowEvents,
            showEvents = system.permissionGranted && !system.loadError,
        )

        if (!system.permissionGranted) {
            Spacer(Modifier.height(DesignTokens.SpacingMd))
            PermissionNoticeCard(onRetry = { permissionLauncher.launch(viewModel.calendarPermissionToRequest()) })
        } else if (system.loadError) {
            Spacer(Modifier.height(DesignTokens.SpacingMd))
            Text(text = "系统日历加载失败，可返回重试", style = DesignTokens.Caption)
        }

        Spacer(Modifier.height(DesignTokens.SpacingMd))

        MonthCard(
            month = month,
            onMonthChange = { month = it },
            checkInsByDate = checkInsByDate,
            eventsByDate = system.eventsByDate,
            selectedDate = selectedDate,
            onSelectDate = { selectedDate = it },
        )

        Spacer(Modifier.height(DesignTokens.SpacingMd))

        DayDetailCard(
            date = selectedDate,
            checkedHabits = checkInsByDate[selectedDate].orEmpty(),
            events = system.eventsByDate[selectedDate].orEmpty(),
            showEvents = system.permissionGranted && !system.loadError,
        )

        Spacer(Modifier.height(DesignTokens.SpacingLg))
    }
}

/** 明日日期卡片：强调色底醒目展示「明天是 X月X日 周X」及明日日程 */
@Composable
private fun TomorrowCard(
    tomorrow: LocalDate,
    events: List<SystemEvent>,
    showEvents: Boolean,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(DesignTokens.CornerRadiusLg),
        color = DesignTokens.Accent,
        shadowElevation = DesignTokens.ShadowElevation,
    ) {
        Column(modifier = Modifier.padding(DesignTokens.CardPadding)) {
            Text(
                text = "明天",
                style = DesignTokens.Caption.copy(color = DesignTokens.Card.copy(alpha = 0.85f)),
            )
            Spacer(Modifier.height(DesignTokens.SpacingXs))
            Text(
                text = chineseDate(tomorrow),
                style = DesignTokens.LargeTitle.copy(color = DesignTokens.Card),
            )
            Spacer(Modifier.height(DesignTokens.SpacingSm))
            if (!showEvents) {
                Text(
                    text = "授权系统日历后可查看明日日程",
                    style = DesignTokens.Auxiliary.copy(color = DesignTokens.Card.copy(alpha = 0.85f)),
                )
            } else if (events.isEmpty()) {
                Text(
                    text = "明天暂无日程，安心安排打卡吧",
                    style = DesignTokens.Auxiliary.copy(color = DesignTokens.Card.copy(alpha = 0.85f)),
                )
            } else {
                events.take(3).forEach { event ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = DesignTokens.SpacingXs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(DesignTokens.Card),
                        )
                        Spacer(Modifier.width(DesignTokens.SpacingSm))
                        Text(
                            text = "${eventTimeText(event)}  ${event.title}",
                            style = DesignTokens.Auxiliary.copy(color = DesignTokens.Card),
                            maxLines = 1,
                        )
                    }
                }
                if (events.size > 3) {
                    Spacer(Modifier.height(DesignTokens.SpacingXs))
                    Text(
                        text = "还有 ${events.size - 3} 项日程…",
                        style = DesignTokens.Caption.copy(color = DesignTokens.Card.copy(alpha = 0.85f)),
                    )
                }
            }
        }
    }
}

/** 权限未授权时的降级提示卡片 */
@Composable
private fun PermissionNoticeCard(onRetry: () -> Unit) {
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(DesignTokens.Warning.copy(alpha = 0.10f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.DateRange,
                    contentDescription = null,
                    tint = DesignTokens.Warning,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(DesignTokens.SpacingMd))
            Column(Modifier.weight(1f)) {
                Text(text = "需要日历权限", style = DesignTokens.CardTitle)
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "授权后才能显示系统日历事件，打卡记录不受影响",
                    style = DesignTokens.Caption,
                )
            }
        }
        Spacer(Modifier.height(DesignTokens.SpacingMd))
        AppButton(text = "去授权", onClick = onRetry)
    }
}

/** 月历卡片：月份切换 + 网格（叠加打卡点与事件徽标） */
@Composable
private fun MonthCard(
    month: YearMonth,
    onMonthChange: (YearMonth) -> Unit,
    checkInsByDate: Map<LocalDate, List<String>>,
    eventsByDate: Map<LocalDate, List<SystemEvent>>,
    selectedDate: LocalDate,
    onSelectDate: (LocalDate) -> Unit,
) {
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            IconButton(onClick = { onMonthChange(month.minusMonths(1)) }) {
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowLeft,
                    contentDescription = "上个月",
                    tint = DesignTokens.Accent,
                )
            }
            Text(
                text = "${month.year} 年 ${month.monthValue} 月",
                style = DesignTokens.CardTitle,
            )
            IconButton(onClick = { onMonthChange(month.plusMonths(1)) }) {
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowRight,
                    contentDescription = "下个月",
                    tint = DesignTokens.Accent,
                )
            }
        }

        Spacer(Modifier.height(DesignTokens.SpacingXs))

        Row(modifier = Modifier.fillMaxWidth()) {
            WeekHeader.forEach { day ->
                Text(
                    text = day,
                    style = DesignTokens.Caption,
                    modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }

        Spacer(Modifier.height(DesignTokens.SpacingSm))

        val today = LocalDate.now()
        val leadingBlanks = month.atDay(1).dayOfWeek.value - 1
        val cells = List(leadingBlanks) { null } +
            (1..month.lengthOfMonth()).map { month.atDay(it) }

        cells.chunked(7).forEach { row ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = DesignTokens.SpacingSm),
            ) {
                row.forEach { date ->
                    if (date == null) {
                        Spacer(Modifier.weight(1f))
                    } else {
                        GlobalDayCell(
                            date = date,
                            isToday = date == today,
                            isSelected = date == selectedDate,
                            hasCheckIn = date in checkInsByDate,
                            hasEvent = date in eventsByDate,
                            onClick = { onSelectDate(date) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                repeat(7 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }

        // 图例
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LegendDot(color = DesignTokens.Success, label = "打卡")
            Spacer(Modifier.width(DesignTokens.SpacingMd))
            LegendDot(color = DesignTokens.Accent, label = "系统日程")
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Box(
        modifier = Modifier
            .size(6.dp)
            .clip(CircleShape)
            .background(color),
    )
    Spacer(Modifier.width(DesignTokens.SpacingXs))
    Text(text = label, style = DesignTokens.Caption)
}

/** 月历单日格：选中为实心强调色，今天描边；下方叠加打卡点 + 事件徽标 */
@Composable
private fun GlobalDayCell(
    date: LocalDate,
    isToday: Boolean,
    isSelected: Boolean,
    hasCheckIn: Boolean,
    hasEvent: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(if (isSelected) DesignTokens.Accent else Color.Transparent)
                .border(
                    width = if (isToday && !isSelected) 1.5.dp else 0.dp,
                    color = DesignTokens.Accent,
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
                    style = DesignTokens.Auxiliary.copy(
                        fontWeight = if (isSelected || isToday) FontWeight.SemiBold else FontWeight.Normal,
                        color = when {
                            isSelected -> DesignTokens.Card
                            isToday    -> DesignTokens.Accent
                            else       -> DesignTokens.PrimaryText
                        },
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
                                        if (isSelected) DesignTokens.Card else DesignTokens.Success,
                                    ),
                            )
                        }
                        if (hasEvent) {
                            Box(
                                modifier = Modifier
                                    .size(4.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (isSelected) DesignTokens.Card.copy(alpha = 0.7f)
                                        else DesignTokens.Accent,
                                    ),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 选中日详情卡片：打卡情况 + 系统日历事件列表 */
@Composable
private fun DayDetailCard(
    date: LocalDate,
    checkedHabits: List<String>,
    events: List<SystemEvent>,
    showEvents: Boolean,
) {
    val today = LocalDate.now()
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = chineseDate(date),
                style = DesignTokens.CardTitle,
            )
            if (date == today) {
                Spacer(Modifier.width(DesignTokens.SpacingSm))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(DesignTokens.Accent.copy(alpha = 0.10f))
                        .padding(horizontal = DesignTokens.SpacingSm, vertical = 2.dp),
                ) {
                    Text(
                        text = "今天",
                        style = DesignTokens.Caption.copy(color = DesignTokens.Accent),
                    )
                }
            }
        }

        Spacer(Modifier.height(DesignTokens.SpacingMd))
        Text(text = "打卡记录", style = DesignTokens.Caption)
        Spacer(Modifier.height(DesignTokens.SpacingXs))
        if (checkedHabits.isEmpty()) {
            Text(text = "该日暂无打卡", style = DesignTokens.Auxiliary.copy(color = DesignTokens.SecondaryText))
        } else {
            checkedHabits.forEach { name ->
                Row(
                    modifier = Modifier.padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        tint = DesignTokens.Success,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(DesignTokens.SpacingSm))
                    Text(text = name, style = DesignTokens.Auxiliary)
                }
            }
        }

        if (showEvents) {
            Spacer(Modifier.height(DesignTokens.SpacingMd))
            Text(text = "系统日程", style = DesignTokens.Caption)
            Spacer(Modifier.height(DesignTokens.SpacingXs))
            if (events.isEmpty()) {
                Text(
                    text = "该日暂无日程",
                    style = DesignTokens.Auxiliary.copy(color = DesignTokens.SecondaryText),
                )
            } else {
                events.forEach { event -> EventRow(event) }
            }
        }
    }
}

/** 事件行：日历颜色圆点 + 标题 + 时间与日历账户名 */
@Composable
private fun EventRow(event: SystemEvent) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = DesignTokens.SpacingXs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(Color(event.calendarColor)),
        )
        Spacer(Modifier.width(DesignTokens.SpacingSm))
        Column(Modifier.weight(1f)) {
            Text(text = event.title, style = DesignTokens.Auxiliary, maxLines = 1)
            Spacer(Modifier.height(1.dp))
            val suffix = buildString {
                append(event.calendarName)
                if (event.location.isNotBlank()) append(" · ${event.location}")
            }
            Text(
                text = "${eventTimeText(event)} · $suffix",
                style = DesignTokens.Caption,
                maxLines = 1,
            )
        }
    }
}
