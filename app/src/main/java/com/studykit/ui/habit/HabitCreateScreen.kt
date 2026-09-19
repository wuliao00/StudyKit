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
import com.studykit.ui.theme.AppTheme

private val IconOptions = listOf("📖", "📝", "🏃", "💪", "🎧", "🎯", "🧘", "🌙", "💧", "🎹", "🖌️", "🥗")
private val TargetOptions = listOf(7, 21, 30, 60, 100)

/**
 * 创建习惯页：名称 + 图标 + 类型（天数/数量）+ 目标 + 默认打卡文案。
 *
 * 颜色与文字样式取 `AppTheme`；间距/圆角取 `AppTheme.space` / `AppTheme.radius` 的 dp 常量。
 * 选中的目标天数磁贴是**实底强调色容器** → `accentInk` 底 + `onAccent` 字（与 AppButton 同一套，
 * 夜间主题下白字压在亮色上只有 1.74:1，故实底必须用 ink 变体）；图标格与类型磁贴是柔底
 * → `accentSoft` 底 + `accentInk` 字，描边用 `accent`。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HabitCreateScreen(
    viewModel: HabitViewModel,
    onBack: () -> Unit,
) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
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
            .padding(horizontal = AppTheme.space.pageH),
    ) {
        Spacer(Modifier.height(AppTheme.space.sm))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = colors.accentInk,
                )
            }
            Spacer(Modifier.width(AppTheme.space.xs))
            Text(text = "新建习惯", style = texts.pageTitle)
        }

        Spacer(Modifier.height(AppTheme.space.lg))

        AppTextField(
            value = name,
            onValueChange = { name = it },
            label = "习惯名称",
            placeholder = "例如：背单词",
        )

        Spacer(Modifier.height(AppTheme.space.lg))
        Text(text = "图标", style = texts.caption)
        Spacer(Modifier.height(AppTheme.space.sm))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(AppTheme.space.sm),
            verticalArrangement = Arrangement.spacedBy(AppTheme.space.sm),
        ) {
            IconOptions.forEach { option ->
                val selected = option == icon
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(
                            if (selected) colors.accentSoft else colors.card,
                        )
                        .border(
                            width = if (selected) 1.5.dp else 1.dp,
                            color = if (selected) colors.accent else colors.divider,
                            shape = CircleShape,
                        )
                        .clickable { icon = option },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = option, fontSize = texts.pageTitle.fontSize)
                }
            }
        }

        Spacer(Modifier.height(AppTheme.space.lg))
        Text(text = "打卡类型", style = texts.caption)
        Spacer(Modifier.height(AppTheme.space.sm))
        Row(horizontalArrangement = Arrangement.spacedBy(AppTheme.space.sm)) {
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
            Spacer(Modifier.height(AppTheme.space.lg))
            Row(horizontalArrangement = Arrangement.spacedBy(AppTheme.space.md)) {
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
            Spacer(Modifier.height(AppTheme.space.xs))
            Text(
                text = "打卡时输入本次数量，逐次累加直到达成目标",
                style = texts.caption,
            )
        }

        Spacer(Modifier.height(AppTheme.space.lg))
        Text(text = if (isCountType) "目标期限（天）" else "目标天数", style = texts.caption)
        Spacer(Modifier.height(AppTheme.space.sm))
        Row(horizontalArrangement = Arrangement.spacedBy(AppTheme.space.sm)) {
            TargetOptions.forEach { option ->
                val selected = option == targetDays
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(AppTheme.radius.md))
                        .background(
                            if (selected) colors.accentInk else colors.card,
                        )
                        .border(
                            width = 1.dp,
                            color = if (selected) colors.accentInk else colors.divider,
                            shape = RoundedCornerShape(AppTheme.radius.md),
                        )
                        .clickable { targetDays = option }
                        .padding(vertical = AppTheme.space.sm),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "$option",
                        style = texts.aux.copy(
                            color = if (selected) colors.onAccent else colors.primaryText,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        ),
                    )
                }
            }
        }

        Spacer(Modifier.height(AppTheme.space.lg))
        AppTextField(
            value = defaultText,
            onValueChange = { defaultText = it },
            label = "默认打卡文案（可选）",
            placeholder = "一键打卡时自动带入备注",
        )

        Spacer(Modifier.height(AppTheme.space.xl))
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
        Spacer(Modifier.height(AppTheme.space.xl))
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
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(AppTheme.radius.md))
            .background(
                if (selected) colors.accentSoft else colors.card,
            )
            .border(
                width = if (selected) 1.5.dp else 1.dp,
                color = if (selected) colors.accent else colors.divider,
                shape = RoundedCornerShape(AppTheme.radius.md),
            )
            .clickable(onClick = onClick)
            .padding(AppTheme.space.card),
    ) {
        Text(
            text = title,
            style = texts.cardTitle.copy(
                color = if (selected) colors.accentInk else colors.primaryText,
            ),
        )
        Spacer(Modifier.height(2.dp))
        Text(text = subtitle, style = texts.caption)
    }
}
