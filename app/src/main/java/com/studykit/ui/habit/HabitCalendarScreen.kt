package com.studykit.ui.habit

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.studykit.ui.components.AppCard
import com.studykit.ui.components.StatTile
import com.studykit.ui.theme.DesignTokens
import java.time.LocalDate
import java.time.YearMonth

private val WeekHeader = listOf("一", "二", "三", "四", "五", "六", "日")

/** 打卡日历页：月视图网格 + 月份切换 + 底部统计 */
@Composable
fun HabitCalendarScreen(
    habitId: Long,
    viewModel: HabitViewModel,
    onBack: () -> Unit,
) {
    LaunchedEffect(habitId) { viewModel.loadDetail(habitId) }
    val detail by viewModel.detail.collectAsStateWithLifecycle()
    var month by remember { mutableStateOf(YearMonth.now()) }
    var makeUpDate by remember { mutableStateOf<LocalDate?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
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
            Text(
                text = detail?.habit?.name ?: "习惯日历",
                style = DesignTokens.PageTitle,
            )
        }

        Spacer(Modifier.height(DesignTokens.SpacingMd))

        AppCard(modifier = Modifier.fillMaxWidth()) {
            // 月份切换
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                IconButton(onClick = { month = month.minusMonths(1) }) {
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
                IconButton(onClick = { month = month.plusMonths(1) }) {
                    Icon(
                        imageVector = Icons.Filled.KeyboardArrowRight,
                        contentDescription = "下个月",
                        tint = DesignTokens.Accent,
                    )
                }
            }

            Spacer(Modifier.height(DesignTokens.SpacingXs))

            // 周一至周日表头
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

            // 日期网格（周一为首列）
            val checkedDates = detail?.checkedDates ?: emptySet()
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
                            val checked = date in checkedDates
                            val makeUpEligible = !checked && canMakeUp(date, today)
                            DayCell(
                                date = date,
                                checked = checked,
                                isToday = date == today,
                                makeUpEligible = makeUpEligible,
                                onClick = if (makeUpEligible) ({ makeUpDate = date }) else null,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    // 补齐最后一行空位，保持等宽
                    repeat(7 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }

        Spacer(Modifier.height(DesignTokens.SpacingSm))
        Text(
            text = "过去 ${MAKEUP_WINDOW_DAYS.toInt()} 天内漏打卡的日期（灰色圈）可点击补打卡",
            style = DesignTokens.Caption,
        )

        Spacer(Modifier.height(DesignTokens.SpacingLg))

        val isCountType = detail?.isCountType == true
        Row(horizontalArrangement = Arrangement.spacedBy(DesignTokens.SpacingMd)) {
            StatTile(
                value = if (isCountType) {
                    "${formatAmount(detail?.totalAmount ?: 0.0)} ${detail?.habit?.unit.orEmpty()}"
                } else {
                    "${detail?.totalCheckDays ?: 0} 天"
                },
                label = if (isCountType) "累计数量" else "累计打卡",
                modifier = Modifier.weight(1f),
            )
            StatTile(
                value = "${detail?.streak ?: 0} 天",
                label = "连续打卡",
                modifier = Modifier.weight(1f),
            )
        }
    }

    // 补打卡弹层：仅针对过去 7 天内未打卡日期，限天数/数量两种写入方式
    val makeUp = makeUpDate
    val habit = detail?.habit
    if (makeUp != null && habit != null) {
        CheckInSheet(
            habit = habit,
            date = makeUp,
            existing = null,
            isMakeUp = true,
            onDismiss = { makeUpDate = null },
            onConfirm = { note, amount ->
                viewModel.submitCheckIn(habit, makeUp, note, amount)
                makeUpDate = null
            },
        )
    }
}

@Composable
private fun DayCell(
    date: LocalDate,
    checked: Boolean,
    isToday: Boolean,
    makeUpEligible: Boolean,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(if (checked) DesignTokens.Accent else androidx.compose.ui.graphics.Color.Transparent)
                .border(
                    width = if (isToday && !checked) 1.5.dp else if (makeUpEligible) 1.dp else 0.dp,
                    color = if (makeUpEligible && !isToday) DesignTokens.SecondaryText.copy(alpha = 0.55f) else DesignTokens.Accent,
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "${date.dayOfMonth}",
                style = DesignTokens.Auxiliary.copy(
                    fontWeight = if (checked || isToday) FontWeight.SemiBold else FontWeight.Normal,
                    color = when {
                        checked   -> DesignTokens.Card
                        isToday   -> DesignTokens.Accent
                        makeUpEligible -> DesignTokens.SecondaryText
                        else      -> DesignTokens.PrimaryText
                    },
                ),
            )
        }
    }
}
