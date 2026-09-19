package com.studykit.ui.habit

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.studykit.ui.components.AppCard
import com.studykit.ui.components.StatTile
import com.studykit.ui.motion.MotionSpec
import com.studykit.ui.theme.AppTheme
import com.studykit.ui.theme.DesignTokens
import java.time.LocalDate
import java.time.YearMonth

private val WeekHeader = listOf("一", "二", "三", "四", "五", "六", "日")

/**
 * 习惯打卡日历页：月视图网格 + 月份切换 + 底部统计。
 *
 * 颜色与文字样式统一取 `AppTheme`；间距/圆角仍走 [DesignTokens] 的 dp 常量（T15 才迁度量）。
 * 动效三处：
 * - 已打卡日格的底色用 `tween(MotionSpec.FadeMs)` 补间（[MotionSpec] 的 spring 全是 `Float` 向，
 *   `animateColorAsState` 要 `Color` 向规格，故颜色走映射表规定的 tween 分支，不自造新规格）；
 * - 同一格用 `MotionSpec.press` 把整枚日格弹到 1.08 倍，形成「水波」式的落定反馈；
 * - 翻月时表头与网格整片走 `AnimatedContent`（淡入 + 1/6 宽的短距横向滑入，方向跟着点的箭头走），
 *   「X 年 X 月」标题行留在转场之外只改文案。
 */
@Composable
fun HabitCalendarScreen(
    habitId: Long,
    viewModel: HabitViewModel,
    onBack: () -> Unit,
) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    LaunchedEffect(habitId) { viewModel.loadDetail(habitId) }
    val detail by viewModel.detail.collectAsStateWithLifecycle()
    var month by remember { mutableStateOf(YearMonth.now()) }
    // 翻月方向：+1 = 往未来（新网格从右侧进），-1 = 回过去。与 month 同一帧写入，
    // 因此 AnimatedContent 触发转场时读到的就是本次手势的方向。
    var slide by remember { mutableStateOf(0) }
    var makeUpDate by remember { mutableStateOf<LocalDate?>(null) }

    /** 翻月：先记方向再换月，保证 AnimatedContent 的滑入方向与手指意图一致 */
    fun stepMonth(delta: Int) {
        slide = delta
        month = month.plusMonths(delta.toLong())
    }

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
                    tint = colors.accentInk,
                )
            }
            Spacer(Modifier.width(DesignTokens.SpacingXs))
            Text(
                text = detail?.habit?.name ?: "习惯日历",
                style = texts.pageTitle,
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
                IconButton(onClick = { stepMonth(-1) }) {
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
                IconButton(onClick = { stepMonth(1) }) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = "下个月",
                        tint = colors.accentInk,
                    )
                }
            }

            // 表头 + 网格整片随月份换页：淡入 + 1/6 宽的短距滑入（两坨内容等高，转场期间卡片不抖）；
            // 「X 年 X 月」标题行留在转场之外，只跟着重组改文案
            AnimatedContent(
                targetState = month,
                transitionSpec = {
                    val dir = if (slide >= 0) 1 else -1
                    val enter = fadeIn(animationSpec = tween(durationMillis = MotionSpec.FadeMs)) +
                        slideInHorizontally(animationSpec = tween(durationMillis = MotionSpec.FadeMs)) {
                            it / 6 * dir
                        }
                    val exit = fadeOut(animationSpec = tween(durationMillis = MotionSpec.FadeMs)) +
                        slideOutHorizontally(animationSpec = tween(durationMillis = MotionSpec.FadeMs)) {
                            -it / 6 * dir
                        }
                    enter.togetherWith(exit)
                },
                modifier = Modifier.fillMaxWidth(),
                label = "habitMonthGrid",
            ) { shownMonth ->
                Column(modifier = Modifier.fillMaxWidth()) {
                    Spacer(Modifier.height(DesignTokens.SpacingXs))

                    // 周一至周日表头
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

                    Spacer(Modifier.height(DesignTokens.SpacingSm))

                    // 日期网格（周一为首列）
                    val checkedDates = detail?.checkedDates ?: emptySet()
                    val today = LocalDate.now()
                    val leadingBlanks = shownMonth.atDay(1).dayOfWeek.value - 1
                    val cells = List(leadingBlanks) { null } +
                        (1..shownMonth.lengthOfMonth()).map { shownMonth.atDay(it) }

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
            }
        }

        Spacer(Modifier.height(DesignTokens.SpacingSm))
        Text(
            text = "过去 ${MAKEUP_WINDOW_DAYS.toInt()} 天内漏打卡的日期（灰色圈）可点击补打卡",
            style = texts.caption,
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

/**
 * 日历单日格：已打卡 = 实底强调色（墨水容器 + onAccent 数字，双主题各自达 AA），
 * 今天 = accent 描边圈，可补卡 = 次级灰描边圈。
 *
 * 打卡那一刻底色用 `tween(MotionSpec.FadeMs)` 淡入、整格用 `MotionSpec.press` 弹到 1.08 倍，
 * 两帧叠加就是本页的「水波」反馈；缩放落在整枚日格而不是只有数字，圆角与描边才会跟着一起长，
 * 不会露出「字大了圈没大」的错位。
 */
@Composable
private fun DayCell(
    date: LocalDate,
    checked: Boolean,
    isToday: Boolean,
    makeUpEligible: Boolean,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    val cellColor by animateColorAsState(
        targetValue = if (checked) colors.accentInk else Color.Transparent,
        animationSpec = tween(durationMillis = MotionSpec.FadeMs),
        label = "dayCellColor",
    )
    // 字色与底色同一条补间：否则打卡那一刻数字立刻变 onAccent（浅色主题＝白），
    // 底色却还在「透明 → accentInk」的路上，那 200ms 里白字压在白卡上等于数字消失。
    val inkColor by animateColorAsState(
        targetValue = when {
            checked   -> colors.onAccent
            isToday   -> colors.accentInk
            makeUpEligible -> colors.secondaryText
            else      -> colors.primaryText
        },
        animationSpec = tween(durationMillis = MotionSpec.FadeMs),
        label = "dayCellInk",
    )
    val cellScale by animateFloatAsState(
        targetValue = if (checked) 1.08f else 1f,
        animationSpec = MotionSpec.press,
        label = "dayCellScale",
    )
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .graphicsLayer(scaleX = cellScale, scaleY = cellScale)
                .clip(CircleShape)
                .background(cellColor)
                .border(
                    width = if (isToday && !checked) 1.5.dp else if (makeUpEligible) 1.dp else 0.dp,
                    color = if (makeUpEligible && !isToday) {
                        colors.secondaryText.copy(alpha = 0.55f)
                    } else {
                        colors.accent
                    },
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "${date.dayOfMonth}",
                style = texts.aux.copy(
                    fontWeight = if (checked || isToday) FontWeight.SemiBold else FontWeight.Normal,
                    color = inkColor,
                ),
            )
        }
    }
}
