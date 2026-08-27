package com.studykit.ui.habit

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.studykit.StudyKitApp
import com.studykit.data.entity.CheckIn
import com.studykit.data.entity.Habit
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

    /** 进度文案：数量型「300/500 ml」；天数型「5/21 天」 */
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
)

/** 日历页展示模型 */
data class HabitDetailUi(
    val habit: Habit,
    val checkedDates: Set<LocalDate>,
    val totalCheckDays: Int,
    val totalAmount: Double,
    val streak: Int,
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
                HabitItemUi(
                    habit          = habit,
                    checkedDates   = dates,
                    totalCheckDays = dates.size,
                    totalAmount    = checkIns.sumOf { it.amount },
                    todayAmount    = checkIns
                        .filter { runCatching { LocalDate.parse(it.date) }.getOrNull() == today }
                        .sumOf { it.amount },
                    streak         = habitStreak(dates, today),
                    checkedInToday = today in dates,
                    latestNote     = checkIns.maxByOrNull { it.date }
                        ?.takeIf { it.note.isNotBlank() }?.note.orEmpty(),
                )
            }
            HabitListUiState(
                items             = items,
                checkedTodayCount = items.count { it.checkedInToday },
                maxStreak         = items.maxOfOrNull { it.streak } ?: 0,
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HabitListUiState())

    // ── 日历页状态 ────────────────────────────────────────────────────────
    private val _detail = MutableStateFlow<HabitDetailUi?>(null)
    val detail: StateFlow<HabitDetailUi?> = _detail
    private var detailJob: Job? = null

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
                HabitDetailUi(
                    habit          = habit,
                    checkedDates   = dates,
                    totalCheckDays = dates.size,
                    totalAmount    = checkIns.sumOf { it.amount },
                    streak         = habitStreak(dates),
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
     * 提交打卡（今日或补卡）：
     * - 数量型传 amount>0 累加；天数型传 0 记一天
     * - 补卡仅限过去 [MAKEUP_WINDOW_DAYS] 天内
     */
    fun submitCheckIn(habit: Habit, date: LocalDate, note: String, amount: Double) {
        val today = LocalDate.now()
        if (date.isAfter(today)) return
        if (date.isBefore(today) && !canMakeUp(date, today)) return
        viewModelScope.launch { repository.checkInOn(habit.id, date, note, amount) }
    }

    /** 创建习惯（支持数量型与默认打卡文案），成功后回调（通常用于返回上一页） */
    fun createHabit(
        name: String,
        icon: String,
        targetDays: Int,
        targetCount: Double,
        unit: String,
        defaultText: String,
        onSaved: () -> Unit,
    ) {
        viewModelScope.launch {
            repository.add(name.trim(), icon, targetDays, targetCount, unit.trim(), defaultText.trim())
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
