package com.studykit.ui.habit

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
import androidx.compose.foundation.layout.IntrinsicSize
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
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.YearMonth

/**
 * 习惯打卡日历页：月视图网格 + 月份切换 + 底部统计。
 *
 * 网格骨架（表头 [WeekHeader]、恒 6 行 [MonthGridCells]、补齐 [padToFullWeeks]）与
 * `GlobalCalendarScreen` 共用 `ui/habit/MonthGridCommon.kt` 那一份。
 *
 * 颜色与文字样式统一取 `AppTheme`；间距/圆角取 `AppTheme.space` / `AppTheme.radius` 的 dp 常量。
 * 动效三处：
 * - 已打卡日格的底色与数字墨色**错峰**淡入（[MotionSpec] 的 spring 全是 `Float` 向，
 *   `animateColorAsState` 要 `Color` 向规格，故颜色走映射表规定的 tween 分支，不自造新规格）：
 *   底色 `tween(MotionSpec.FadeMs / 3)` 提前落定、墨色仍 `tween(MotionSpec.FadeMs)`。同速补间时
 *   f≈0.2–0.8 那一段是「半透明 accentInk 托着半透明白字」，实测对比 ≈1.06:1，数字像糊了一层；
 * - 打卡那一刻整格用 `MotionSpec.press` **弹一下**（1.08 → 回落 1f，见 [DayCell]），不常驻缩放，
 *   否则整月打过卡的格子会比没打的高出一圈，月历基线参差不齐；
 * - 翻月时表头与网格整片走 `AnimatedContent`（淡入 + 1/6 宽的短距横向滑入，方向跟着点的箭头走），
 *   「X 年 X 月」标题行留在转场之外只改文案；网格恒为 6 行使两帧等高，且**出场那一帧的旧网格不可点击**
 *   （`shownMonth != month` 时把日格的 onClick 置空），消除 TalkBack 读到两个日期与陈旧点击。
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
    val requestedDetail by viewModel.detail.collectAsStateWithLifecycle()
    // 路由键守卫（终审 C1 的同类站点）：只认「答的就是本习惯」的那份应答。`loadDetail(habitId)`
    // 是异步的，换习惯进来的那一帧 VM 里留着的还是上一个习惯的 detail —— 页面下方所有 `detail?.`
    // 都会跟着渲染错的习惯，补打卡弹层更会把卡打到另一个习惯上（`submitCheckIn(habit, …)`
    // 的 habit 就取自这里）。
    // 本页与另两页的差别只在**没有**整页加载态：这里挡成 null 后，页头标题与各 safe-call
    // 自动落回既有的空网格形态（那是本页本来的样子，不新造视觉），所以只有
    // `MistakeDetailScreen` / `BookDetailScreen` 需要早返回分支。三页统一的是这条守卫本身。
    val detail = requestedDetail?.takeIf { it.habit.id == habitId }
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
            Text(
                text = detail?.habit?.name ?: "习惯日历",
                style = texts.pageTitle,
            )
        }

        Spacer(Modifier.height(AppTheme.space.md))

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
                    // 淡入淡出走 MotionSpec 工厂（默认 FadeMs）；滑入仍按本页方向自己给 tween，
                    // 因为动的是位移 lambda，工厂只管淡
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
                label = "habitMonthGrid",
            ) { shownMonth ->
                Column(modifier = Modifier.fillMaxWidth()) {
                    Spacer(Modifier.height(AppTheme.space.xs))

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

                    Spacer(Modifier.height(AppTheme.space.sm))

                    // 日期网格（周一为首列）
                    val checkedDates = detail?.checkedDates ?: emptySet()
                    val today = LocalDate.now()
                    val leadingBlanks = shownMonth.atDay(1).dayOfWeek.value - 1
                    val days = List(leadingBlanks) { null } +
                        (1..shownMonth.lengthOfMonth()).map { shownMonth.atDay(it) }
                    // 固定 6 行：不足 42 格的月用空位补齐（[padToFullWeeks]，与全局日历页同一份）。
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
                                    val checked = date in checkedDates
                                    val makeUpEligible = !checked && canMakeUp(date, today)
                                    // 转场期间出场的旧月网格只负责动效，不再吃点击：否则 220ms 里
                                    // 屏幕上是两个月份，TalkBack 也会把同一批日期读第二遍
                                    val interactive = shownMonth == month
                                    DayCell(
                                        date = date,
                                        checked = checked,
                                        isToday = date == today,
                                        makeUpEligible = makeUpEligible,
                                        onClick = if (interactive && makeUpEligible) ({ makeUpDate = date }) else null,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                            // 补齐最后一行空位，保持等宽
                            repeat(7 - row.size) { Spacer(Modifier.weight(1f).aspectRatio(1f)) }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(AppTheme.space.sm))
        Text(
            text = "过去 ${MAKEUP_WINDOW_DAYS.toInt()} 天内漏打卡的日期（灰色圈）可点击补打卡",
            style = texts.caption,
        )

        Spacer(Modifier.height(AppTheme.space.lg))

        val isCountType = detail?.isCountType == true
        Row(
            horizontalArrangement = Arrangement.spacedBy(AppTheme.space.md),
            modifier = Modifier.height(IntrinsicSize.Max),
        ) {
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
 * 打卡那一刻的两处动效是**错峰**的：底色只走 `tween(MotionSpec.FadeMs / 3)` 先落定，数字墨色
 * 仍走完整的 `tween(MotionSpec.FadeMs)`。两条补间同速时，中段（f≈0.2–0.8）是「半透明 accentInk
 * 托着半透明白字」，对比 ≈1.06:1 —— 数字会糊掉约 130ms；让底色提前站稳，剩下的时间里字始终
 * 压在已实底的圈上。墨色仍必须补间（不能立刻变 onAccent），否则浅色主题那 200ms 是白字压白卡。
 *
 * 缩放不再是「已打卡即常驻 1.08」（整月看会参差不齐），而是照抄 `HabitListScreen` 打卡按钮的
 * 写法：`checked` 由 false→true 那一帧弹到 1.08、`MotionSpec.FadeMs` 后回落 1f。缩放落在整枚
 * 日格而不是只有数字，圆角与描边才会跟着一起长，不会露出「字大了圈没大」的错位。
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
        animationSpec = tween(durationMillis = MotionSpec.FadeMs / 3),
        label = "dayCellColor",
    )
    // 字色补间比底色长（见 KDoc）：底色已在 1/3 处站稳，这一条走完 FadeMs 全程
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
    // 一次性弹跳：appeared 只压掉「这一格第一次出现」的那一帧（翻月/重组不重播）；
    // 之后 checked 由 false→true 才弹，弹完落回 1f
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
    val cellScale by animateFloatAsState(
        targetValue = if (pop) 1.08f else 1f,
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
