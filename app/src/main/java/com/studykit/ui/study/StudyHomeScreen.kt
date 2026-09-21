package com.studykit.ui.study

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.studykit.ui.components.AppCard
import com.studykit.ui.components.AppPill
import com.studykit.ui.components.RingGauge
import com.studykit.ui.components.StatTile
import com.studykit.ui.motion.MotionSpec
import com.studykit.ui.motion.StaggeredIn
import com.studykit.ui.theme.AppTheme
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * 学习首页（学习 Tab）：页标题 + 今日任务 hero 卡（进度环 + 火焰徽章）、统计磁贴、三张入口卡片。
 * 颜色与文字样式统一取 `AppTheme`；间距/圆角取 `AppTheme.space` / `AppTheme.radius` 的 dp 常量。
 */
@Composable
fun StudyHomeScreen(
    viewModel: StudyViewModel,
    onOpenWords: () -> Unit,
    onStartQuiz: () -> Unit,
    onOpenMistakes: () -> Unit,
    onAddWord: () -> Unit,
    onAddQuestion: () -> Unit,
    onBulkImportQuestions: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    val state by viewModel.homeState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = AppTheme.space.pageH),
    ) {
        Spacer(Modifier.height(AppTheme.space.sm))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = "学习", style = texts.largeTitle)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onAddWord) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = null,
                    tint = colors.accentInk,
                )
                Spacer(Modifier.width(AppTheme.space.xs))
                Text(
                    text = "录入",
                    style = texts.aux.copy(
                        color = colors.accentInk,
                        fontWeight = FontWeight.Medium,
                    ),
                )
            }
            // 设置入口。这枚图标旁边**没有**等价文字（与底栏"图标 + label 同一语义节点"那种相反），
            // 所以必须给 contentDescription，否则读屏只会念出一个 unnamed 按钮。
            // 触摸目标由 IconButton 自身保证 48dp，不再叠 minimumInteractiveComponentSize。
            IconButton(onClick = onOpenSettings) {
                Icon(
                    imageVector = Icons.Outlined.Settings,
                    contentDescription = "设置",
                    tint = colors.secondaryText,
                )
            }
        }

        Spacer(Modifier.height(AppTheme.space.md))
        TodayHeroCard(
            todayDone = state.todayDone,
            dueCount = state.dueCount,
            streakDays = state.streakDays,
        )

        Spacer(Modifier.height(AppTheme.space.md))
        Row(
            horizontalArrangement = Arrangement.spacedBy(AppTheme.space.sm),
            modifier = Modifier.height(IntrinsicSize.Max),
        ) {
            StatTile(
                value = "${state.dueCount}",
                label = "今日待复习",
                modifier = Modifier.weight(1f),
            )
            StatTile(
                value = "${state.totalCount}",
                label = "单词总数",
                modifier = Modifier.weight(1f),
            )
            StatTile(
                value = "${state.masteredCount}",
                label = "已掌握",
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(AppTheme.space.lg))

        StaggeredIn(index = 0) {
            EntryCard(
                icon = Icons.Outlined.Star,
                iconColor = colors.accent,
                iconContainerColor = colors.accentSoft,
                title = "背单词",
                caption = "今日待复习 ${state.dueCount} 个 · 卡片翻面记忆",
                onClick = onOpenWords,
            )
        }
        Spacer(Modifier.height(AppTheme.space.md))
        StaggeredIn(index = 1) {
            EntryCard(
                icon = Icons.Outlined.CheckCircle,
                iconColor = colors.success,
                iconContainerColor = colors.successSoft,
                title = "题库练习",
                caption = "按学科刷题 · 答错自动入错题本",
                onClick = onStartQuiz,
                trailing = {
                    IconButton(onClick = onAddQuestion) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = "录入题目",
                            tint = colors.accentInk,
                        )
                    }
                    // 批量入口与单条录入并排：图标刻意用本题卡自己的 CheckCircle（core 图标集里
                    // 没有「多行/导入」形状，硬凑一个反而看不出区别），区别由 contentDescription 承担。
                    IconButton(onClick = onBulkImportQuestions) {
                        Icon(
                            imageVector = Icons.Outlined.CheckCircle,
                            contentDescription = "批量录入题目",
                            tint = colors.accentInk,
                        )
                    }
                },
            )
        }
        Spacer(Modifier.height(AppTheme.space.md))
        StaggeredIn(index = 2) {
            EntryCard(
                icon = Icons.Outlined.Close,
                iconColor = colors.warning,
                iconContainerColor = colors.warningSoft,
                title = "错题本",
                caption = "${state.mistakeCount} 道待掌握",
                onClick = onOpenMistakes,
            )
        }
        Spacer(Modifier.height(AppTheme.space.lg))
    }
}

/**
 * 今日任务 hero 卡：左侧 88dp 进度环（完成次数 / 今日总任务，达成转 `goldInk`——
 * 纯 `gold` 在浅色卡面只有 1.79:1，细环几乎看不见），
 * 右侧「今日待办」标题 + `todayDone / total` 大数 + 连续学习火焰徽章。
 *
 * `todayDone` 统计的是**复习/练习次数**（会话数），不是学习天数，因此文案只报「今日待办」计数、
 * 不出现「天」字；「连续 X 天」只属于 [FlameBadge]（`streakDays`，由单词复习与题目练习共同驱动）。
 * 标题用「待办」而非「任务」：`dueCount` 含逾期项，「任务」会高估今日口径。
 *
 * 参数只收 hero 渲染所需的三个字段（而非整个 [StudyHomeUiState]）：
 * `mistakeCount/totalCount` 等其余字段变化时不触发本卡重组。
 * 容器保持 88dp 正方形：`RingGauge` 已在容器内双向居中（弧不再锚定左上角），但直径仍取
 * `minDimension`，正方形才能让环的大小与容器高度解耦。
 */
@Composable
private fun TodayHeroCard(
    todayDone: Int,
    dueCount: Int,
    streakDays: Int,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    // 每日词数目标来自设置（AppSettings.dailyWordGoal，默认 20）。这里只做陈述：
    // 达成不改配色 —— 环的 gold 达成态由"待办清零"这件事实驱动（见下面 progress 的注释），
    // 目标只是给用户一个"今天到哪算够"的参照，不该反过来劫持那套语义。
    val goal = AppTheme.settings.dailyWordGoal
    val total = todayDone + dueCount
    // 空日（total == 0）显示空环而非满环：gold/达成态须由真实完成数驱动，
    // progress >= 1f 在 0/0 下不再可能
    val progress = if (total == 0) 0f else todayDone.toFloat() / total
    AppCard(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(88.dp),
                contentAlignment = Alignment.Center,
            ) {
                RingGauge(
                    progress = progress,
                    modifier = Modifier.fillMaxSize(),
                    strokeWidth = 9.dp,
                    color = if (progress >= 1f && total > 0) colors.goldInk else colors.accent,
                )
                // 环心读数用既有的 statValue 原样：这里此前是 `statValue.copy(fontSize = 28.sp)`，
                // 凭空造了一个刻度外的字号。statValue 本就是「粗体 + (-0.5)sp 字距」的大数字档，
                // 与 28sp 那份是同一血脉（pageTitle 是 22sp SemiBold，换了等于换一档字重），
                // 且刷题结果页 132dp 环的环心数字用的就是它 —— 两页的环心数同一档。
                Text(text = "$todayDone", style = texts.statValue)
            }
            Spacer(Modifier.width(AppTheme.space.lg))
            Column(Modifier.weight(1f)) {
                Text(text = "今日待办", style = texts.caption)
                Spacer(Modifier.height(AppTheme.space.xs))
                Text(text = "$todayDone / $total", style = texts.pageTitle)
                Spacer(Modifier.height(AppTheme.space.sm))
                // 目标那一行：没设置考试日时它是这卡上唯一一行说明文字，设了则两行都在。
                Text(
                    text = if (todayDone >= goal) {
                        "今日目标 $goal 词，已完成"
                    } else {
                        "今日目标 $goal 词，还差 ${goal - todayDone}"
                    },
                    style = texts.caption,
                )
                // 考试倒计时来自设置（未设置时整行不存在，不显示"还剩 0 天"那种废话）
                AppTheme.settings.examDate?.let { exam ->
                    val days = ChronoUnit.DAYS.between(LocalDate.now(), exam)
                    Spacer(Modifier.height(AppTheme.space.xs))
                    Text(
                        text = when {
                            days > 0 -> "距考试还有 $days 天"
                            days == 0L -> "今天考试"
                            else -> "考试已过 ${-days} 天"
                        },
                        style = texts.caption,
                        color = if (days in 0..7) colors.warningInk else colors.secondaryText,
                    )
                }
                Spacer(Modifier.height(AppTheme.space.sm))
                if (streakDays > 0) FlameBadge(days = streakDays)
            }
        }
    }
}

/**
 * 连续学习火焰徽章：进场时 0.4 → 1 的 snap spring 弹入，容器是一枚
 * [AppPill]（goldSoft 底 + goldInk 文案，T15 墨水批次：gold 作字仅 1.79:1，goldInk 压这层柔底 5.42:1）。
 *
 * 进场缩放挂在 [AppPill] 的 `modifier` 上 ⇒ 落在整枚药丸（含柔底）之外一层，与迁移前
 * `graphicsLayer → clip → background` 的层次一致。波 3 把全 app 的状态药丸收进同一份实现，
 * 圆角因此与书架/单词库/错题本同批从 `radius.md` 变 `radius.sm`。
 */
@Composable
private fun FlameBadge(days: Int) {
    val colors = AppTheme.colors
    // saveable：转屏后 born 直接恢复为 true ⇒ 目标值不再从 0.4f 起步，徽章不会凭空再弹一次
    var born by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { born = true }
    val s by animateFloatAsState(
        targetValue = if (born) 1f else 0.4f,
        animationSpec = MotionSpec.snap,
        label = "flame",
    )
    AppPill(
        container = colors.goldSoft,
        ink = colors.goldInk,
        // 🔥 是这枚徽章的**装饰**符号（旁边「连续 N 天」就是它的等价文字），留着会让 TalkBack
        // 念成「火焰 连续 5 天」（终审 I9）。图形照旧绘制，只把朗读换成去掉表情的那句：
        // clearAndSetSemantics 会清掉 AppPill 内部 Text 的语义，再由这里给一条等价的。
        label = "🔥 连续 $days 天",
        modifier = Modifier
            .graphicsLayer {
                scaleX = s
                scaleY = s
            }
            .clearAndSetSemantics {
                contentDescription = "连续学习 $days 天"
            },
    )
}

/** 入口卡片：圆形图标（soft 底色）+ 标题 + 说明，可选尾部操作按钮；点击默认 ripple */
@Composable
private fun EntryCard(
    icon: ImageVector,
    iconColor: Color,
    iconContainerColor: Color,
    title: String,
    caption: String,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    val texts = AppTheme.texts
    AppCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(iconContainerColor),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconColor,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.width(AppTheme.space.md))
            Column(Modifier.weight(1f)) {
                Text(text = title, style = texts.cardTitle)
                Spacer(Modifier.height(2.dp))
                Text(text = caption, style = texts.caption)
            }
            trailing?.invoke()
        }
    }
}
