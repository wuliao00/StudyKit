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
import com.studykit.ui.material.glassSurface
import com.studykit.ui.material.rememberGlassStyle
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

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        // 材质**不挂在这里**。ModalBottomSheet 的 modifier 落在 dialog 根容器上，与它的面板
        // 测量打架：玻璃开着时整棵内容子树不进树，只剩一层遮罩 —— 屏幕变暗看得见，内容点不着，
        // uiautomator 里也查不到任何节点（2026-09-24 真机 A/B 定位：只切「玻璃材质」这一个变量，
        // 开 = dump 2663 字节仅「关闭工作表」，关 = 5951 字节含「确认补卡」等全部节点，开→关→开可复现）。
        // 所以底色的活交给 containerColor 画整块面板（连拖动手柄那一条一起画，不留接缝），
        // 顶部高光与细切割边画在下面那个内容 Column 上 —— 那里是普通 composable，
        // 与底栏、词库状态卡是同一种用法。v3.2 起不再有任何亮带动画。
        sheetState = rememberModalBottomSheetState(),
        containerColor = if (glass.enabled) glass.tint else colors.card,
        shape = sheetShape,
    ) {
        androidx.compose.foundation.layout.Column(
            modifier = Modifier
                .fillMaxWidth()
                .glassSurface(
                    style = glass,
                    shape = sheetShape,
                    paintTint = false,
                )
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

            // 那条 MISS_ONE_DAY 宽恕贴士**不在弹层里画了**（B17 复审 ⚠3）：天数型一键打卡根本没有
            // 弹层，挂在这里等于那条路径永远碰不到它。判定与渲染都收上到列表层那一张
            // [OneShotTipCard]，两路共用 [gapTipShouldShow] 这一个闸门，只说一次。

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
