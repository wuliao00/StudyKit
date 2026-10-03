package com.studykit.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import com.studykit.ui.bulkimport.BulkAction
import com.studykit.ui.theme.AppTheme

/**
 * 各屏右上角的「＋录入」下拉菜单：把录入入口从一颗按钮扩成一小张表。
 *
 * 触发器**保持原来那颗按钮的样子**（`Add` 图标 + `录入` 文案 + `accentInk`），口径同单词库页
 * 「＋批量导入」——图标纯装饰，所以读屏念的是整枚按钮的 `contentDescription`（"录入菜单"），
 * 而不是「加号 录入」这种半截话。
 *
 * 菜单内容由调用方传进来的动作列表决定（真源是 `BulkEntries.menuFor`）；
 * 列表为空就【什么都不渲染】——这一条同时被渲染守卫钉住：入口本来就已齐的屏，
 * 不该因为"别的屏加了菜单"就跟着长出一把第二道门。
 */
@Composable
fun EntryMenuButton(
    items: List<BulkAction>,
    onSelect: (BulkAction) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "录入",
    description: String = "录入菜单",
) {
    if (items.isEmpty()) return
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    var expanded by remember { mutableStateOf(false) }

    // Box 是 DropdownMenu 的锚点（M3 官方姿势）：菜单贴着这枚按钮下方展开，
    // 而不是落在整屏的某个角落。
    Box(modifier = modifier) {
        TextButton(
            onClick = { expanded = true },
            modifier = Modifier.semantics { contentDescription = description },
        ) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = null,
                tint = colors.accentInk,
            )
            Spacer(Modifier.width(AppTheme.space.xs))
            Text(
                text = label,
                style = texts.aux.copy(color = colors.accentInk, fontWeight = FontWeight.Medium),
            )
        }

        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            items.forEach { action ->
                DropdownMenuItem(
                    text = { Text(text = action.label, style = texts.aux) },
                    onClick = {
                        // 先收菜单再回报：反过来时 onSelect 里若立刻换屏，菜单会留在旧栈上
                        expanded = false
                        onSelect(action)
                    },
                )
            }
        }
    }
}
