package com.studykit.ui.habit

import android.app.Application
import android.content.pm.PackageManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.studykit.StudyKitApp
import com.studykit.data.SystemCalendarReader
import com.studykit.data.SystemEvent
import com.studykit.data.entity.CheckIn
import com.studykit.data.entity.Habit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** 官方日历读权限（AOSP 定义即为单数 READ_CALENDAR，不依赖 SDK 常量避免编译环境差异） */
const val PermissionReadCalendar = "android.permission.READ_CALENDAR"

/** 历史遗留的复数形式（早期版本误声明），仅作为极端 ROM 的兼容回退保留 */
const val PermissionReadCalendars = "android.permission.READ_CALENDARS"

/** 系统日历加载状态 */
data class SystemCalendarUiState(
    val permissionGranted: Boolean = false,
    val loading: Boolean = false,
    val loadError: Boolean = false,
    /** 事件按日归组：日期 -> 当日事件（按开始时间升序） */
    val eventsByDate: Map<LocalDate, List<SystemEvent>> = emptyMap(),
    /** 明日事件列表 */
    val tomorrowEvents: List<SystemEvent> = emptyList(),
)

/**
 * 日详情卡里的一条打卡记录。
 *
 * 带着 `isMakeup` 而不是只给习惯名：`check_ins.is_makeup` 早就在写入了，但全仓没有任何一处
 * 读它 —— 更新说明与 `AppSettings.makeupAllowed` 的注释都承诺"补的单独标识、不与当天混算"，
 * 而投影在这里把标记丢掉，那句承诺在界面上根本不存在（2026-09-24 走查发现）。
 * 补卡与当天正常打卡在日历里必须看得出区别，否则用户无法知道哪天是补的。
 */
data class CheckedHabit(val name: String, val isMakeup: Boolean)

/**
 * 打卡记录按日归组。**补卡标记必须一路走到界面上** —— 这一段以前只把习惯名带出去，
 * `is_makeup` 在这里被丢掉，于是"单独标识"的承诺在 UI 上根本不存在（2026-09-24 走查发现）。
 *
 * 抽成纯函数正是为了这条回归能被单测钉住：本仓没有 instrumented 测试，
 * 投影逻辑一旦被改回"只留名字"，编译不报错、界面也看不出少东西，只有测试拦得住。
 *
 * 日期解析失败的行**整条丢弃**（不是归到某个默认日）—— 脏数据宁可不上屏，
 * 免得在日历上凭空多出一个谁都没打过的格子。
 */
internal fun groupCheckInsByDate(
    habits: List<Habit>,
    checkIns: List<CheckIn>,
): Map<LocalDate, List<CheckedHabit>> {
    val nameById = habits.associate { it.id to it.name }
    return checkIns.mapNotNull { checkIn ->
        val date = runCatching { LocalDate.parse(checkIn.date) }.getOrNull() ?: return@mapNotNull null
        date to CheckedHabit(
            name = nameById[checkIn.habitId] ?: "习惯",
            isMakeup = checkIn.isMakeup,
        )
    }.groupBy({ it.first }, { it.second })
}

/**
 * 全局日历页 ViewModel：
 * - 打卡记录流（Room）按日归组为「日期 -> 当日打卡记录（含是否补卡）」
 * - 系统日历事件（CalendarContract）在 IO 线程查询后转 StateFlow
 */
class GlobalCalendarViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = (application as StudyKitApp).container.habitRepository
    private val reader = SystemCalendarReader(application)

    /** 打卡记录按日归组：日期 -> 当日打卡的习惯（补卡带 `isMakeup`，供日详情卡标出来） */
    val checkInsByDate: StateFlow<Map<LocalDate, List<CheckedHabit>>> =
        combine(repository.observeAll(), repository.observeAllCheckIns()) { habits, checkIns ->
            groupCheckInsByDate(habits = habits, checkIns = checkIns)
        }
            // 全量打卡逐条 `LocalDate.parse` + 两次 map 查找 + groupBy：整段下推到 Default
            // （终审 I7，写法与 `StudyViewModel.homeState` 一致）
            .flowOn(context = Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private val _system = MutableStateFlow(SystemCalendarUiState())
    val system: StateFlow<SystemCalendarUiState> = _system

    /** 日历读取权限是否已授权（官方名优先，兼容历史遗留的复数形式） */
    fun hasCalendarPermission(): Boolean {
        val pm = getApplication<Application>().packageManager
        return pm.checkPermission(PermissionReadCalendar, getApplication<Application>().packageName) ==
            PackageManager.PERMISSION_GRANTED ||
            pm.checkPermission(PermissionReadCalendars, getApplication<Application>().packageName) ==
            PackageManager.PERMISSION_GRANTED
    }

    /** 待申请的日历权限：优先官方 READ_CALENDAR，仅当 ROM 未定义时回退历史遗留名称 */
    fun calendarPermissionToRequest(): String {
        val pm = getApplication<Application>().packageManager
        return listOf(PermissionReadCalendar, PermissionReadCalendars).firstOrNull { permission ->
            runCatching { pm.getPermissionInfo(permission, 0) }.isSuccess
        } ?: PermissionReadCalendar
    }

    /** 权限结果回调：授权后立即拉取系统日历 */
    fun onPermissionResult(granted: Boolean) {
        _system.update { it.copy(permissionGranted = granted) }
        if (granted) loadSystemEvents()
    }

    /** 查询系统日历：窗口为「今天-1天 ~ 今天+35天」，避免全表扫描 */
    fun loadSystemEvents() {
        _system.update { it.copy(loading = true, permissionGranted = true) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val today = LocalDate.now()
                    reader.queryEvents(from = today.minusDays(1), days = 36)
                }
            }
            val events = result.getOrDefault(emptyList())
            val tomorrow = LocalDate.now().plusDays(1)
            _system.update {
                it.copy(
                    loading = false,
                    loadError = result.isFailure,
                    eventsByDate = events.groupBy(SystemEvent::startDate),
                    tomorrowEvents = events
                        .filter { event -> event.startDate == tomorrow }
                        .sortedBy(SystemEvent::startMillis),
                )
            }
        }
    }
}
