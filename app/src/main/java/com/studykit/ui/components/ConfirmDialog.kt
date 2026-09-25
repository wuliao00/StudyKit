package com.studykit.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.studykit.ui.theme.AppTheme

/**
 * 破坏性动作的确认对话框（写法与 `MistakeDetailScreen` 的删除确认同一套）。
 *
 * 从 `SettingsScreen` 提到这里，是因为契约页的撤销/删除需要同一个骨架：两处都要
 * 「标题 + 一句说清后果的正文 + 一枚确认 + 一枚放弃」，各自写一份 near-identical 的
 * `AlertDialog` 就是重复缺陷。组件本身的行为没有变化。
 *
 * `danger` 决定确认按钮的字色（`warningInk` / `accentInk`），**不**改变按钮是否可点 ——
 * 危险程度靠措辞和颜色呈现，不做"长按才能删"那类花活。
 *
 * `dismissLabel` 默认「取消」，那一类确认框的动词是清除/删除/覆盖，「取消」不是它们的
 * 同义词，读不出歧义。**但当破坏性动词本身就是「撤销」时（契约页 ACTIVE 那一支），
 * 「取消」是它的日常同义词** —— 想撤的人会把「取消这份契约」当成退出对话框，点下去
 * 什么也没说明，这份契约还留着。那种调用方必须换一个不撞车的词（`DictStoreScreen`
 * 撤销词库那枚对话框用「留着」，同一个判断）。措辞跟着动词走这件事留在调用方的纯函数里，
 * 组件只提供这个口子。
 */
@Composable
fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    danger: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    dismissLabel: String = "取消",
) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title, style = texts.cardTitle) },
        text = { Text(text = body, style = texts.aux) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = confirmLabel,
                    color = if (danger) colors.warningInk else colors.accentInk,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = dismissLabel, color = colors.secondaryText)
            }
        },
    )
}
