package com.studykit.ui.habit

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import com.studykit.data.entity.CheckIn
import com.studykit.data.entity.Habit
import com.studykit.ui.components.AppButton
import com.studykit.ui.components.AppTextField
import com.studykit.ui.theme.DesignTokens
import java.time.LocalDate

/**
 * 打卡弹层：今日打卡 / 补打卡 / 数量追加共用。
 * - 天数型：填写备注（默认带入习惯默认文案）
 * - 数量型：必填本次数量（可多次累加），备注可选
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

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
        containerColor = DesignTokens.Card,
        shape = RoundedCornerShape(DesignTokens.CornerRadiusLg),
    ) {
        androidx.compose.foundation.layout.Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = DesignTokens.PageHorizontalPadding)
                .padding(bottom = DesignTokens.SpacingXl),
        ) {
            Text(text = title, style = DesignTokens.PageTitle)
            Spacer(Modifier.height(DesignTokens.SpacingXs))
            Text(
                text = buildString {
                    append("${habit.name} · ${date.monthValue}月${date.dayOfMonth}日")
                    if (isMakeUp) append("（补卡限过去 ${MAKEUP_WINDOW_DAYS.toInt()} 天内）")
                },
                style = DesignTokens.Caption,
            )

            if (isCountType) {
                Spacer(Modifier.height(DesignTokens.SpacingLg))
                if (existing != null) {
                    Text(
                        text = "今日已累计 ${formatAmount(existing.amount)} ${habit.unit}，目标 ${formatAmount(habit.targetCount)} ${habit.unit}",
                        style = DesignTokens.Caption.copy(
                            color = DesignTokens.Accent,
                            fontWeight = FontWeight.Medium,
                        ),
                    )
                    Spacer(Modifier.height(DesignTokens.SpacingSm))
                }
                AppTextField(
                    value = amountText,
                    onValueChange = { amountText = it },
                    label = "本次数量（${habit.unit}）",
                    placeholder = "例如：${formatAmount(habit.targetCount)}",
                    keyboardType = KeyboardType.Decimal,
                )
            }

            Spacer(Modifier.height(DesignTokens.SpacingLg))
            AppTextField(
                value = note,
                onValueChange = { note = it },
                label = if (isCountType) "备注（可选）" else "备注",
                placeholder = if (habit.defaultText.isNotBlank()) habit.defaultText else "写一句今天的感受…",
            )

            Spacer(Modifier.height(DesignTokens.SpacingLg))
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
