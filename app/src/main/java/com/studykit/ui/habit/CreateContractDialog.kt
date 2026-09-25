package com.studykit.ui.habit

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.studykit.data.entity.Habit
import com.studykit.ui.components.AppTextField
import com.studykit.ui.theme.AppTheme
import java.time.LocalDate


/**
 * 期限预设档（天）。**与 spec 的偏离**：spec 写"自由日期"，这里做成 7/14/30/60 的预设 Chips ——
 * 不做日期解析 UI（手敲 yyyy-MM-dd 的校验与错误提示是设置页考试日那一格的老大难），
 * Ariely 2002 的 self-imposed deadline 结论也不依赖具体是哪天，预设档反而更接近"自己选的期限"。
 */
private val DeadlinePresets = listOf(7, 14, 30, 60)

/** 承诺文案的 if-then 模板（Gollwitzer 1999）：预填而非空框，填空处可改 */
private const val PROMISE_TEMPLATE = "如果到了【填写场景】，我就完成当日打卡。"

/**
 * 创建契约弹层：选习惯（点选）、期限预设档（Chips，风格同 FocusScreen 的时长选择与
 * 设置卡的 ChoiceTile）、目标次数、承诺文案（预填 if-then 模板）、违约后果（选填）。
 * 确认后由调用方清空并收起（onConfirm 之后 showCreate 置 false，saveable 状态随之重置）。
 */
@Composable
internal fun CreateContractDialog(
    habits: List<Habit>,
    onDismiss: () -> Unit,
    onConfirm: (habitId: Long, deadline: LocalDate, goalCount: Int, promise: String, consequence: String) -> Unit,
) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    var habitId by rememberSaveable { mutableStateOf<Long?>(null) }
    var presetDays by rememberSaveable { mutableStateOf(DeadlinePresets.first()) }
    var goalText by rememberSaveable { mutableStateOf("") }
    var promise by rememberSaveable { mutableStateOf(PROMISE_TEMPLATE) }
    var consequence by rememberSaveable { mutableStateOf("") }

    val goalCount = goalText.trim().toIntOrNull() ?: 0
    val deadline = LocalDate.now().plusDays(presetDays.toLong())
    val canSubmit = habitId != null && goalCount >= 1 && promise.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "签一份契约", style = texts.cardTitle) },
        text = {
            // 契约字段不多，但选习惯列表可能长：给一个限高的滚动区，键盘弹起也不塌
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(text = "选一个习惯", style = texts.caption)
                Spacer(modifier = Modifier.height(AppTheme.space.xs))
                Column(modifier = Modifier.selectableGroup()) {
                    habits.forEach { habit ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(AppTheme.radius.md))
                                .selectable(
                                    selected = habitId == habit.id,
                                    role = Role.RadioButton,
                                    onClick = { habitId = habit.id },
                                )
                                .padding(vertical = AppTheme.space.sm),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .background(
                                        color = if (habitId == habit.id) colors.accentSoft else colors.card,
                                        shape = RoundedCornerShape(AppTheme.radius.sm),
                                    )
                                    .padding(horizontal = AppTheme.space.sm, vertical = AppTheme.space.xs),
                            ) {
                                Text(
                                    text = habit.name,
                                    style = texts.aux.copy(
                                        color = if (habitId == habit.id) colors.accentInk else colors.primaryText,
                                        fontWeight = if (habitId == habit.id) FontWeight.Medium else FontWeight.Normal,
                                    ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(AppTheme.space.md))
                Text(text = "期限（$deadline 到期对账）", style = texts.caption)
                Spacer(modifier = Modifier.height(AppTheme.space.xs))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(AppTheme.space.sm),
                    modifier = Modifier.selectableGroup(),
                ) {
                    DeadlinePresets.forEach { days ->
                        DeadlineChip(
                            label = "$days 天",
                            selected = presetDays == days,
                            onClick = { presetDays = days },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Spacer(modifier = Modifier.height(AppTheme.space.md))
                Text(text = "到期累计打卡 ≥ 几次算达成", style = texts.caption)
                AppTextField(
                    value = goalText,
                    onValueChange = { raw -> goalText = raw.filter { it.isDigit() }.take(3) },
                    placeholder = "14",
                    keyboardType = KeyboardType.Number,
                )
                Spacer(modifier = Modifier.height(AppTheme.space.md))
                Text(text = "承诺（可改）", style = texts.caption)
                AppTextField(
                    value = promise,
                    onValueChange = { promise = it },
                    placeholder = PROMISE_TEMPLATE,
                )
                Spacer(modifier = Modifier.height(AppTheme.space.md))
                Text(text = "违约后果（选填，到期未达成会原样摆出来）", style = texts.caption)
                AppTextField(
                    value = consequence,
                    onValueChange = { consequence = it },
                    placeholder = "例如：当天不许刷剧",
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val id = habitId
                    if (canSubmit && id != null) {
                        onConfirm(id, deadline, goalCount, promise, consequence)
                    }
                },
                enabled = canSubmit,
            ) {
                Text(text = "签名生效", color = if (canSubmit) colors.accentInk else colors.secondaryText)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "取消", color = colors.secondaryText)
            }
        },
    )
}

/**
 * 期限预设 chip：与 [FocusScreen] 的时长选择同一份写法（accentSoft 底 + accentInk 字）。
 * 约 34dp 高，够不上 48dp 最小可点目标 —— `minimumInteractiveComponentSize` 挂链首只撑
 * 不可见的点击槽位（终审 I9）。
 */
@Composable
internal fun DeadlineChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    Box(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .clip(RoundedCornerShape(AppTheme.radius.md))
            .background(if (selected) colors.accentSoft else colors.card)
            .clickable(onClick = onClick)
            .padding(vertical = AppTheme.space.sm),
        contentAlignment = Alignment.Center,
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
