package com.studykit.ui.habit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.studykit.ui.components.AppButton
import com.studykit.ui.components.AppTextField
import com.studykit.ui.theme.DesignTokens

private val IconOptions = listOf("📖", "📝", "🏃", "💪", "🎧", "🎯", "🧘", "🌙", "💧", "🎹", "🖌️", "🥗")
private val TargetOptions = listOf(7, 21, 30, 60, 100)

/** 创建习惯页：名称 + 图标 + 类型（天数/数量）+ 目标 + 默认打卡文案 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HabitCreateScreen(
    viewModel: HabitViewModel,
    onBack: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var icon by rememberSaveable { mutableStateOf("🎯") }
    var targetDays by rememberSaveable { mutableStateOf(21) }
    var isCountType by rememberSaveable { mutableStateOf(false) }
    var targetCountText by rememberSaveable { mutableStateOf("") }
    var unit by rememberSaveable { mutableStateOf("") }
    var defaultText by rememberSaveable { mutableStateOf("") }
    val parsedCount = targetCountText.trim().toDoubleOrNull() ?: 0.0
    val canSave = name.isNotBlank() && (!isCountType || parsedCount > 0)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = DesignTokens.PageHorizontalPadding),
    ) {
        Spacer(Modifier.height(DesignTokens.SpacingSm))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = DesignTokens.Accent,
                )
            }
            Spacer(Modifier.width(DesignTokens.SpacingXs))
            Text(text = "新建习惯", style = DesignTokens.PageTitle)
        }

        Spacer(Modifier.height(DesignTokens.SpacingLg))

        AppTextField(
            value = name,
            onValueChange = { name = it },
            label = "习惯名称",
            placeholder = "例如：背单词",
        )

        Spacer(Modifier.height(DesignTokens.SpacingLg))
        Text(text = "图标", style = DesignTokens.Caption)
        Spacer(Modifier.height(DesignTokens.SpacingSm))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(DesignTokens.SpacingSm),
            verticalArrangement = Arrangement.spacedBy(DesignTokens.SpacingSm),
        ) {
            IconOptions.forEach { option ->
                val selected = option == icon
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(
                            if (selected) DesignTokens.Accent.copy(alpha = 0.12f)
                            else DesignTokens.Card,
                        )
                        .border(
                            width = if (selected) 1.5.dp else 1.dp,
                            color = if (selected) DesignTokens.Accent else DesignTokens.Divider,
                            shape = CircleShape,
                        )
                        .clickable { icon = option },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = option, fontSize = DesignTokens.PageTitle.fontSize)
                }
            }
        }

        Spacer(Modifier.height(DesignTokens.SpacingLg))
        Text(text = "打卡类型", style = DesignTokens.Caption)
        Spacer(Modifier.height(DesignTokens.SpacingSm))
        Row(horizontalArrangement = Arrangement.spacedBy(DesignTokens.SpacingSm)) {
            TypeChip(
                title = "天数型",
                subtitle = "坚持 N 天",
                selected = !isCountType,
                modifier = Modifier.weight(1f),
                onClick = { isCountType = false },
            )
            TypeChip(
                title = "数量型",
                subtitle = "累计目标量",
                selected = isCountType,
                modifier = Modifier.weight(1f),
                onClick = { isCountType = true },
            )
        }

        if (isCountType) {
            Spacer(Modifier.height(DesignTokens.SpacingLg))
            Row(horizontalArrangement = Arrangement.spacedBy(DesignTokens.SpacingMd)) {
                AppTextField(
                    value = targetCountText,
                    onValueChange = { targetCountText = it },
                    label = "目标总数量",
                    placeholder = "例如：50",
                    keyboardType = KeyboardType.Decimal,
                    modifier = Modifier.weight(2f),
                )
                AppTextField(
                    value = unit,
                    onValueChange = { unit = it },
                    label = "单位",
                    placeholder = "个/公里…",
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(DesignTokens.SpacingXs))
            Text(
                text = "打卡时输入本次数量，逐次累加直到达成目标",
                style = DesignTokens.Caption,
            )
        }

        Spacer(Modifier.height(DesignTokens.SpacingLg))
        Text(text = if (isCountType) "目标期限（天）" else "目标天数", style = DesignTokens.Caption)
        Spacer(Modifier.height(DesignTokens.SpacingSm))
        Row(horizontalArrangement = Arrangement.spacedBy(DesignTokens.SpacingSm)) {
            TargetOptions.forEach { option ->
                val selected = option == targetDays
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(DesignTokens.CornerRadius))
                        .background(
                            if (selected) DesignTokens.Accent else DesignTokens.Card,
                        )
                        .border(
                            width = 1.dp,
                            color = if (selected) DesignTokens.Accent else DesignTokens.Divider,
                            shape = RoundedCornerShape(DesignTokens.CornerRadius),
                        )
                        .clickable { targetDays = option }
                        .padding(vertical = DesignTokens.SpacingSm),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "$option",
                        style = DesignTokens.Auxiliary.copy(
                            color = if (selected) DesignTokens.Card else DesignTokens.PrimaryText,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        ),
                    )
                }
            }
        }

        Spacer(Modifier.height(DesignTokens.SpacingLg))
        AppTextField(
            value = defaultText,
            onValueChange = { defaultText = it },
            label = "默认打卡文案（可选）",
            placeholder = "一键打卡时自动带入备注",
        )

        Spacer(Modifier.height(DesignTokens.SpacingXl))
        AppButton(
            text = "保存习惯",
            enabled = canSave,
            onClick = {
                viewModel.createHabit(
                    name = name,
                    icon = icon,
                    targetDays = targetDays,
                    targetCount = if (isCountType) parsedCount else 0.0,
                    unit = if (isCountType) unit.ifBlank { "个" } else "",
                    defaultText = defaultText,
                ) { onBack() }
            },
        )
        Spacer(Modifier.height(DesignTokens.SpacingXl))
    }
}

/** 打卡类型选择磁贴：标题 + 说明，选中态为强调色描边浅底 */
@Composable
private fun TypeChip(
    title: String,
    subtitle: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(DesignTokens.CornerRadius))
            .background(
                if (selected) DesignTokens.Accent.copy(alpha = 0.10f) else DesignTokens.Card,
            )
            .border(
                width = if (selected) 1.5.dp else 1.dp,
                color = if (selected) DesignTokens.Accent else DesignTokens.Divider,
                shape = RoundedCornerShape(DesignTokens.CornerRadius),
            )
            .clickable(onClick = onClick)
            .padding(DesignTokens.CardPadding),
    ) {
        Text(
            text = title,
            style = DesignTokens.CardTitle.copy(
                color = if (selected) DesignTokens.Accent else DesignTokens.PrimaryText,
            ),
        )
        Spacer(Modifier.height(2.dp))
        Text(text = subtitle, style = DesignTokens.Caption)
    }
}
