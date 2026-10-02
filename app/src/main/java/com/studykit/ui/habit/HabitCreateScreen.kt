package com.studykit.ui.habit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import com.studykit.data.entity.Habit
import com.studykit.ui.components.AppButton
import com.studykit.ui.components.AppTextField
import com.studykit.ui.theme.AppTheme

private val IconOptions = listOf("📖", "📝", "🏃", "💪", "🎧", "🎯", "🧘", "🌙", "💧", "🎹", "🖌️", "🥗")
// 目标天数档位住在 `HabitViewModel.kt` 里的 HABIT_TARGET_DAY_OPTIONS：那边能被纯 JVM 单测读到，
// 事实守卫测试不该为了读一个常量而拖着整个 Compose 类加载进场。

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
    // 初值 = 实体那一个默认值（66），它必须是档位之一，否则进来那一格选不中
    var targetDays by rememberSaveable { mutableStateOf(Habit.DEFAULT_TARGET_DAYS) }
    var isCountType by rememberSaveable { mutableStateOf(false) }
    var targetCountText by rememberSaveable { mutableStateOf("") }
    var unit by rememberSaveable { mutableStateOf("") }
    var defaultText by rememberSaveable { mutableStateOf("") }
    // 执行意图三输入（v2.7 B16）：when 复用时段分类（不新造第二个时间选择器），where/then 自由文本。
    var category by rememberSaveable { mutableStateOf(Habit.CATEGORY_ANY) }
    var where by rememberSaveable { mutableStateOf("") }
    var then by rememberSaveable { mutableStateOf("") }
    // ANY = "没绑例程"，不进整句的"何时"段（否则拼成"当任意·…"很怪），退化成只留地点。
    val whenLabel = if (category == Habit.CATEGORY_ANY) "" else categoryLabel(category = category)
    val ifThen = IfThenTemplate.compose(whenLabel = whenLabel, where = where, then = then)
    // 那条 IF_THEN 贴士**不在本页渲染**（B17 复审 ⚠2）：计划钉的触发点是「首次成功保存」，
    // 而本页保存成功就 pop，挂在输入区下面等于用户还没保存就先被教了一遍。
    // 触发与渲染都在 ViewModel（一次性标记）+ 习惯列表页那一边。
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
            // 十二选一的图标选择器：整行标成单选组，读屏才会在组内横滑并播报「已选中」
            // （终审 I10 的同类站点，brief 点名的是题目录入的答案选择器，这里同一套缺陷）。
            // 纯语义修饰，不改布局、不改 ripple。
            modifier = Modifier.selectableGroup(),
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
                        // 48dp 见方，本就不需要 minimumInteractiveComponentSize
                        .selectable(
                            selected = selected,
                            role = Role.RadioButton,
                            onClick = { icon = option },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = option, fontSize = texts.pageTitle.fontSize)
                }
            }
        }

        Spacer(Modifier.height(AppTheme.space.lg))
        Text(text = "打卡类型", style = texts.caption)
        Spacer(Modifier.height(AppTheme.space.sm))
        Row(
            horizontalArrangement = Arrangement.spacedBy(AppTheme.space.sm),
            // 天数型 / 数量型是二选一的表单值 ⇒ 整行标成单选组（终审 I10 同类站点）
            modifier = Modifier.selectableGroup(),
        ) {
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
        Row(
            horizontalArrangement = Arrangement.spacedBy(AppTheme.space.sm),
            // 五选一的表单值 ⇒ 单选组（终审 I10 同类站点）
            modifier = Modifier.selectableGroup(),
        ) {
            HABIT_TARGET_DAY_OPTIONS.forEach { option ->
                val selected = option == targetDays
                Box(
                    modifier = Modifier
                        .weight(1f)
                        // 格体约 36dp 高，不到 48dp 最小可点目标（终审 I9）。挂链首只撑
                        // 不可见的点击槽位，clip/background/border 在它下游 ⇒ 磁贴一分不变。
                        .minimumInteractiveComponentSize()
                        .clip(RoundedCornerShape(AppTheme.radius.md))
                        .background(
                            if (selected) colors.accentInk else colors.card,
                        )
                        .border(
                            width = 1.dp,
                            color = if (selected) colors.accentInk else colors.divider,
                            shape = RoundedCornerShape(AppTheme.radius.md),
                        )
                        .selectable(
                            selected = selected,
                            role = Role.RadioButton,
                            onClick = { targetDays = option },
                        )
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

        Spacer(Modifier.height(AppTheme.space.sm))
        // 只给档位不给口径，用户会把 66 当成另一个 KPI。那句里的数字全从
        // `HabitViewModel.kt` 那几个常量生成，改了默认值不会漏改这句（HabitTargetDaysTest 钉着）。
        Text(text = HABIT_TARGET_DAYS_HINT, style = texts.caption)

        Spacer(Modifier.height(AppTheme.space.lg))
        AppTextField(
            value = defaultText,
            onValueChange = { defaultText = it },
            label = "默认打卡文案（可选）",
            placeholder = "一键打卡时自动带入备注",
        )

        // ── 执行意图（v2.7 B16 · app.docx 模块4 P0）：把习惯写成"当【何时·何地】，我就【做什么】"。
        // when 直接复用时段分类（Gardner 2021 绑例程的既有决定，不新造第二个时间选择器），
        // where/then 走既有 AppTextField；三样只组装成一个字符串写进 habits.ifThen，不新增列。
        Spacer(Modifier.height(AppTheme.space.xl))
        Text(
            text = "执行意图（可选）",
            style = texts.caption.copy(fontWeight = FontWeight.Medium),
        )
        Spacer(Modifier.height(AppTheme.space.xs))
        Text(
            text = "把它绑到具体的时段、地点和动作，更容易真的做成。",
            style = texts.caption,
            color = colors.secondaryText,
        )

        Spacer(Modifier.height(AppTheme.space.md))
        Text(text = "何时", style = texts.caption)
        Spacer(Modifier.height(AppTheme.space.sm))
        Row(
            horizontalArrangement = Arrangement.spacedBy(AppTheme.space.xs),
            // 时段分类是二选一的表单值 ⇒ 整行标成单选组（与图标格 / 类型磁贴同一套 a11y 纪律）
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .selectableGroup(),
        ) {
            Habit.CATEGORIES.forEach { option ->
                val selected = option == category
                Box(
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .clip(RoundedCornerShape(AppTheme.radius.md))
                        .background(if (selected) colors.accentSoft else colors.card)
                        .border(
                            width = 1.dp,
                            color = if (selected) colors.accent else colors.divider,
                            shape = RoundedCornerShape(AppTheme.radius.md),
                        )
                        .selectable(
                            selected = selected,
                            role = Role.RadioButton,
                            onClick = { category = option },
                        )
                        .padding(horizontal = AppTheme.space.md, vertical = AppTheme.space.xs),
                ) {
                    Text(text = categoryLabel(category = option), style = texts.caption)
                }
            }
        }

        Spacer(Modifier.height(AppTheme.space.md))
        AppTextField(
            value = where,
            onValueChange = { where = it },
            label = "何地（可选）",
            placeholder = "例如：书桌前",
        )
        Spacer(Modifier.height(AppTheme.space.md))
        AppTextField(
            value = then,
            onValueChange = { then = it },
            label = "做什么",
            placeholder = "例如：背 10 个单词",
        )
        // 只填了"做什么"才组装得出句子，实时把成品整句回显出来（预览为空就不占位）
        if (ifThen.isNotBlank()) {
            Spacer(Modifier.height(AppTheme.space.sm))
            IfThenPreview(sentence = ifThen)
        }

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
                    ifThen = ifThen,
                    category = category,
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
            // `selectable` 顶掉原 `clickable`：多挂 Role.RadioButton + 选中态，
            // indication 仍走本地默认 ⇒ ripple 与配色一分不动（终审 I10 同类站点）。
            // 磁贴本身两行文案 + 16dp 内边距，已在 48dp 之上，不需要 minimumInteractiveComponentSize。
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onClick,
            )
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

/**
 * 执行意图实时预览行：把 [IfThenTemplate] 组装好的整句原样回显。单独抽成一个无状态叶子
 * （同 ConfidenceRow 的手法）是为了让它能被 [IfThenPreview] 的渲染守卫单独 setContent 钉住，
 * 不必拖着整个要 viewModel 的创建页进场。只展示，不持有状态。
 */
@Composable
internal fun IfThenPreview(sentence: String) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppTheme.radius.md))
            .background(colors.accentSoft)
            .padding(AppTheme.space.card),
    ) {
        Text(text = sentence, style = texts.aux.copy(color = colors.accentInk))
    }
}
