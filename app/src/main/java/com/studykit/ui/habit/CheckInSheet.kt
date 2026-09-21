package com.studykit.ui.habit

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import com.studykit.data.entity.CheckIn
import com.studykit.data.entity.Habit
import com.studykit.ui.components.AppButton
import com.studykit.ui.components.AppTextField
import com.studykit.ui.material.glassContainerColor
import com.studykit.ui.material.glassSurface
import com.studykit.ui.material.rememberGlassStyle
import com.studykit.ui.motion.MotionSpec
import com.studykit.ui.theme.AppTheme
import java.time.LocalDate

/**
 * 打卡弹层：今日打卡 / 补打卡 / 数量追加共用。
 * - 天数型：填写备注（默认带入习惯默认文案）
 * - 数量型：必填本次数量（可多次累加），备注可选
 *
 * 颜色与文字样式取自 `AppTheme`（弹层底色 `card` 随主题变化，夜间不再是硬白），
 * 间距/圆角取 `AppTheme.space` / `AppTheme.radius` 的 dp 常量；提示文案走 `accentInk` 而非 `accent`
 * （T1 裁定：accent 作纯文字在浅底只有 3.04:1，不达 AA）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CheckInSheet(
    habit: Habit,
    date: LocalDate,
    existing: CheckIn?,
    isMakeUp: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (note: String, amount: Double) -> Unit,
) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    val isCountType = habit.targetCount > 0
    var note by rememberSaveable { mutableStateOf(existing?.note?.ifBlank { habit.defaultText } ?: habit.defaultText) }
    var amountText by rememberSaveable { mutableStateOf("") }
    val parsedAmount = amountText.trim().toDoubleOrNull() ?: 0.0
    val confirmEnabled = if (isCountType) parsedAmount > 0 else true

    val title = when {
        isMakeUp -> "补打卡"
        existing != null && isCountType -> "追加打卡"
        existing != null -> "编辑打卡备注"
        else -> "打卡"
    }

    val glass = rememberGlassStyle()
    val sheetShape = RoundedCornerShape(AppTheme.radius.lg)
    // `AppTheme.settings` 是 @Composable getter，只能在组合层读，
    // 所以先把这一档取成普通局部量再带进 LaunchedEffect（在协程里读它编译不过）
    val reduceMotion = AppTheme.settings.reduceMotion
    val sweep = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        if (glass.enabled && !reduceMotion) {
            sweep.snapTo(0f)
            sweep.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = MotionSpec.SweepMs, easing = MotionSpec.Easing),
            )
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        // 弹层是浮在内容之上的，正是玻璃该待的地方。sheet 弹入时亮带扫过一次（一次性动画，
        // 跑完即静止），减弱动效或玻璃关掉时这次动画根本不排。
        modifier = Modifier.glassSurface(style = glass, shape = sheetShape, scrollPhase = { sweep.value }),
        sheetState = rememberModalBottomSheetState(),
        containerColor = glassContainerColor(),
        shape = sheetShape,
    ) {
        androidx.compose.foundation.layout.Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppTheme.space.pageH)
                .padding(bottom = AppTheme.space.xl),
        ) {
            Text(text = title, style = texts.pageTitle)
            Spacer(Modifier.height(AppTheme.space.xs))
            Text(
                text = buildString {
                    append("${habit.name} · ${date.monthValue}月${date.dayOfMonth}日")
                    if (isMakeUp) append("（补卡限过去 ${MAKEUP_WINDOW_DAYS.toInt()} 天内）")
                },
                style = texts.caption,
            )

            if (isCountType) {
                Spacer(Modifier.height(AppTheme.space.lg))
                if (existing != null) {
                    Text(
                        text = "今日已累计 ${formatAmount(existing.amount)} ${habit.unit}，目标 ${formatAmount(habit.targetCount)} ${habit.unit}",
                        style = texts.caption.copy(
                            color = colors.accentInk,
                            fontWeight = FontWeight.Medium,
                        ),
                    )
                    Spacer(Modifier.height(AppTheme.space.sm))
                }
                AppTextField(
                    value = amountText,
                    onValueChange = { amountText = it },
                    label = "本次数量（${habit.unit}）",
                    placeholder = "例如：${formatAmount(habit.targetCount)}",
                    keyboardType = KeyboardType.Decimal,
                )
            }

            Spacer(Modifier.height(AppTheme.space.lg))
            AppTextField(
                value = note,
                onValueChange = { note = it },
                label = if (isCountType) "备注（可选）" else "备注",
                placeholder = if (habit.defaultText.isNotBlank()) habit.defaultText else "写一句今天的感受…",
            )

            Spacer(Modifier.height(AppTheme.space.lg))
            AppButton(
                text = when {
                    isMakeUp -> "确认补卡"
                    existing != null && isCountType -> "累加进度"
                    else -> "确认打卡"
                },
                enabled = confirmEnabled,
                onClick = { onConfirm(note.trim(), if (isCountType) parsedAmount else 0.0) },
            )
        }
    }
}
