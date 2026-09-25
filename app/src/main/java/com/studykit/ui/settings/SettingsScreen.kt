package com.studykit.ui.settings

import android.content.Context
import android.net.Uri
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.studykit.BuildConfig
import com.studykit.data.AppSettings
import com.studykit.data.GlassLevel
import com.studykit.data.ThemeMode
import com.studykit.data.memory.ReviewStrictness
import com.studykit.ui.components.AppButton
import com.studykit.ui.components.AppButtonTone
import com.studykit.ui.components.AppCard
import com.studykit.ui.components.AppPill
import com.studykit.ui.components.AppTextField
import com.studykit.ui.components.ConfirmDialog
import com.studykit.ui.components.SectionHeader
import com.studykit.ui.theme.AppTheme
import com.studykit.util.backup.StorageStats
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.delay

/**
 * 文本格的防抖写入：昵称/目标/考试日都是「逐字改」的输入框，
 * 每键一次就是一次**整表 upsert**（`SettingsRepository.save` 写全部键），
 * 打到「张三」会先把「张」落库。收 450ms 静默再写：仍然是「改完就存」，页面没有保存按钮，
 * 但也不会把中间态写进库（万一这一屏被划走，最后一格已经存过了）。
 */
private const val DebounceMs = 450L

/** 一次性提示在屏幕上的停留时长，到点调 `consumeMessage()` */
private const val MessageTtlMs = 5_000L

private val ThemeChoices = listOf(
    ThemeMode.SYSTEM to "跟随系统",
    ThemeMode.LIGHT to "浅色",
    ThemeMode.DARK to "深色",
)

private val GlassChoices = listOf(
    GlassLevel.OFF to "关闭",
    GlassLevel.SOFT to "柔和",
    GlassLevel.STRONG to "浓郁",
)

/** 复习严格度。四格而不是滑条：这一项的语义是分档的，"自动"和三个固定档不在同一根轴上 */
private val StrictnessChoices = listOf(
    ReviewStrictness.AUTO to "自动",
    ReviewStrictness.RELAXED to "宽松",
    ReviewStrictness.STANDARD to "标准",
    ReviewStrictness.STRICT to "严格",
)

/** 「一天」开始的候选小时。5 格短标签，一行放得下 */
private val BoundaryChoices = listOf(
    0 to "00 点",
    2 to "02 点",
    3 to "03 点",
    4 to "04 点",
    5 to "05 点",
)

/** 打卡时段预设。索引与 `SettingsViewModel.setCheckInWindow` 一一对应 */
private val WindowChoices = listOf(0 to "不限", 1 to "08–22", 2 to "07–23", 3 to "09–24")

/** 由当前设置反推选中的预设档（手改备份出现自定义窗口时四档都不选中，可接受） */
private fun currentWindowPreset(s: AppSettings): Int = when {
    !s.restrictCheckIn -> 0
    s.restrictStartMin == 8 * 60 && s.restrictEndMin == 22 * 60 -> 1
    s.restrictStartMin == 7 * 60 && s.restrictEndMin == 23 * 60 -> 2
    s.restrictStartMin == 9 * 60 && s.restrictEndMin == 24 * 60 -> 3
    else -> -1
}

/** 提醒周期的常用档。WorkManager 的周期任务按小时粗粒度，1..72 的自由值靠备份恢复才会出现 */
private val ReminderPresets = listOf(2, 4, 6, 12, 24)

/**
 * 设置页（v2.2 T7）：三段同页滚动 —— 外观与动效 / 档案与目标 / 数据管理。
 *
 * 路由与顶栏齿轮入口在 `AppNav` / `StudyHomeScreen` 那一侧接（本页只暴露
 * `SettingsScreen(viewModel, onBack)` 这一个入口），玻璃材质本身在 `ui/material/Glass.kt`。
 *
 * ## 这个页面守的几条纪律
 *
 * - **根 Column 只有 `fillMaxSize + verticalScroll + padding(pageH)`，一处 insets 都不碰。**
 *   `AppNav` 根 Scaffold 的 `innerPadding` 是全仓唯一的键盘/系统栏消费者，这里再补一枚
 *   `.imePadding()` 就是两份键盘高度 —— 表单页会直接塌成一条（真机踩过，波 4）。
 * - 颜色与文字只取 `AppTheme.colors/texts`；间距圆角只取 `AppTheme.space/radius/size/elevation`。
 * - 品牌色（accent/success/gold/warning）只做填充、描边、色点；**文字与图标一律同族 `*Ink`**。
 *   磁贴选中态是实底容器 → `accentInk` 底 + `onAccent` 字（与 [AppButton] 同一套）。
 * - 每格改动立即写库，全页没有「保存」按钮；文本格按 [DebounceMs] 静默后写（见其注释）。
 * - 考试日不走 `@ExperimentalMaterial3Api` 的 DatePicker：填 `yyyy-MM-dd` + `LocalDate.parse` 校验，
 *   非法时在本格下面给一行 `warningInk` 的原因，旁边一枚「清除」把 `examEpochDay` 归 0。
 * - 导出/恢复走 SAF（`CreateDocument("application/zip")` / `OpenDocument`），
 *   恢复前必经过一次二次确认；`busy` 为真时两个入口都禁用。
 */
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()

    // ── SAF：文件名带日期，用户自己挑落地位置 ──────────────────────
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { target -> if (target != null) viewModel.exportTo(target) }
    // 选完不直接恢复：先拿这个 state 存住 uri，让确认对话框过一遍
    var pendingRestore by rememberSaveable { mutableStateOf<Uri?>(null) }
    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { source ->
        if (source != null) pendingRestore = source
    }

    // ── 昵称 ────────────────────────────────────────────────────
    var nicknameText by rememberSaveable { mutableStateOf("") }
    var nicknameEdited by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(settings.nickname, nicknameEdited) {
        // 用户没动过就跟着库里的值走（冷启动第一帧读到的是默认值，真值随后到）
        if (!nicknameEdited) nicknameText = settings.nickname
    }
    LaunchedEffect(nicknameText, nicknameEdited) {
        if (!nicknameEdited) return@LaunchedEffect
        delay(DebounceMs)
        if (nicknameText.trim() != settings.nickname) viewModel.setNickname(nicknameText)
    }

    // ── 每日目标词数 ─────────────────────────────────────────────
    var goalText by rememberSaveable { mutableStateOf("") }
    var goalEdited by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(settings.dailyWordGoal, goalEdited) {
        if (!goalEdited) goalText = settings.dailyWordGoal.toString()
    }
    val goalValue = goalText.toIntOrNull()
    val goalError = when {
        !goalEdited || goalText.isBlank() -> null
        goalValue == null -> "只能是整数"
        goalValue !in AppSettings.WORD_GOAL_RANGE ->
            "收在 ${AppSettings.WORD_GOAL_RANGE.first}–${AppSettings.WORD_GOAL_RANGE.last} 之间"
        else -> null
    }
    LaunchedEffect(goalText, goalEdited) {
        if (!goalEdited) return@LaunchedEffect
        delay(DebounceMs)
        val parsed = goalText.toIntOrNull()
        if (parsed != null && parsed in AppSettings.WORD_GOAL_RANGE && parsed != settings.dailyWordGoal) {
            viewModel.setDailyWordGoal(parsed)
        }
    }

    // ── 考试日 ───────────────────────────────────────────────────
    var examText by rememberSaveable { mutableStateOf("") }
    var examEdited by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(settings.examEpochDay, examEdited) {
        if (!examEdited) examText = settings.examDate?.toString() ?: ""
    }
    val examParsed = examText.trim().takeIf { it.isNotEmpty() }?.let { raw ->
        runCatching { LocalDate.parse(raw) }.getOrNull()
    }
    val examError = when {
        !examEdited || examText.isBlank() -> null
        examParsed == null -> "按 2026-12-19 这样的 yyyy-MM-dd 填"
        examParsed.toEpochDay() !in AppSettings.EXAM_DAY_RANGE ->
            "日期要在 ${LocalDate.ofEpochDay(AppSettings.EXAM_DAY_RANGE.first)} 到 " +
                LocalDate.ofEpochDay(AppSettings.EXAM_DAY_RANGE.last) + " 之间"
        else -> null
    }
    LaunchedEffect(examText, examEdited) {
        if (!examEdited) return@LaunchedEffect
        delay(DebounceMs)
        if (examParsed != null && examParsed != settings.examDate) viewModel.setExamDate(examParsed)
    }
    val daysLeft = settings.examDate?.let { ChronoUnit.DAYS.between(LocalDate.now(), it) }

    // 页根滚动状态提到外面，是为了下面那次「有提示就滚回页首」（见 LaunchedEffect(message)）
    val scrollState = rememberScrollState()

    LaunchedEffect(message) {
        if (message != null) {
            // 提示条画在页首，而导出/恢复这两格在页尾：不滚回顶部的话，用户点了按钮会以为没反应
            scrollState.animateScrollTo(0)
            delay(MessageTtlMs)
            viewModel.consumeMessage()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = AppTheme.space.pageH),
    ) {
        Spacer(modifier = Modifier.height(AppTheme.space.sm))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = colors.accentInk,
                )
            }
            Spacer(modifier = Modifier.width(AppTheme.space.xs))
            Text(text = "设置", style = texts.pageTitle, modifier = Modifier.weight(1f))
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = colors.accent,
                    strokeWidth = 2.dp,
                )
            }
        }

        if (message != null) {
            Spacer(modifier = Modifier.height(AppTheme.space.sm))
            MessageBanner(text = message.orEmpty(), onDismiss = viewModel::consumeMessage)
        }

        // ── 一、外观与动效 ────────────────────────────────────────
        SectionHeader(title = "外观与动效", modifier = Modifier.padding(top = AppTheme.space.lg))
        Spacer(modifier = Modifier.height(AppTheme.space.md))
        AppCard(modifier = Modifier.fillMaxWidth()) {
            SettingBlock(
                title = "主题",
                hint = "整个 app 的配色当场换；选「跟随系统」时才跟手机的深色模式走。",
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(AppTheme.space.sm),
                    modifier = Modifier.selectableGroup(),
                ) {
                    ThemeChoices.forEach { (mode, label) ->
                        ChoiceTile(
                            label = label,
                            selected = settings.themeMode == mode,
                            onClick = { viewModel.setThemeMode(mode) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            HorizontalDivider(color = colors.divider)
            SettingBlock(
                title = "玻璃材质",
                hint = "只影响底栏、打卡弹层这类浮层的材质，卡面仍是暖纸实底；" +
                    "「柔和 → 浓郁」改的是高光的浓度，不是模糊半径。",
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(AppTheme.space.sm),
                    modifier = Modifier.selectableGroup(),
                ) {
                    GlassChoices.forEach { (level, label) ->
                        ChoiceTile(
                            label = label,
                            selected = settings.glass == level,
                            onClick = { viewModel.setGlass(level) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            HorizontalDivider(color = colors.divider)
            SettingBlock(
                title = "减弱动效",
                hint = "减弱动效：关闭彩带、错峰入场与按压缩放，不影响功能。",
                trailing = {
                    Switch(
                        checked = settings.reduceMotion,
                        onCheckedChange = { viewModel.setReduceMotion(it) },
                        colors = SwitchDefaults.colors(
                            // 滑块恒为卡面色：选中压在 accentInk 上、未选中压在 divider 轨道上，
                            // 两个主题都靠明度差分得出来（品牌色在这里是填充，不充当文字）
                            checkedTrackColor = colors.accentInk,
                            checkedBorderColor = colors.accentInk,
                            checkedThumbColor = colors.card,
                            uncheckedTrackColor = colors.divider,
                            uncheckedBorderColor = colors.divider,
                            uncheckedThumbColor = colors.card,
                        ),
                    )
                },
            )
        }

        // ── 二、档案与目标 ────────────────────────────────────────
        SectionHeader(title = "档案与目标", modifier = Modifier.padding(top = AppTheme.space.lg))
        Spacer(modifier = Modifier.height(AppTheme.space.md))
        AppCard(modifier = Modifier.fillMaxWidth()) {
            SettingBlock(
                title = "昵称",
                hint = "习惯周报分享文本的抬头用它；不填就走默认那句。",
            ) {
                AppTextField(
                    value = nicknameText,
                    onValueChange = {
                        nicknameText = it.take(AppSettings.NICKNAME_MAX)
                        nicknameEdited = true
                    },
                    placeholder = "例如：小考",
                )
                Spacer(modifier = Modifier.height(AppTheme.space.xs))
                Text(
                    text = "${nicknameText.length}/${AppSettings.NICKNAME_MAX}",
                    style = texts.caption,
                )
            }
            HorizontalDivider(color = colors.divider)
            SettingBlock(
                title = "每日目标词数",
                hint = "学习首页那圈今日进度按这个数算：今天新学满 N 个词即达标。",
                error = goalError,
            ) {
                AppTextField(
                    value = goalText,
                    onValueChange = { raw ->
                        // 只收数字并截到三位（上限 500），省掉「-1」「1e3」这类解析歧义
                        goalText = raw.filter { it.isDigit() }.take(3)
                        goalEdited = true
                    },
                    placeholder = "20",
                    keyboardType = KeyboardType.Number,
                    isError = goalError != null,
                )
            }
            HorizontalDivider(color = colors.divider)
            SettingBlock(
                title = "复习提醒间隔（小时）",
                hint = buildString {
                    append("约每 N 小时提醒一次复习；WorkManager 的周期任务只保证最小间隔，不承诺准点。")
                    if (settings.reminderEveryHours !in ReminderPresets) {
                        append("当前为每 ${settings.reminderEveryHours} 小时，仍按这个周期生效。")
                    }
                },
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(AppTheme.space.sm),
                    modifier = Modifier.selectableGroup(),
                ) {
                    ReminderPresets.forEach { hours ->
                        ChoiceTile(
                            label = "$hours",
                            selected = settings.reminderEveryHours == hours,
                            onClick = { viewModel.setReminderHours(hours) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            HorizontalDivider(color = colors.divider)
            SettingBlock(
                title = "考试日",
                hint = buildString {
                    append("学习首页的倒计时行按这一天算剩余天数；留空即不显示。")
                    when {
                        daysLeft == null -> Unit
                        daysLeft > 0 -> append("还有 $daysLeft 天。")
                        daysLeft == 0L -> append("就是今天。")
                        else -> append("已经过了 ${-daysLeft} 天，改日期或清除。")
                    }
                },
                error = examError,
            ) {
                Row(verticalAlignment = Alignment.Bottom) {
                    AppTextField(
                        value = examText,
                        onValueChange = {
                            examText = it
                            examEdited = true
                        },
                        placeholder = "2026-12-19",
                        isError = examError != null,
                        modifier = Modifier.weight(1f),
                    )
                    if (settings.examDate != null) {
                        TextButton(
                            onClick = {
                                examText = ""
                                examEdited = true
                                viewModel.setExamDate(null)
                            },
                            // 文字按钮本体约 20dp 高，够不上 48dp（终审 I9）
                            modifier = Modifier.minimumInteractiveComponentSize(),
                        ) {
                            Text(text = "清除", color = colors.warningInk)
                        }
                    }
                }
            }
            HorizontalDivider(color = colors.divider)
            SettingBlock(
                title = "复习严格度",
                hint = buildString {
                    append("决定「预测还记得多少」时就把词送回来 —— 越严越早送回、复习次数越多；")
                    append("越松则让它多忘一会儿，那一次提取的加固效果反而更强。")
                    append(
                        when {
                            settings.reviewStrictness != ReviewStrictness.AUTO -> "当前为固定档，不随考试日变化。"
                            settings.examDate != null -> "当前跟随考试日：考得越近越严。"
                            else -> "当前为自动，但未填考试日，按 90% 执行。"
                        },
                    )
                },
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(AppTheme.space.sm),
                    modifier = Modifier.selectableGroup(),
                ) {
                    StrictnessChoices.forEach { (level, label) ->
                        ChoiceTile(
                            label = label,
                            selected = settings.reviewStrictness == level,
                            onClick = { viewModel.setReviewStrictness(level) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            HorizontalDivider(color = colors.divider)
            SettingBlock(
                title = "打卡规则",
                hint = "「一天」从几点开始：选 03 点，凌晨两点的打卡就记进前一天，熬夜不再断签。" +
                    "漏一天并不会毁掉习惯，所以补打卡也放开了（Lally 2010）。",
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(AppTheme.space.sm),
                    modifier = Modifier.selectableGroup(),
                ) {
                    BoundaryChoices.forEach { (hour, label) ->
                        ChoiceTile(
                            label = label,
                            selected = settings.dayBoundaryHour == hour,
                            onClick = { viewModel.setDayBoundaryHour(hour) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            HorizontalDivider(color = colors.divider)
            SettingBlock(
                title = "允许补打卡",
                hint = "过去 7 天内漏掉的日历格可以点进去补；补的会单独标识，不与当天打卡混算。",
                trailing = {
                    Switch(
                        checked = settings.makeupAllowed,
                        onCheckedChange = { viewModel.setMakeupAllowed(it) },
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = colors.accentInk,
                            checkedBorderColor = colors.accentInk,
                            checkedThumbColor = colors.card,
                            uncheckedTrackColor = colors.divider,
                            uncheckedBorderColor = colors.divider,
                            uncheckedThumbColor = colors.card,
                        ),
                    )
                },
            )
            HorizontalDivider(color = colors.divider)
            SettingBlock(
                title = "限制打卡时段",
                hint = "窗口外点「确认打卡」会被拦下并说明原因；补打卡不受窗口限制。",
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(AppTheme.space.sm),
                    modifier = Modifier.selectableGroup(),
                ) {
                    WindowChoices.forEach { (index, label) ->
                        ChoiceTile(
                            label = label,
                            selected = currentWindowPreset(settings) == index,
                            onClick = { viewModel.setCheckInWindow(index) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }

        // ── 三、数据管理 ──────────────────────────────────────────
        SectionHeader(title = "数据管理", modifier = Modifier.padding(top = AppTheme.space.lg))
        Spacer(modifier = Modifier.height(AppTheme.space.md))
        AppCard(modifier = Modifier.fillMaxWidth()) {
            SettingBlock(
                title = "本机占用",
                hint = "这些数字只统计本机：删掉 app 或恢复一份备份，它们就会变。",
                trailing = {
                    TextButton(
                        onClick = { viewModel.refreshStats() },
                        modifier = Modifier.minimumInteractiveComponentSize(),
                    ) {
                        Text(text = "重新统计", color = colors.accentInk)
                    }
                },
            ) {
                StatsBody(stats = stats, context = context)
            }
            HorizontalDivider(color = colors.divider)
            SettingBlock(
                title = "导出备份",
                hint = "一个 zip：studykit.db + 错题图片 + 清单。系统的自动备份目前把数据库整块排除了，" +
                    "也就是说这份 zip 是你的词库与错题唯一的退路。",
            ) {
                AppButton(
                    text = if (busy) "正在读写备份…" else "选择一个位置导出 zip",
                    enabled = !busy,
                    onClick = { exportLauncher.launch("studykit-backup-${LocalDate.now()}.zip") },
                )
            }
            HorizontalDivider(color = colors.divider)
            SettingBlock(
                title = "从备份恢复",
                hint = "会覆盖当前全部学习数据与设置，并在成功后重启应用；" +
                    "备份文件与本机的表结构不一致时会拒绝并留下原因，不会恢复一半。",
            ) {
                AppButton(
                    text = "选一个备份文件恢复",
                    secondary = true,
                    tone = AppButtonTone.Warning,
                    enabled = !busy,
                    onClick = { openLauncher.launch(arrayOf("application/zip")) },
                )
            }
        }

        Spacer(modifier = Modifier.height(AppTheme.space.md))
        AppCard(modifier = Modifier.fillMaxWidth()) {
            SettingBlock(
                title = "恢复默认设置",
                hint = "只清设置这一张表：主题、玻璃、减弱动效、昵称、目标与考试日全部回到默认，" +
                    "词库、错题、打卡一条不动。",
            ) {
                var showResetDialog by rememberSaveable { mutableStateOf(false) }
                AppButton(
                    text = "恢复默认设置",
                    secondary = true,
                    enabled = !busy,
                    onClick = { showResetDialog = true },
                )
                if (showResetDialog) {
                    ConfirmDialog(
                        title = "恢复默认设置？",
                        body = "设置这一张表会被清空（下次进来就是默认的外观与目标）。学习数据不受影响。",
                        confirmLabel = "清空设置",
                        danger = false,
                        onConfirm = {
                            showResetDialog = false
                            viewModel.resetSettings()
                        },
                        onDismiss = { showResetDialog = false },
                    )
                }
            }
            HorizontalDivider(color = colors.divider)
            SettingBlock(
                title = "清除学习数据",
                hint = "删除单词、词库、错题（含图片）、习惯与打卡、自我契约、书目与摘录、题库与刷题记录；" +
                    "本页的设置一条不动。不可撤销，请先导出备份。",
            ) {
                // 真机踩过：这一格原来是**单击直接清**的，走查时一次落在标题下方的误触就把
                // 1177 个词清空了。它是全页唯一不可撤销的动作，必须有二次确认，
                // 而且确认框的措辞要把"清掉哪些、留下哪些"说全（下面这段与 hint 同口径）。
                var showClearDialog by rememberSaveable { mutableStateOf(false) }
                AppButton(
                    text = "清除学习数据",
                    secondary = true,
                    tone = AppButtonTone.Warning,
                    enabled = !busy,
                    onClick = { showClearDialog = true },
                )
                if (showClearDialog) {
                    ConfirmDialog(
                        title = "清除全部学习数据？",
                        body = "单词、词库、错题与它们的图片、习惯与打卡、自我契约、书目与摘录、" +
                            "题库与刷题记录" +
                            "会一起删掉，删了就找不回来；本页的设置一条不动。" +
                            "先点上面的「选择一个位置导出 zip」存一份，再回来清。",
                        confirmLabel = "清除学习数据",
                        danger = true,
                        onConfirm = {
                            showClearDialog = false
                            viewModel.clearBusinessData()
                        },
                        onDismiss = { showClearDialog = false },
                    )
                }
            }
            HorizontalDivider(color = colors.divider)
            SettingBlock(
                title = "诊断",
                hint = "词库下载的失败原因只记录上一条，供排查用；重新导入成功不会自动清掉它。",
            ) {
                val failed = settings.lastDictFailure.isNotBlank()
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AppTheme.space.sm),
                ) {
                    AppPill(
                        container = if (failed) colors.warningSoft else colors.successSoft,
                        ink = if (failed) colors.warningInk else colors.successInk,
                        label = if (failed) "有失败记录" else "暂无失败记录",
                        leadingDot = if (failed) colors.warning else colors.success,
                    )
                    Text(text = "v${BuildConfig.VERSION_NAME}", style = texts.caption)
                }
                Spacer(modifier = Modifier.height(AppTheme.space.sm))
                Text(
                    text = settings.lastDictFailure.ifBlank { "上次词库下载没有失败记录。" },
                    style = texts.caption.copy(color = if (failed) colors.warningInk else colors.secondaryText),
                )
            }
        }

        Spacer(modifier = Modifier.height(AppTheme.space.xl))
    }

    // 恢复的二次确认：这是全页唯一会吃掉既有数据的路径，说狠话也要说清楚
    pendingRestore?.let { source ->
        ConfirmDialog(
            title = "从备份恢复？",
            body = "这会覆盖当前全部学习数据与设置，并在恢复完成后重启应用。" +
                "现在的词库、错题、打卡与书目都不会留下，只有更早的备份能救回来。",
            confirmLabel = "覆盖并重启",
            danger = true,
            onConfirm = {
                pendingRestore = null
                viewModel.restoreFrom(source)
            },
            onDismiss = { pendingRestore = null },
        )
    }
}

/** 一次性提示条：`accentSoft` 底 + `accentInk` 字（提示不分成败，故不借 success/warning 那两族）。 */
@Composable
private fun MessageBanner(text: String, onDismiss: () -> Unit) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    Box(
        modifier = Modifier
            .fillMaxWidth()
            // 链首撑点击槽位，clip/background 在它下游 ⇒ 底色与圆角一分不变（终审 I9）
            .minimumInteractiveComponentSize()
            .clip(RoundedCornerShape(AppTheme.radius.sm))
            .background(colors.accentSoft)
            .clickable(onClick = onDismiss)
            .padding(horizontal = AppTheme.space.md, vertical = AppTheme.space.sm),
    ) {
        Text(text = text, style = texts.caption.copy(color = colors.accentInk))
    }
}

/**
 * 一格设置：标题 + 控件（可选）+ 下面一行小字，说清这条**影响什么**。
 *
 * [hint] 恒为 `texts.caption` + `colors.secondaryText`（本仓的次要文字档），
 * [error] 走同族 `warningInk` —— 校验失败时它顶替的是提示那一行，不额外把布局撑高。
 * [trailing] 挂在标题行右端（开关、次要文字按钮）。
 */
@Composable
private fun SettingBlock(
    title: String,
    hint: String,
    modifier: Modifier = Modifier,
    error: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: (@Composable () -> Unit)? = null,
) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    Column(modifier = modifier.fillMaxWidth().padding(vertical = AppTheme.space.md)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = title, style = texts.cardTitle, modifier = Modifier.weight(1f))
            trailing?.invoke()
        }
        if (content != null) {
            Spacer(modifier = Modifier.height(AppTheme.space.sm))
            content()
        }
        Spacer(modifier = Modifier.height(AppTheme.space.sm))
        Text(text = hint, style = texts.caption.copy(color = colors.secondaryText))
        if (error != null) {
            Spacer(modifier = Modifier.height(AppTheme.space.xs))
            Text(text = error, style = texts.caption.copy(color = colors.warningInk))
        }
    }
}

/**
 * 磁贴式单选项（同一份写法见 `HabitCreateScreen` 的目标天数那一排）：
 * 选中 = 实底强调色容器（`accentInk` 底 + `onAccent` 字），未选中 = 卡面 + 分割线描边。
 */
@Composable
private fun ChoiceTile(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    Box(
        modifier = modifier
            // 格体只有 ~36dp 高，够不上 48dp 最小可点目标（终审 I9）：挂在链首，
            // 只撑不可见的点击槽位，clip/background/border 都在它下游
            .minimumInteractiveComponentSize()
            .clip(RoundedCornerShape(AppTheme.radius.md))
            .background(if (selected) colors.accentInk else colors.card)
            .border(
                width = 1.dp,
                color = if (selected) colors.accentInk else colors.divider,
                shape = RoundedCornerShape(AppTheme.radius.md),
            )
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .padding(vertical = AppTheme.space.sm),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = texts.aux.copy(
                color = if (selected) colors.onAccent else colors.primaryText,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            ),
            maxLines = 1,
        )
    }
}

/** 占用统计正文：三格一排，两排计数 + 一行体积；`stats == null` 时是「还在算」而不是 0。 */
@Composable
private fun StatsBody(stats: StorageStats?, context: Context) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    if (stats == null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                color = colors.accent,
                strokeWidth = 2.dp,
            )
            Spacer(modifier = Modifier.width(AppTheme.space.sm))
            Text(text = "正在统计本机数据…", style = texts.caption)
        }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(AppTheme.space.md)) {
        Row(horizontalArrangement = Arrangement.spacedBy(AppTheme.space.md)) {
            StatCell(value = "${stats.words}", label = "单词", modifier = Modifier.weight(1f))
            StatCell(value = "${stats.questions}", label = "题目", modifier = Modifier.weight(1f))
            StatCell(value = "${stats.mistakes}", label = "错题", modifier = Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(AppTheme.space.md)) {
            StatCell(value = "${stats.habits}", label = "打卡记录", modifier = Modifier.weight(1f))
            StatCell(value = "${stats.books}", label = "书目", modifier = Modifier.weight(1f))
            StatCell(
                value = Formatter.formatShortFileSize(context, stats.imageBytes),
                label = "错题图片",
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            text = "数据库文件 ${Formatter.formatShortFileSize(context, stats.dbBytes)}",
            style = texts.caption.copy(color = colors.secondaryText),
        )
    }
}

/** 统计格：数字用 `cardTitle`（不是 `statValue` 的 34sp —— 这里三格并排，34sp 会把行高撑乱）。 */
@Composable
private fun StatCell(value: String, label: String, modifier: Modifier = Modifier) {
    val texts = AppTheme.texts
    Column(modifier = modifier) {
        Text(text = value, style = texts.cardTitle, maxLines = 1)
        Spacer(modifier = Modifier.height(AppTheme.space.xs))
        Text(text = label, style = texts.caption)
    }
}
