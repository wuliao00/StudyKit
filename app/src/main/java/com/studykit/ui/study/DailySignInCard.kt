package com.studykit.ui.study

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import com.studykit.ui.motion.MotionSpec
import com.studykit.ui.motion.rememberPressScale
import com.studykit.ui.theme.AppTheme
import com.studykit.util.signin.SignInDay
import com.studykit.util.signin.SignInSummary
import java.time.LocalDate

/**
 * 首页“每日签到”卡：签到按钮 + 连续/累计天数 + 最近 7 天的小圆点。
 *
 * 交互约定：
 * - 点卡片主体（含 7 天圆点）打开 14 天签到日历；
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
        "每日签到，今天已签到，连续 " + summary.currentStreak + " 天"
    } else {
        "每日签到，今天还没签到"
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
                WeekDots(days = summary.last14.takeLast(7))
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
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        days.forEach { day ->
            val dotColor by animateColorAsState(
                targetValue = if (day.signed) colors.accent else colors.divider,
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
                    .size(8.dp)
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
 * 14 天签到日历（底部弹层）。只展示最近 14 天，不做整月翻页 —— 首页要的是“最近有没有断”，
 * 不是历史账本；老数据仍在库里，统计由 [SignInSummary] 现算。
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
            summary.last14.chunked(7).forEach { week ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    week.forEach { day ->
                        SignInDayCell(day = day, modifier = Modifier.weight(1f))
                    }
                }
                Spacer(Modifier.height(6.dp))
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
private fun SignInDayCell(day: SignInDay, modifier: Modifier = Modifier) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    val today = day.date == LocalDate.now()
    val background = when {
        day.signed -> colors.accentSoft
        else -> Color.Transparent
    }
    Column(
        modifier = modifier
            .padding(2.dp)
            .aspectRatio(1f)
            .clip(CircleShape)
            .background(background)
            .then(
                if (today) {
                    Modifier.border(width = 1.5.dp, color = colors.accent, shape = CircleShape)
                } else {
                    Modifier
                },
            )
            .semantics {
                contentDescription = day.date.toString() + if (day.signed) " 已签到" else " 未签到"
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = day.date.dayOfMonth.toString(),
            style = texts.caption,
            color = if (day.signed) colors.accentInk else colors.secondaryText,
        )
        if (day.signed) {
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
