package com.studykit.ui.habit

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.studykit.ui.components.AppButton
import com.studykit.ui.components.ConfirmDialog
import com.studykit.ui.theme.AppTheme
import java.time.LocalDate
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** 剩余时间排版：mm:ss（最大 45 分钟，两位分钟数够用） */
private fun formatClock(totalSeconds: Int): String =
    "%02d:%02d".format(totalSeconds / 60, totalSeconds % 60)

/** 从 Compose 树一路解包拿到宿主 Activity（Context 可能被 ContextWrapper 包着） */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * 专注一场（v2.4 批次三）：全屏倒计时，到点把分钟数按数量型打卡写进当天（计时打卡）。
 *
 * 几个刻意的选择：
 * - **会话在屏内创建**而不是调用方传入：计时的生命周期就是这一屏的生命周期 ——
 *   放弃/完成/进程死亡它就该消失，调用方没有任何理由握着它。起点也刻意**不进
 *   rememberSaveable**：设计文档定死"进程被杀视为中断，不记量"，saveable 会把起点一并
 *   带回来，让一场被杀掉的计时复活并照常写打卡 —— 那是伪造数据；宁可转屏重来一场。
 * - 时长选择（15/25/45，25 默认）放在开始前：一旦计时开始就不给"换时长"按钮，
 *   换时长等于重开一场，应当走"放弃"那道 Leroy 确认，而不是一条静默重置的缝。
 * - 放弃确认弹层写在**本屏**而不是列表页：中断的代价（Leroy 2009 的注意力残留）
 *   是跑计时的人才需要听的，列表页不该替它念一遍。
 */
@Composable
fun FocusScreen(
    habitId: Long,
    viewModel: HabitViewModel,
    onExit: () -> Unit,
) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // 防御脏路由参数：习惯**确已**不存在（列表非空却没有它）才退出 ——
    // 列表还在加载（items 为空）时不许把"没到货"误判成"没有这个习惯"而弹走用户
    val habit = state.items.firstOrNull { it.habit.id == habitId }?.habit
    LaunchedEffect(habit, state.items.size) {
        if (habit == null && state.items.isNotEmpty()) onExit()
    }

    var durationMinutes by rememberSaveable { mutableStateOf(FocusSession.DEFAULT_MINUTES) }
    var started by rememberSaveable { mutableStateOf(false) }
    // 起点 = 创建这一刻的 elapsedRealtime；keyed 在 (started, durationMinutes) 上，
    // 选择时长不会动它，按下"开始专注"才落起点
    val session = remember(started, durationMinutes) {
        FocusSession(
            habitId = habitId,
            durationMinutes = durationMinutes,
            startElapsedMs = SystemClock.elapsedRealtime(),
        )
    }

    // 唯一的"现在"：每 250ms 重取一次 elapsedRealtime（精度只要 1s，250ms 只是防跳秒）。
    // 挂后台回来也按起点补算，不维护任何"本地剩余"状态
    var nowMs by remember { mutableStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(session) {
        while (isActive) {
            nowMs = SystemClock.elapsedRealtime()
            delay(250)
        }
    }

    val complete = started && session.isComplete(nowMs)

    // 一次性提交位（参考 HabitViewModel.savingHabit 的注释模式）：到点的自动 effect 与
    // 「结束并记录」按钮可能同帧各触发一次，第二次必须被挡住 —— 多记一场比少记一场严重。
    var recorded by rememberSaveable { mutableStateOf(false) }

    /** 到点收尾的唯一路径：自动触发与按钮共用，写一次打卡（带分钟备注与数量）即退出 */
    val finishSession: () -> Unit = {
        if (complete && !recorded) {
            recorded = true
            habit?.let { current ->
                viewModel.submitCheckIn(
                    habit = current,
                    date = LocalDate.now(),
                    note = "专注 ${session.durationMinutes} 分钟",
                    amount = session.durationMinutes.toDouble(),
                )
            }
            onExit()
        }
    }
    LaunchedEffect(complete) {
        if (complete) finishSession()
    }

    // 屏幕常亮：专注页的全部意义就是"人还在场"，熄屏反而打断节奏。
    // add/clear 成对挂在 DisposableEffect 上，离开本屏（放弃/完成/弹栈）立即恢复原状
    val view = LocalView.current
    DisposableEffect(view) {
        val window = view.context.findActivity()?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    var showGiveUpDialog by rememberSaveable { mutableStateOf(false) }
    // 系统返回与「放弃」走同一条路：都先过 Leroy 确认，不留静默退出的缝。
    // 没开始无可放弃（正常返回）；已到点则由自动收尾退出，这两个状态都不拦
    BackHandler(enabled = started && !complete) { showGiveUpDialog = true }

    Column(modifier = Modifier.fillMaxSize()) {
        // ── 顶部：返回 = 放弃入口 ────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = { if (started) showGiveUpDialog = true else onExit() },
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    contentDescription = null,
                    tint = colors.accentInk,
                )
                Spacer(Modifier.width(AppTheme.space.xs))
                Text(
                    text = if (started) "放弃" else "返回",
                    style = texts.aux.copy(color = colors.accentInk),
                )
            }
        }

        // ── 中央：习惯名 + 剩余时间 ─────────────────────────────
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = habit?.name ?: "…",
                    style = texts.cardTitle,
                    color = colors.primaryText,
                )
                Spacer(Modifier.height(AppTheme.space.sm))
                if (!started) {
                    Text(
                        text = "选好时长，按下开始倒计时就走",
                        style = texts.caption,
                        color = colors.secondaryText,
                    )
                    Spacer(Modifier.height(AppTheme.space.md))
                    Row(horizontalArrangement = Arrangement.spacedBy(AppTheme.space.sm)) {
                        FocusSession.PRESETS.forEach { minutes ->
                            PresetChip(
                                label = "$minutes 分钟",
                                selected = minutes == durationMinutes,
                                onClick = { durationMinutes = minutes },
                            )
                        }
                    }
                } else {
                    Text(
                        text = "专注 ${session.durationMinutes} 分钟",
                        style = texts.caption,
                        color = colors.secondaryText,
                    )
                    Spacer(Modifier.height(AppTheme.space.md))
                    val remaining = session.remainingSeconds(nowMs)
                    Text(
                        text = formatClock(remaining),
                        style = texts.heroNumber,
                        // "24:59" 读屏会念成"二十四冒号五十九"，给一份人话等价朗读
                        modifier = Modifier.semantics {
                            contentDescription = "剩余 ${remaining / 60} 分 ${remaining % 60} 秒"
                        },
                    )
                }
            }
        }

        // ── 底部：开始 / 结束并记录 ─────────────────────────────
        if (!started) {
            AppButton(
                text = "开始专注",
                onClick = { started = true },
                enabled = habit != null,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = AppTheme.space.lg),
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = AppTheme.space.lg),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                AppButton(
                    text = "结束并记录",
                    onClick = finishSession,
                    enabled = complete,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (!complete) {
                    Spacer(Modifier.height(AppTheme.space.xs))
                    Text(
                        text = "还没到时间",
                        style = texts.caption,
                        color = colors.secondaryText,
                    )
                }
            }
        }
    }

    // ── 放弃确认：二次确认 + 注意力残留（Leroy 2009）────────────
    if (showGiveUpDialog) {
        ConfirmDialog(
            title = "放弃这一场？",
            body = "中途停下不会记录任何打卡。打断是有代价的 —— " +
                "Leroy（2009）的注意力残留研究：被打断的注意力会残留在这里。",
            confirmLabel = "放弃",
            // danger 只管字色：这一支确实是"丢掉这一场"，与清除数据同档
            danger = true,
            // 放弃按钮不叫「取消」—— 这里「取消」的日常语义恰好是"继续专注"，
            // 两个按钮会撞车。措辞跟着动词走，见 ConfirmDialog 的 dismissLabel。
            dismissLabel = "继续专注",
            onConfirm = {
                showGiveUpDialog = false
                // 放弃 = 不记任何打卡直接退出：中断如实，不伪造数据（设计文档口径）
                onExit()
            },
            onDismiss = { showGiveUpDialog = false },
        )
    }
}

/**
 * 时长选择 chip：选中 = `accentSoft` 底 + `accentInk` 字（本仓药丸的同一份配色），
 * 未选中 = 卡面色底 + 次要字。约 34dp 高，够不上 48dp 最小可点目标 ——
 * `minimumInteractiveComponentSize` 挂在链首只撑大不可见的点击槽位（终审 I9）。
 */
@Composable
private fun PresetChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    Box(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .clip(RoundedCornerShape(AppTheme.radius.md))
            .background(if (selected) colors.accentSoft else colors.card)
            .clickable(onClick = onClick)
            .padding(horizontal = AppTheme.space.md, vertical = AppTheme.space.sm),
    ) {
        Text(
            text = label,
            style = texts.aux.copy(
                color = if (selected) colors.accentInk else colors.secondaryText,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            ),
        )
    }
}
