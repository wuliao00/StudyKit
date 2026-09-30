package com.studykit.ui.habit

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.studykit.StudyKitApp
import com.studykit.data.entity.CheckIn
import com.studykit.data.entity.Habit
import com.studykit.tips.StudyTips
import com.studykit.tips.Tip
import com.studykit.tips.TipEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** 补打卡窗口：仅允许补录过去 7 天内未打卡的日期 */
const val MAKEUP_WINDOW_DAYS = 7L

/** 数量数值格式化：整数不带小数点，小数保留 1 位 */
fun formatAmount(value: Double): String =
    if (value % 1.0 == 0.0) value.toLong().toString() else String.format(java.util.Locale.US, "%.1f", value)

/** 列表页单项展示模型 */
data class HabitItemUi(
    val habit: Habit,
    val checkedDates: Set<LocalDate>,
    val totalCheckDays: Int,
    val totalAmount: Double,
    val todayAmount: Double,
    val streak: Int,
    val checkedInToday: Boolean,
    val latestNote: String,
    /** 卡片展示数据：执行意图句 + 阶段进度 + 周达标率 + 含保护的连续 */
    val display: HabitCardDisplay = HabitCardDisplay(),
) {
    /** targetCount > 0 即数量型习惯 */
    val isCountType: Boolean get() = habit.targetCount > 0

    /** 目标进度：数量型 = 累计数量/目标数量；天数型 = 打卡天数/目标天数 */
    val progress: Float
        get() = when {
            isCountType -> (totalAmount / habit.targetCount).toFloat().coerceIn(0f, 1f)
            habit.targetDays > 0 -> (totalCheckDays.toFloat() / habit.targetDays).coerceIn(0f, 1f)
            else -> 0f
        }

    /** 进度是否已达成（进度环满 100%） */
    val achieved: Boolean get() = progress >= 1f

    /** 距目标日还剩几天（startDate + targetDays 推算）；负数表示已超额 */
    val remainingDays: Long
        get() {
            val start = Instant.ofEpochMilli(habit.startDate)
                .atZone(ZoneId.systemDefault()).toLocalDate()
            val targetDate = start.plusDays(habit.targetDays.toLong())
            return ChronoUnit.DAYS.between(LocalDate.now(), targetDate)
        }

    /** 进度文案：数量型「300/500 ml」；天数型「5/66 天」 */
    val progressText: String
        get() = if (isCountType) {
            "${formatAmount(totalAmount)}/${formatAmount(habit.targetCount)} ${habit.unit}".trim()
        } else {
            "$totalCheckDays/${habit.targetDays} 天"
        }
}

data class HabitListUiState(
    val items: List<HabitItemUi> = emptyList(),
    val checkedTodayCount: Int = 0,
    val maxStreak: Int = 0,
    /** 列表顶部一次性挂出的科学提示（缺席补偿 / 里程碑），没有则为 null */
    val tip: Tip? = null,
)

/** 日历页展示模型 */
data class HabitDetailUi(
    val habit: Habit,
    val checkedDates: Set<LocalDate>,
    val totalCheckDays: Int,
    val totalAmount: Double,
    val streak: Int,
    /** 连续里靠保护卡跨过的缺口天数 */
    val streakCardsUsed: Int = 0,
    /** 本月剩余保护卡 */
    val cardsLeft: Int = 0,
    /** 本周达标率文案 */
    val weeklyText: String = "",
    /** 本周是否达标 */
    val weeklyMet: Boolean = false,
) {
    val isCountType: Boolean get() = habit.targetCount > 0
}

/**
 * 连续打卡天数（纯函数）：从今天（若今天未打卡则从昨天）向前逐日回溯，
 * 统计不间断打卡的天数。
 */
internal fun habitStreak(dates: Set<LocalDate>, today: LocalDate = LocalDate.now()): Int {
    if (dates.isEmpty()) return 0
    var cursor = if (today in dates) today else today.minusDays(1)
    var streak = 0
    while (cursor in dates) {
        streak += 1
        cursor = cursor.minusDays(1)
    }
    return streak
}

/** 该日期是否可补打卡：过去 7 天内（不含今天与未来） */
fun canMakeUp(date: LocalDate, today: LocalDate = LocalDate.now()): Boolean =
    date.isBefore(today) && !date.isBefore(today.minusDays(MAKEUP_WINDOW_DAYS))

// ── 以下为习惯科学化改造新增的纯函数：不依赖 Android，可在 JVM 单测直接调用 ──────

/** 列表卡片展示数据（组装自 HabitScience） */
data class HabitCardDisplay(
    val intentionText: String = "",
    val dayCounterText: String = "",
    val phaseHint: String = "",
    val weeklyText: String = "",
    val weeklyMet: Boolean = false,
    val streakText: String = "",
    val cardsLeft: Int = 0,
)

/** 把执行意图、第 N 天/目标、阶段文案、周达标率、含保护的连续拼成一张卡片的数据 */
internal fun buildHabitCardDisplay(
    habit: Habit,
    dates: Set<LocalDate>,
    today: LocalDate,
    daysElapsed: Int,
    streak: HabitScience.StreakResult,
): HabitCardDisplay {
    val days = daysElapsed.coerceAtLeast(0)
    val intention = HabitScience.intention(habit.cueTime, habit.cuePlace, habit.name)
    val rate = HabitScience.weeklyRate(dates, today)
    val met = rate.metTarget(habit.weeklyTargetDays)
    val target = if (habit.targetDays > 0) habit.targetDays else HabitScience.DEFAULT_TARGET_DAYS
    return HabitCardDisplay(
        intentionText = intention.text,
        dayCounterText = "第 $days 天 / $target 天",
        phaseHint = HabitScience.phaseHint(days),
        weeklyText = "本周 ${rate.checkedDays}/${rate.windowDays} · " + if (met) "已达标" else "还没达标",
        weeklyMet = met,
        streakText = buildString {
            append("连续 ${streak.streak} 天")
            if (streak.cardsUsed > 0) append("（含保护 ${streak.cardsUsed}）")
        },
        cardsLeft = habitQuota(habit, today).left,
    )
}

/** 把习惯行上的三列拼成本月额度（跨月自动回血） */
internal fun habitQuota(habit: Habit, today: LocalDate): HabitScience.Quota =
    HabitScience.refreshQuota(
        HabitScience.Quota(habit.protectionPeriod, habit.protectionCards, habit.protectionUsed),
        today,
    )

/** 补卡决策：允许时给出写入参数（isMakeup、消耗后的已用卡数），否则给出原因 */
sealed class MakeupDecision {
    data class Allowed(val isMakeup: Boolean, val protectionUsedAfter: Int, val cardsLeftAfter: Int) : MakeupDecision()
    data class Blocked(val reason: String) : MakeupDecision()
}

/** 过去日期补卡需消耗一张保护卡；卡用完或超出窗口则拒绝并说明原因（措辞不带威胁） */
internal fun decideMakeup(
    habit: Habit,
    date: LocalDate,
    today: LocalDate,
    quota: HabitScience.Quota = habitQuota(habit, today),
): MakeupDecision {
    if (!canMakeUp(date, today)) {
        return MakeupDecision.Blocked("只能补录过去 ${MAKEUP_WINDOW_DAYS.toInt()} 天内的记录，更早的日期就先放下。")
    }
    if (quota.left <= 0) {
        return MakeupDecision.Blocked("这个月的保护卡已经用完，漏一天不会打断习惯，明天接着来就好。")
    }
    val spent = HabitScience.spendCard(quota)
    return MakeupDecision.Allowed(
        isMakeup = true,
        protectionUsedAfter = spent.used,
        cardsLeftAfter = spent.left,
    )
}

/**
 * 列表页提示事件：连续刚好到 21 天 → 破除神话提示；
 * 昨天缺席且本月仍有保护卡 → 缺席安抚提示；其余不提示。
 */
internal fun habitTipEvent(
    dates: Set<LocalDate>,
    today: LocalDate,
    streak: Int,
    cardsLeft: Int,
): TipEvent? {
    if (streak == StudyTips.STREAK_MYTH_DAY) return TipEvent.StreakReached(streak)
    val yesterdayChecked = today.minusDays(1) in dates
    if (!yesterdayChecked && cardsLeft > 0) return TipEvent.GapDay
    return null
}

/** 从一组卡片里挑一条最该展示的提示事件：里程碑优先于缺席安抚 */
internal fun pickHabitTip(items: List<HabitItemUi>, today: LocalDate): TipEvent? {
    var gapEvent: TipEvent? = null
    items.forEach { item ->
        when (val event = habitTipEvent(item.checkedDates, today, item.streak, item.display.cardsLeft)) {
            is TipEvent.StreakReached -> return event
            is TipEvent.GapDay -> gapEvent = gapEvent ?: event
            else -> Unit
        }
    }
    return gapEvent
}

/** 习惯日历：某一周（按展示行的日期集合）是否达到 weeklyTarget 天 */
internal fun isCalendarWeekMet(
    weekDays: Collection<LocalDate>,
    checkedDates: Set<LocalDate>,
    weeklyTarget: Int,
): Boolean = weekDays.count { it in checkedDates } >= weeklyTarget

/** 创建页执行意图校验：何时或何地缺失时返回给用户的提示，完整则返回 null */
internal fun intentionValidationError(cueTime: String, cuePlace: String, action: String): String? {
    val complete = HabitScience.intention(cueTime, cuePlace, action).isComplete
    return if (complete) null else "补上「何时」和「何地」，这个计划才更容易被执行。"
}

/** 从习惯开始日期推算「第几天」（含起始当天），用于 66 天阶段文案 */
internal fun daysElapsed(startDateMillis: Long, today: LocalDate): Int {
    val start = Instant.ofEpochMilli(startDateMillis).atZone(ZoneId.systemDefault()).toLocalDate()
    return (ChronoUnit.DAYS.between(start, today) + 1).toInt().coerceAtLeast(0)
}

/** 把演示数据的旧图标键映射为 emoji；已是 emoji 则原样返回 */
fun habitIconEmoji(icon: String): String = when (icon) {
    "book" -> "📖"
    "run"  -> "🏃"
    "read" -> "📚"
    else   -> icon.ifBlank { "🎯" }
}

private fun List<CheckIn>.toLocalDates(): Set<LocalDate> =
    mapNotNull { runCatching { LocalDate.parse(it.date) }.getOrNull() }.toSet()

class HabitViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = (application as StudyKitApp).container.habitRepository

    // ── 列表页状态：习惯流 × 各习惯打卡流，自动响应打卡写入 ─────────────
    val uiState: StateFlow<HabitListUiState> = repository.observeAll()
        .flatMapLatest { habits ->
            if (habits.isEmpty()) {
                emptyFlow<Pair<List<Habit>, Map<Long, List<CheckIn>>>>()
            } else {
                combine(
                    habits.map { habit ->
                        repository.observeCheckIns(habit.id).map { habit.id to it }
                    },
                ) { pairs -> habits to pairs.associate { it } }
            }
        }
        .map { (habits, checkInsById) ->
            val today = LocalDate.now()
            val items = habits.map { habit ->
                val checkIns = checkInsById[habit.id].orEmpty()
                val dates = checkIns.toLocalDates()
                val streakResult = HabitScience.streakWithProtection(dates, today, habitQuota(habit, today).left)
                HabitItemUi(
                    habit          = habit,
                    checkedDates   = dates,
                    totalCheckDays = dates.size,
                    totalAmount    = checkIns.sumOf { it.amount },
                    todayAmount    = checkIns
                        .filter { runCatching { LocalDate.parse(it.date) }.getOrNull() == today }
                        .sumOf { it.amount },
                    streak         = streakResult.streak,
                    checkedInToday = today in dates,
                    latestNote     = checkIns.maxByOrNull { it.date }
                        ?.takeIf { it.note.isNotBlank() }?.note.orEmpty(),
                    display        = buildHabitCardDisplay(
                        habit        = habit,
                        dates        = dates,
                        today        = today,
                        daysElapsed  = daysElapsed(habit.startDate, today),
                        streak       = streakResult,
                    ),
                )
            }
            HabitListUiState(
                items             = items,
                checkedTodayCount = items.count { it.checkedInToday },
                maxStreak         = items.maxOfOrNull { it.streak } ?: 0,
                tip               = pickHabitTip(items, today)?.let { StudyTips.forEvent(it) },
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HabitListUiState())

    // ── 创建页：习惯叠加的可选锚点（现有习惯的名字 + 何时线索） ───────────
    val anchors: StateFlow<List<HabitScience.StackAnchor>> = repository.observeAll()
        .map { habits -> habits.map { HabitScience.StackAnchor(it.name, it.cueTime) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ── 日历页状态 ────────────────────────────────────────────────────────
    private val _detail = MutableStateFlow<HabitDetailUi?>(null)
    val detail: StateFlow<HabitDetailUi?> = _detail
    private var detailJob: Job? = null

    /** 补卡被拒时的一次性提示（卡用完 / 超窗口），消费后清空 */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message
    fun consumeMessage() { _message.value = null }

    fun loadDetail(habitId: Long) {
        if (_detail.value?.habit?.id == habitId) return
        detailJob?.cancel()
        detailJob = viewModelScope.launch {
            combine(
                repository.observeAll(),
                repository.observeCheckIns(habitId),
            ) { habits, checkIns ->
                val habit = habits.firstOrNull { it.id == habitId } ?: return@combine null
                val dates = checkIns.toLocalDates()
                val today = LocalDate.now()
                val streakResult = HabitScience.streakWithProtection(dates, today, habitQuota(habit, today).left)
                val rate = HabitScience.weeklyRate(dates, today)
                val met = rate.metTarget(habit.weeklyTargetDays)
                HabitDetailUi(
                    habit           = habit,
                    checkedDates    = dates,
                    totalCheckDays  = dates.size,
                    totalAmount     = checkIns.sumOf { it.amount },
                    streak          = streakResult.streak,
                    streakCardsUsed = streakResult.cardsUsed,
                    cardsLeft       = habitQuota(habit, today).left,
                    weeklyText      = "本周 ${rate.checkedDays}/${rate.windowDays}",
                    weeklyMet       = met,
                )
            }.collect { _detail.value = it }
        }
    }

    /** 查某习惯某日的既有打卡记录（打卡弹层预填用） */
    suspend fun findCheckIn(habitId: Long, date: LocalDate): CheckIn? =
        repository.findCheckInOn(habitId, date)

    /** 一键打卡（天数型）：备注自动带入习惯默认文案，幂等 */
    fun checkIn(habit: Habit) {
        viewModelScope.launch { repository.checkInToday(habit.id, habit.defaultText) }
    }

    /**
     * 提交打卡：
     * - 今日（或未来拦截）：正常记录，isMakeup=false
     * - 过往日期：走补卡流程，写 isMakeup=true 并消耗一张保护卡；卡用完则拒绝并说明原因
     */
    fun submitCheckIn(habit: Habit, date: LocalDate, note: String, amount: Double) {
        val today = LocalDate.now()
        if (date.isAfter(today)) return
        if (date.isBefore(today)) {
            when (val decision = decideMakeup(habit, date, today)) {
                is MakeupDecision.Blocked -> _message.value = decision.reason
                is MakeupDecision.Allowed -> viewModelScope.launch {
                    val quota = habitQuota(habit, today)
                    val ok = repository.makeupCheckIn(
                        habitId = habit.id,
                        date = date,
                        note = note,
                        amount = amount,
                        quotaPeriod = quota.period,
                        quotaCards = quota.cards,
                        quotaUsedAfter = decision.protectionUsedAfter,
                    )
                    if (!ok) _message.value = "这个月的保护卡已经用完，明天接着来就好。"
                }
            }
            return
        }
        viewModelScope.launch { repository.checkInOn(habit.id, date, note, amount) }
    }

    /** 创建习惯（含执行意图的何时/何地线索），成功后回调（通常用于返回上一页） */
    fun createHabit(
        name: String,
        icon: String,
        targetDays: Int,
        targetCount: Double,
        unit: String,
        defaultText: String,
        cueTime: String,
        cuePlace: String,
        onSaved: () -> Unit,
    ) {
        viewModelScope.launch {
            repository.add(
                name = name.trim(),
                icon = icon,
                targetDays = targetDays,
                targetCount = targetCount,
                unit = unit.trim(),
                defaultText = defaultText.trim(),
                cueTime = cueTime.trim(),
                cuePlace = cuePlace.trim(),
            )
            onSaved()
        }
    }

    /** 分享成就文本：「我坚持了 X 天」等汇总，走系统分享面板 */
    fun shareAchievement() {
        val items = uiState.value.items
        if (items.isEmpty()) return
        HabitExporter.shareText(getApplication(), HabitExporter.buildShareText(items))
    }

    /** 导出全部打卡记录为 CSV（写入应用内部目录）并以系统分享面板发出 */
    fun exportCsv() {
        viewModelScope.launch {
            val habits = repository.getAll()
            val checkIns = repository.getAllCheckIns()
            if (checkIns.isEmpty()) return@launch
            val file = withContext(Dispatchers.IO) {
                HabitExporter.writeCsv(getApplication(), habits, checkIns)
            }
            HabitExporter.shareFile(getApplication(), file)
        }
    }
}

/** 打卡记录导出与成就分享文本（系统 Intent，无第三方依赖） */
object HabitExporter {

    private val fileDateFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")

    /** 生成成就分享文本 */
    fun buildShareText(items: List<HabitItemUi>): String = buildString {
        appendLine("【StudyKit · 我的习惯打卡】")
        items.forEach { item ->
            val icon = habitIconEmoji(item.habit.icon)
            if (item.achieved) {
                appendLine("$icon ${item.habit.name}：已达成目标（${item.progressText}）🎉")
            } else {
                val kept = if (item.isCountType) "累计 ${item.progressText}" else "坚持了 ${item.totalCheckDays} 天"
                appendLine("$icon ${item.habit.name}：$kept，连续 ${item.streak} 天，进度 ${item.progressText}")
            }
        }
        appendLine()
        append(
            "共 ${items.size} 个习惯 · 今日已打卡 ${items.count { it.checkedInToday }} 个" +
                " · 最长连续 ${items.maxOfOrNull { it.streak } ?: 0} 天",
        )
    }

    /** 系统分享纯文本 */
    fun shareText(context: android.content.Context, text: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(intent, "分享打卡成就").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** 导出 CSV 到应用内部目录并返回文件 */
    fun writeCsv(context: android.content.Context, habits: List<Habit>, checkIns: List<CheckIn>): File {
        val nameById = habits.associate { it.id to it.name }
        val file = File(context.filesDir, "studykit_checkins_${LocalDate.now().format(fileDateFmt)}.csv")
        file.bufferedWriter().use { writer ->
            writer.write("\uFEFF习惯,日期,数量,备注")
            writer.newLine()
            checkIns.forEach { checkIn ->
                val name = nameById[checkIn.habitId] ?: "习惯"
                writer.write(
                    "${csvEscape(name)},${checkIn.date},${formatAmount(checkIn.amount)},${csvEscape(checkIn.note)}",
                )
                writer.newLine()
            }
        }
        return file
    }

    /** 以系统分享面板发出 CSV 文件（FileProvider 授权） */
    fun shareFile(context: android.content.Context, file: File) {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context, "com.studykit.fileprovider", file,
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "StudyKit 打卡记录")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "导出打卡记录").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun csvEscape(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' }) {
            "\"${value.replace("\"", "\"\"")}\""
        } else value
}
