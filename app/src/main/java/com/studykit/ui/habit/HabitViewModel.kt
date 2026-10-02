package com.studykit.ui.habit

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.studykit.StudyKitApp
import com.studykit.data.entity.CheckIn
import com.studykit.data.entity.Habit
import com.studykit.util.OneShotGate
import com.studykit.util.rejectWithToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

/** 补打卡窗口：仅允许补录过去 7 天内未打卡的日期 */
const val MAKEUP_WINDOW_DAYS = 7L

/** 弹性达标窗口：看的是"含今天的近 7 个日历日"，与连续天数那套回溯无关 */
const val WEEK_WINDOW_DAYS = 7

/** 达标线：近 7 天做到 5 天就算跟上 —— 漏一两天不判负（Lally et al. 2010, EJSP） */
const val WEEK_TARGET_DAYS = 5

/**
 * 创建页的目标天数档位（本轮 A 段）：以 66 天为中位数，两侧给个体差异留出空间。
 *
 * 旧档位是 7/21/30/60/100，中心那个 21 已被原研究团队辟谣；这里把中心换成
 * [Habit.DEFAULT_TARGET_DAYS]（66），且**必须**让默认值落在档位里 ——
 * 否则新建页进来时那一格选不中，用户看到的第一个数是"没选"。
 * 上限 180：研究报的是 18–254 天，而超过半年仍未自动化的习惯，不是把
 * 这个数字再调大能救的。
 *
 * 放在 ViewModel 而不是 `HabitCreateScreen`：常量要被纯 JVM 单测读到，而放
 * Compose 文件里读一个非 const 顶层 val 会拖着整个 Compose 类加载进场。
 */
internal val HABIT_TARGET_DAY_OPTIONS = listOf(7, 30, 66, 100, 180)

/**
 * 创建页档位下面那句诚实说明。
 *
 * 数字全部从常量插值，免得改了默认值而文案还在讲旧数。断签保护是**纯推导、零落库**（spec D6，
 * 见 `ui/habit/HabitGuard.kt` / `HabitGuardDisplay.kt`：按自然月取前 2 次缺卡为受保护，
 * 不加列、不写库），所以这里既不提额度数字、也不承诺"漏掉的日子会被自动补上"——那是补录
 * （[MAKEUP_WINDOW_DAYS]）那条独立机制，只能照实说；保护只影响"断不断"的展示口径。
 *
 * 同一行在数量型习惯那里标的标签是"目标期限（天）"，所以这句**不许**写"它不是期限"
 * —— 那对数量型是错的（过了那天就完不成了）。反 KPI 那一层靠"漏一天不算断"与 5/7 撑。
 */
internal val HABIT_TARGET_DAYS_HINT: String =
    "研究里习惯成形约需中位数 ${Habit.DEFAULT_TARGET_DAYS} 天，个体差异很大（18–254 天），" +
        "这一格按自己的情况选就行。漏一天不算断，" +
        "近 $WEEK_WINDOW_DAYS 天做到 $WEEK_TARGET_DAYS 天就算跟上；" +
        "漏掉的日子在 $MAKEUP_WINDOW_DAYS 天内还能补录（设置里可关），超出就补不了了。"

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
    /**
     * 近 7 天弹性达标（本轮 A 段）：与 [streak] 并列的第二指标。
     *
     * 默认按满周算（方便测试与旁路构造），列表那边会按习惯实际年龄重算窗口：
     * 新建三天的习惯拿 5/7 当达标线是凭空造坏消息。只读现有 `check_ins`，不加列。
     */
    val week: WeekCompliance = weekCompliance(checkedDates),
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

    /** 进度文案：数量型「300/500 ml」；天数型「5/66 天」（分母就是习惯自己的 targetDays） */
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

/**
 * 近 7 天（弹性）达标的结果：列表卡片上与「连续天数」并列的第二个指标。
 *
 * 为什么要第二个指标：[habitStreak] 那一套是"断了就回到 0"，而养成研究里恰好不是这样
 * （漏一天不毁掉自动性）。只看连续天数会把"漏一天"这个完全正常的动作变成一次可见的失败。
 * 两个数并排摆着，前者说势头、后者说比例，用户自己看得懂它们不矛盾。
 *
 * @param windowDays 窗口天数（含今天），新习惯还没满一周时小于 [WEEK_WINDOW_DAYS]
 * @param checkedDays 窗口内打过卡的天数（同一天多条只算一天，集合语义）
 * @param targetDays 本窗口的达标线，由 [weekTargetDays] 按窗口等比缩放
 * @param ratePercent 达标率（四舍五入取整）：分母是窗口天数，不是目标天数
 * @param achieved 跟上没跟上（[checkedDays] ≥ [targetDays]）
 */
data class WeekCompliance(
    val windowDays: Int,
    val checkedDays: Int,
    val targetDays: Int,
    val ratePercent: Int,
    val achieved: Boolean,
)

/**
 * 达标线（纯函数）：满窗口时就是 [WEEK_TARGET_DAYS]（7 天里 5 天）；
 * 窗口短了就等比缩放，并且永远不高于窗口本身 —— 一个昨天的习惯不该被要求做到 5 天。
 */
internal fun weekTargetDays(windowDays: Int): Int {
    val days = windowDays.coerceIn(1, WEEK_WINDOW_DAYS)
    if (days == WEEK_WINDOW_DAYS) return WEEK_TARGET_DAYS
    return (days * WEEK_TARGET_DAYS.toDouble() / WEEK_WINDOW_DAYS).roundToInt().coerceIn(1, days)
}

/**
 * 窗口实际长度（纯函数）：习惯本身还不满一周时，分母先跟日子数走，
 * 否则新建当天的习惯会被凭空判一次"未达标"。封顶 [WEEK_WINDOW_DAYS]；
 * 建库时间在未来（时钟漂移）也不许算出 0 或负窗口。
 */
internal fun weeklyWindowDays(startDate: LocalDate, today: LocalDate = LocalDate.now()): Int =
    (ChronoUnit.DAYS.between(startDate, today) + 1).coerceIn(1, WEEK_WINDOW_DAYS.toLong()).toInt()

/**
 * 近 [windowDays] 天达标情况（纯函数）：只看含今天向前数那几天，更早的打卡不进分子也不进分母。
 *
 * 与 [habitStreak] 共用同一个 `Set<LocalDate>`，但口径不同：这里不回溯、不在窗口中间
 * "断开"，所以漏一天只是把两个数都拉低一点，不会像连续天数那样归零。
 *
 * 这里是**公开**而不是 internal：[HabitItemUi.week] 是公开属性，它的默认值就调这个函数，
 * 可见性上不留任何需要推敲的地方（本轮不能跑编译，能避就避）。上面两个算式仍然 internal。
 */
fun weekCompliance(
    dates: Set<LocalDate>,
    today: LocalDate = LocalDate.now(),
    windowDays: Int = WEEK_WINDOW_DAYS,
): WeekCompliance {
    val days = windowDays.coerceIn(1, WEEK_WINDOW_DAYS)
    val from = today.minusDays((days - 1).toLong())
    val checked = dates.count { !it.isBefore(from) && !it.isAfter(today) }
    val target = weekTargetDays(days)
    return WeekCompliance(
        windowDays = days,
        checkedDays = checked,
        targetDays = target,
        ratePercent = (checked * 100.0 / days).roundToInt(),
        achieved = checked >= target,
    )
}

/**
 * 列表卡片那一行（纯函数，为的是能被单测钉住）：跟上时多两个字，没跟上时只报两个数。
 *
 * 两个约定：① 不判罪也不吓唬（"没跟上"/"断签"/"再不…就…"这类字一个都不写）；
 * ② 不宣称本轮没有的东西（无额度、无自动补签 —— 那需要加列，超出范围）。
 */
internal fun weekComplianceLabel(week: WeekCompliance): String =
    "近 ${week.windowDays} 天 ${week.checkedDays}/${week.windowDays}" + (if (week.achieved) " 达标" else "")

/**
 * 列表副标题那一行文字（计划 B Task 16 / spec §7）：
 * **优先展示执行意图整句** `habits.ifThen`（一旦用户把习惯写成"当…我就…"，这句比冷冰冰的
 * "连续 N 天"更贴近他当初的承诺），空则回退到既有的双指标口径。抽成纯函数是为了让这条
 * UI 文案的分支被 JVM 单测钉住（`HabitSubtitleTest`），页面只消费结果。
 */
internal fun habitSubtitleLine(item: HabitItemUi): String {
    if (item.habit.ifThen.isNotBlank()) return item.habit.ifThen
    return buildString {
        append(
            if (item.isCountType) {
                "累计 ${item.progressText} · 连续 ${item.streak} 天"
            } else {
                "连续 ${item.streak} 天 · 累计 ${item.totalCheckDays} 天"
            },
        )
        append(" · ${weekComplianceLabel(item.week)}")
        if (item.habit.category != Habit.CATEGORY_ANY) {
            append(" · ${categoryLabel(category = item.habit.category)}")
        }
    }
}

/** 该日期是否可补打卡：过去 7 天内（不含今天与未来） */
fun canMakeUp(date: LocalDate, today: LocalDate = LocalDate.now()): Boolean =
    date.isBefore(today) && !date.isBefore(today.minusDays(MAKEUP_WINDOW_DAYS))

/**
 * 日历上一格**能不能点开补卡弹层**（纯函数）：没打过 + 设置允许补卡 + 在窗口内。
 *
 * 为什么把"设置允许"并进这个函数、而不是让各页面自己判：`canMakeUp` 只管日期窗口，
 * 页面上只用它的话，开关关了格子照样画成灰圈、照样能点，填完弹层才被
 * `submitCheckIn` 静默丢掉 —— 全局日历是 `canMakeUp(date) && showMakeUp` 两条一起判的，
 * 习惯日历漏了后一条（2026-09-24 读代码走查发现，不是真机撞上的）。
 * 入口判定和写入判定共用一条规则，才不会一处开门一处锁门。
 */
internal fun isMakeUpEligible(
    checked: Boolean,
    makeupAllowed: Boolean,
    date: LocalDate,
    today: LocalDate,
): Boolean = !checked && makeupAllowed && canMakeUp(date, today)

/**
 * 日界归属（纯函数）：boundary=b 时，"今天 b 点之前"发生的事算**前一天**。
 * 例：boundary=3，凌晨 1 点的打卡 → 昨天。0 点边界原样返回。
 */
internal fun effectiveCheckInDate(now: java.time.Instant, zone: java.time.ZoneId, boundaryHour: Int): LocalDate =
    now.atZone(zone).toLocalDateTime().minusHours(boundaryHour.coerceIn(0, 12).toLong()).toLocalDate()

/**
 * 打卡时段窗口（纯函数）。end ≤ start 视为跨零点窗口（如 22:00–06:00）；
 * **start == end 视为不限制**而不是永久锁死 —— 那是手滑写错时把自己锁在门外的兜底。
 */
internal fun isWithinCheckInWindow(nowMinuteOfDay: Int, startMin: Int, endMin: Int): Boolean {
    val s = startMin.coerceIn(0, 24 * 60)
    val e = endMin.coerceIn(0, 24 * 60)
    val now = nowMinuteOfDay.coerceIn(0, 24 * 60 - 1)
    if (s == e) return true
    return if (s < e) now in s until e else now >= s || now < e
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
    private val settingsRepository = (application as StudyKitApp).container.settingsRepository

    /** 贪吃蛇历史最高分（批次一）。 只增不减的逻辑在写入侧做，读档侧不兜底 */
    val snakeBest: StateFlow<Int> = settingsRepository.settings
        .map { it.snakeBest }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** 是否允许补打卡：全局日历的空日面板据此决定显不显示补卡入口（守卫在 submitCheckIn） */
    val makeupAllowed: StateFlow<Boolean> = settingsRepository.settings
        .map { it.makeupAllowed }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    /** 一局结束报分：只有真的破了纪录才写库，读档侧取 max 是双保险不是主逻辑 */
    fun submitSnakeScore(score: Int) {
        if (score <= 0) return
        viewModelScope.launch {
            val current = settingsRepository.current().snakeBest
            if (score > current) settingsRepository.update { it.copy(snakeBest = score) }
        }
    }

    // ── 列表页状态：习惯流 × 各习惯打卡流，自动响应打卡写入 ─────────────
    // flatMapLatest 仍是实验 API：这里按调用点局部 opt-in，不给整个类挂 @OptIn（会把后续
    // 新增实验 API 的调用一起静默掉）
    @OptIn(ExperimentalCoroutinesApi::class)
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
                // 窗口长度跟习惯实际年龄走（同一个 today、同一批 dates，与 streak 不会各说各话）
                val startDay = Instant.ofEpochMilli(habit.startDate)
                    .atZone(ZoneId.systemDefault()).toLocalDate()
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
                    week           = weekCompliance(dates, today, weeklyWindowDays(startDay, today)),
                )
            }
            HabitListUiState(
                // 时段分类排序（v2.4 批次四）：当前时段的组置顶、组内按 sortOrder；
                // 归档的 observeAll 本就查不出来，这里再滤一道是纯函数自身口径的兜底。
                // 下游（HabitListScreen 的待打卡/已打卡分区、HeatmapCard 的 activeDays 并集、
                // 统计磁贴计数）都只依赖集合与计数，不依赖原 start_date 序，换序无副作用。
                items             = sortedForList(items = items, now = LocalTime.now()),
                checkedTodayCount = items.count { it.checkedInToday },
                maxStreak         = items.maxOfOrNull { it.streak } ?: 0,
            )
        }
        // 逐条 `LocalDate.parse` + 求和 + 连续天数回溯都在这一段，习惯多、打卡记录厚时
        // 不该压在主线（终审 I7）；写法与 `StudyViewModel.homeState` 一致
        .flowOn(context = Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HabitListUiState())

    // ── 日历页状态 ────────────────────────────────────────────────────────
    /** 页面请求的习惯 id（route 上的 `habitId`），由页面自己的 LaunchedEffect 写入 */
    private val _detailId = MutableStateFlow<Long?>(null)
    val detailId: StateFlow<Long?> = _detailId

    /**
     * 日历页状态。
     *
     * 旧写法是 `viewModelScope.launch { combine(...).collect { _detail.value = it } }`
     * （终审 I12）：`viewModelScope` 要活到 VM 销毁，页面早就关了这条订阅还在，
     * 之后每次打卡写库都白算一遍全量 parse + 连续天数。换成 flatMapLatest +
     * `stateIn(WhileSubscribed(5_000))` —— 与本页 [uiState]、`MistakeViewModel.detailState`
     * 同一形态：离开页面 5s 后自动退订，期间回到页面还能拿上一份值，不会闪空态。
     */
    // flatMapLatest 仍是实验 API：按调用点局部 opt-in（与 [uiState] 同一理由）
    @OptIn(ExperimentalCoroutinesApi::class)
    val detail: StateFlow<HabitDetailUi?> = _detailId
        .flatMapLatest { id ->
            if (id == null) {
                flowOf(null)
            } else {
                combine(
                    repository.observeAll(),
                    repository.observeCheckIns(id),
                ) { habits, checkIns ->
                    val habit = habits.firstOrNull { it.id == id } ?: return@combine null
                    val dates = checkIns.toLocalDates()
                    HabitDetailUi(
                        habit          = habit,
                        checkedDates   = dates,
                        totalCheckDays = dates.size,
                        totalAmount    = checkIns.sumOf { it.amount },
                        streak         = habitStreak(dates),
                    )
                }
            }
        }
        // 这一段同样逐条 parse + 回溯连续天数，跟着 [uiState] 一起下推 Default（终审 I7）
        .flowOn(context = Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun loadDetail(habitId: Long) {
        _detailId.value = habitId
    }

    // ── 整理页（v2.4 批次四）：分类 / 排序 / 归档 ─────────────────────────
    /**
     * 整理页状态：全部习惯**含已归档**，已归档沉底、组间按 CATEGORIES 序、组内按 sortOrder。
     * 与 [uiState] 分开供给：列表页只要未归档，整理页要全量 —— 两个口径各自收口，不互相将就。
     */
    val organizeHabits: StateFlow<List<Habit>> = repository.observeAllIncludingArchived()
        .map { habits ->
            habits.sortedWith(
                comparator = compareBy(
                    { habit -> habit.archived },
                    { habit -> categoryOrderIndex(category = habit.category) },
                    { habit -> habit.sortOrder },
                ),
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 改习惯的时段分类；写库后 observeAllIncludingArchived 自动刷新 */
    fun setCategory(id: Long, category: String) {
        viewModelScope.launch { repository.updateCategory(id = id, category = category) }
    }

    /**
     * 上移/下移一个位置：与**相邻同分类**习惯互换 sortOrder；已是最前/最后时不动。
     * 只在未归档链上换位 —— 整理页里归档组沉底独立展示，不参与组内排序。
     */
    fun moveHabit(id: Long, delta: Int) {
        if (delta == 0) return
        viewModelScope.launch {
            val all = repository.getAll()
            val me = all.firstOrNull { it.id == id } ?: return@launch
            val chain = all
                .filter { habit -> !habit.archived && habit.category == me.category }
                .sortedBy { it.sortOrder }
            val index = chain.indexOfFirst { it.id == id }
            if (index < 0) return@launch
            val neighbor = chain.getOrNull(index + delta) ?: return@launch
            repository.updateSortOrder(id = id, sortOrder = neighbor.sortOrder)
            repository.updateSortOrder(id = neighbor.id, sortOrder = me.sortOrder)
        }
    }

    /** 归档开关：归档后列表页（observeAll）自动隐藏，整理页沉底可找回 */
    fun setArchived(id: Long, archived: Boolean) {
        viewModelScope.launch { repository.setArchived(id = id, archived = archived) }
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
    fun submitCheckIn(habit: Habit, date: LocalDate, note: String, amount: Double, isMakeup: Boolean = false) {
        viewModelScope.launch {
            val zone = ZoneId.systemDefault()
            val now = java.time.Instant.now()
            val s = settingsRepository.settings.first()
            val today = now.atZone(zone).toLocalDate()
            if (date.isAfter(today)) return@launch
            val boundary = s.dayBoundaryHour.coerceIn(0, 12)
            // 日界归属在**写入时**定对：boundary=b 时，今天 b 点之前的打卡算前一天。
            // check_ins 只存日期串不存时刻，事后无法重算 —— 错过这里就永远错了。
            val effective = if (date == today && boundary > 0) effectiveCheckInDate(now, zone, boundary) else date
            if (effective.isBefore(today)) {
                // 三条拒绝路径必须**全都**说话。下面"限时段"那条早就有 Toast，理由写在
                // 它自己的注释里（"静默拒绝最坑人 —— 用户以为打了，账上却没有"），
                // 但补卡的这两条一直是 `return@launch` 装死。两个入口页面（习惯日历、
                // 全局日历）现在都做了闸门，正常路径走不到这里；走到就是闸门漏了
                // （新增页面、或设置刚被改过），那时候静默就等于丢数据。
                if (!s.makeupAllowed) {
                    rejectWithToast("补打卡已在设置里关闭，这次没有记上")
                    return@launch
                }
                if (!canMakeUp(effective, today)) {
                    rejectWithToast("只能补最近 $MAKEUP_WINDOW_DAYS 天的空缺，$effective 已经过期了")
                    return@launch
                }
            }
            if (s.restrictCheckIn && effective == today) {
                val nowMin = now.atZone(zone).let { it.hour * 60 + it.minute }
                if (!isWithinCheckInWindow(nowMin, s.restrictStartMin, s.restrictEndMin)) {
                    // 静默拒绝最坑人 —— 用户以为打了，账上却没有。这里必须说话。
                    rejectWithToast(
                        "当前不在可打卡时段（${s.restrictStartMin / 60}:${"%02d".format(s.restrictStartMin % 60)}" +
                            "–${s.restrictEndMin / 60}:${"%02d".format(s.restrictEndMin % 60)}）",
                    )
                    return@launch
                }
            }
            repository.checkInOn(habit.id, effective, note, amount, isMakeup)
        }
    }

    /** 创建习惯（支持数量型与默认打卡文案），成功后回调（通常用于返回上一页） */
    // 一次性门（终审 C4）：连点「保存习惯」会双插库 + 双 pop
    private val savingHabit = OneShotGate()

    fun createHabit(
        name: String,
        icon: String,
        targetDays: Int,
        targetCount: Double,
        unit: String,
        defaultText: String,
        // v2.7 B16：执行意图整句 + 时段分类（创建页「何时何地→做什么」三输入区的产物）。
        ifThen: String = "",
        category: String = Habit.CATEGORY_ANY,
        onSaved: () -> Unit,
    ) {
        if (!savingHabit.tryEnter()) return
        viewModelScope.launch {
            try {
                repository.add(
                    name.trim(),
                    icon,
                    targetDays,
                    targetCount,
                    unit.trim(),
                    defaultText.trim(),
                    ifThen = ifThen,
                    category = category,
                )
                // 触发点：首次成功保存一条带了 ifThen 的习惯（每次安装一次，口径同 flipTipSeen）。
                // 写在 onSaved 之前，保证即使页面立刻 pop 也把"看过没有"落库。
                if (ifThen.isNotBlank()) markIfThenTipSeen()
                onSaved()
            } finally {
                savingHabit.leave()
            }
        }
    }

    /**
     * 首次保存带执行意图的习惯时那条 IF_THEN 贴士：置真后每次安装不再出现。
     * 与 [markFlipTipSeen]、[com.studykit.ui.study.StudyViewModel] 的同款纪律一致——写失败不提示
     * 也不重投（后果只是下次保存还会再提一次），不在用户正要建习惯的路上弹 toast。
     */
    fun markIfThenTipSeen() {
        viewModelScope.launch {
            runCatching { settingsRepository.update { it.copy(ifThenTipSeen = true) } }
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
