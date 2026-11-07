package com.studykit.ui.study

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.outlined.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.studykit.ui.components.AppButton
import com.studykit.ui.components.AppCard
import com.studykit.ui.components.BouncyIconButton
import com.studykit.ui.motion.MotionSpec
import com.studykit.ui.motion.rememberPressScale
import com.studykit.ui.theme.AppTheme
import com.studykit.util.signin.SignInDay
import com.studykit.util.signin.SignInMonthCell
import com.studykit.util.signin.SignInStats
import com.studykit.util.signin.SignInSummary
import java.time.LocalDate
import java.time.YearMonth

/**
 * 首页“每日签到”卡：签到按钮 + 连续天数 + 本周 7 天的圆点。
 *
 * 交互约定：
 * - 点卡片主体（含本周圆点）打开整月签到日历；
 * - 点右侧按钮只签到，成功后由首页外层触发一次 ConfettiBurst；
 * - 已签到时按钮变成 successSoft 的“已签到”，不可重复写库；
 * - 按下时按钮/卡片沿用全局 press 回弹，且签到按钮会发一次轻触觉反馈。
 */
@Composable
fun DailySignInCard(
    summary: SignInSummary,
    onSignIn: () -> Unit,
    onOpenCalendar: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    val haptic = LocalHapticFeedback.current
    val signed = summary.signedToday
    val caption = when {
        signed -> "已连续签到 " + summary.currentStreak + " 天"
        summary.currentStreak > 0 -> "今天签到，延续 " + summary.currentStreak + " 天"
        else -> "每天来一下，积少成多"
    }
    val cardDescription = if (signed) {
        "每日签到，今天已签到，连续 " + summary.currentStreak + " 天，点按查看签到日历"
    } else {
        "每日签到，今天还没签到，点按查看签到日历"
    }

    AppCard(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = cardDescription }
            .clickable(role = Role.Button, onClick = onOpenCalendar),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SignInBadge(signed = signed)
            Spacer(Modifier.width(AppTheme.space.md))
            Column(Modifier.weight(1f)) {
                Text(text = "每日签到", style = texts.cardTitle)
                Spacer(Modifier.height(2.dp))
                Text(text = caption, style = texts.caption, color = colors.secondaryText)
                Spacer(Modifier.height(AppTheme.space.sm))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "本周 " + summary.weekSignedCount + "/7",
                        style = texts.caption,
                        color = colors.secondaryText,
                        maxLines = 1,
                    )
                    Spacer(Modifier.width(6.dp))
                    WeekDots(days = summary.weekDays)
                }
            }
            Spacer(Modifier.width(AppTheme.space.sm))
            SignInPill(
                signed = signed,
                onClick = {
                    if (!signed) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onSignIn()
                    }
                },
            )
        }
    }
}

@Composable
private fun SignInBadge(signed: Boolean) {
    val colors = AppTheme.colors
    val container by animateColorAsState(
        targetValue = if (signed) colors.goldSoft else colors.accentSoft,
        animationSpec = tween(MotionSpec.FadeMs),
        label = "signInBadgeColor",
    )
    val scale by animateFloatAsState(
        targetValue = if (signed) 1f else 0.92f,
        animationSpec = MotionSpec.snap,
        label = "signInBadgeScale",
    )
    Box(
        modifier = Modifier
            .size(46.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .background(container),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.CheckCircle,
            contentDescription = null,
            tint = if (signed) colors.goldInk else colors.accentInk,
            modifier = Modifier.size(24.dp),
        )
    }
}

@Composable
private fun WeekDots(days: List<SignInDay>) {
    val colors = AppTheme.colors
    val shown = if (days.size == 7) days else SignInStats.weekDays(LocalDate.now(), emptySet())
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        shown.forEach { day ->
            val isToday = day.date == LocalDate.now()
            val dotColor by animateColorAsState(
                targetValue = when {
                    day.signed -> colors.accent
                    isToday -> colors.accent.copy(alpha = 0.35f)
                    else -> colors.divider
                },
                animationSpec = tween(MotionSpec.FadeMs),
                label = "signInDotColor",
            )
            val dotScale by animateFloatAsState(
                targetValue = if (day.signed) 1f else 0.78f,
                animationSpec = MotionSpec.snap,
                label = "signInDotScale",
            )
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .graphicsLayer {
                        scaleX = dotScale
                        scaleY = dotScale
                    }
                    .clip(CircleShape)
                    .background(dotColor),
            )
        }
    }
}

@Composable
private fun SignInPill(signed: Boolean, onClick: () -> Unit) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    val interaction = remember { MutableInteractionSource() }
    val scale by rememberPressScale(interaction)
    val container by animateColorAsState(
        targetValue = if (signed) colors.successSoft else colors.accent,
        animationSpec = tween(MotionSpec.FadeMs),
        label = "signInPillContainer",
    )
    Button(
        onClick = onClick,
        enabled = !signed,
        interactionSource = interaction,
        shape = CircleShape,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = container,
            contentColor = colors.onAccent,
            disabledContainerColor = colors.successSoft,
            disabledContentColor = colors.successInk,
        ),
        modifier = Modifier
            .height(38.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
    ) {
        AnimatedContent(
            targetState = signed,
            transitionSpec = {
                (fadeIn(tween(180)) + scaleIn(initialScale = 0.86f)) togetherWith fadeOut(tween(120))
            },
            label = "signInPillLabel",
        ) { isSigned ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isSigned) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    text = if (isSigned) "已签到" else "签到",
                    style = texts.caption.copy(fontWeight = FontWeight.SemiBold),
                    // 按钮文案必须显式取按钮内容色：texts.caption 自带 secondaryText 色，
                    // 不覆盖会把白字/深绿字压成灰字（真机 v3.17 首轮截图定位）。
                    color = if (isSigned) colors.successInk else colors.onAccent,
                )
            }
        }
    }
}

/**
 * 整月签到日历（底部弹层）：周一开始、月初补空、固定 6 行；可向前翻历史月份，
 * 不能翻到未来。今天的格子描边，已签到格子 soft 底 + 小圆点。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignInCalendarSheet(
    summary: SignInSummary,
    onDismiss: () -> Unit,
    onSignIn: () -> Unit,
) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    val sheetState = rememberModalBottomSheetState()
    val shape = RoundedCornerShape(AppTheme.radius.lg)
    val today = LocalDate.now()
    val currentMonth = YearMonth.from(today)
    var visibleMonth by remember { mutableStateOf(currentMonth) }
    val weekLabels = listOf("一", "二", "三", "四", "五", "六", "日")

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.card,
        shape = shape,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppTheme.space.pageH)
                .padding(bottom = AppTheme.space.xl),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(text = "签到日历", style = texts.pageTitle)
                    Spacer(Modifier.height(AppTheme.space.xs))
                    Text(
                        text = "累计 " + summary.totalDays + " 天 · 最长连续 " + summary.longestStreak + " 天",
                        style = texts.caption,
                        color = colors.secondaryText,
                    )
                }
                if (summary.signedToday) {
                    Text(
                        text = "连续 " + summary.currentStreak + " 天",
                        style = texts.cardTitle,
                        color = colors.goldInk,
                    )
                }
            }
            Spacer(Modifier.height(AppTheme.space.md))
            Row(verticalAlignment = Alignment.CenterVertically) {
                BouncyIconButton(
                    icon = Icons.Outlined.KeyboardArrowLeft,
                    contentDescription = "上一个月",
                    onClick = { visibleMonth = visibleMonth.minusMonths(1) },
                    tint = colors.accentInk,
                )
                Text(
                    text = visibleMonth.year.toString() + " 年 " + visibleMonth.monthValue + " 月",
                    style = texts.cardTitle,
                    modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                BouncyIconButton(
                    icon = Icons.Outlined.KeyboardArrowRight,
                    contentDescription = "下一个月",
                    onClick = { visibleMonth = visibleMonth.plusMonths(1) },
                    enabled = visibleMonth < currentMonth,
                    tint = if (visibleMonth < currentMonth) colors.accentInk else colors.divider,
                )
            }
            Spacer(Modifier.height(AppTheme.space.xs))
            Row(modifier = Modifier.fillMaxWidth()) {
                weekLabels.forEach { label ->
                    Text(
                        text = label,
                        style = texts.caption,
                        color = colors.secondaryText,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Spacer(Modifier.height(AppTheme.space.xs))
            AnimatedContent(
                targetState = visibleMonth,
                transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
                label = "signInMonth",
            ) { month ->
                val grid = SignInStats.monthGrid(month, summary.signedDays, today)
                Column {
                    grid.cells.chunked(7).forEach { week ->
                        Row(modifier = Modifier.fillMaxWidth()) {
                            week.forEach { cell ->
                                MonthCell(cell = cell, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(AppTheme.space.md))
            if (!summary.signedToday) {
                AppButton(text = "今天签到", onClick = onSignIn, modifier = Modifier.fillMaxWidth())
            } else {
                Text(
                    text = "今天已签到，明天记得再来。",
                    style = texts.body,
                    color = colors.successInk,
                )
            }
        }
    }
}

@Composable
private fun MonthCell(cell: SignInMonthCell, modifier: Modifier = Modifier) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    val date = cell.date
    if (date == null) {
        Box(modifier = modifier.padding(2.dp).aspectRatio(1f))
        return
    }
    val future = date.isAfter(LocalDate.now())
    val background = if (cell.signed) colors.accentSoft else Color.Transparent
    Column(
        modifier = modifier
            .padding(2.dp)
            .aspectRatio(1f)
            .clip(CircleShape)
            .background(background)
            .then(
                if (cell.today) {
                    Modifier.border(width = 1.5.dp, color = colors.accent, shape = CircleShape)
                } else {
                    Modifier
                },
            )
            .semantics {
                contentDescription = date.toString() + when {
                    cell.signed -> " 已签到"
                    future -> " 未到"
                    else -> " 未签到"
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = date.dayOfMonth.toString(),
            style = texts.caption,
            color = when {
                cell.signed -> colors.accentInk
                future -> colors.secondaryText.copy(alpha = 0.45f)
                else -> colors.primaryText
            },
        )
        if (cell.signed) {
            Spacer(Modifier.height(2.dp))
            Box(
                modifier = Modifier
                    .size(4.dp)
                    .clip(CircleShape)
                    .background(colors.accent),
            )
        }
    }
}
