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
 * 「标题 + 一句说清后果的正文 + 一枚确认 + 一枚取消」，各自写一份 near-identical 的
 * `AlertDialog` 就是重复缺陷。组件本身的行为没有变化。
 *
 * `danger` 决定确认按钮的字色（`warningInk` / `accentInk`），**不**改变按钮是否可点 ——
 * 危险程度靠措辞和颜色呈现，不做"长按才能删"那类花活。
 */
@Composable
fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    danger: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
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
                Text(text = "取消", color = colors.secondaryText)
            }
        },
    )
}
