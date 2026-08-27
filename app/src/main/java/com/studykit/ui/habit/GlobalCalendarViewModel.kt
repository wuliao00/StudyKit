package com.studykit.ui.habit

import android.app.Application
import android.content.pm.PackageManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.studykit.StudyKitApp
import com.studykit.data.SystemCalendarReader
import com.studykit.data.SystemEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** READ_CALENDARS 权限字符串（不依赖 SDK 常量，避免编译环境差异） */
const val PermissionReadCalendars = "android.permission.READ_CALENDARS"

/** 部分厂商框架（如 vivo）将日历权限改名为单数形式，需兼容 */
const val PermissionReadCalendar = "android.permission.READ_CALENDAR"

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
 * 全局日历页 ViewModel：
 * - 打卡记录流（Room）按日归组为「日期 -> 习惯名列表」
 * - 系统日历事件（CalendarContract）在 IO 线程查询后转 StateFlow
 */
class GlobalCalendarViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = (application as StudyKitApp).container.habitRepository
    private val reader = SystemCalendarReader(application)

    /** 打卡记录按日归组：日期 -> 当日打卡的习惯名列表 */
    val checkInsByDate: StateFlow<Map<LocalDate, List<String>>> =
        combine(repository.observeAll(), repository.observeAllCheckIns()) { habits, checkIns ->
            val nameById = habits.associate { it.id to it.name }
            checkIns.mapNotNull { checkIn ->
                val date = runCatching { LocalDate.parse(checkIn.date) }.getOrNull() ?: return@mapNotNull null
                date to (nameById[checkIn.habitId] ?: "习惯")
            }.groupBy({ it.first }, { it.second })
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private val _system = MutableStateFlow(SystemCalendarUiState())
    val system: StateFlow<SystemCalendarUiState> = _system

    /** 日历读取权限是否已授权（兼容两种权限名） */
    fun hasCalendarPermission(): Boolean {
        val pm = getApplication<Application>().packageManager
        return pm.checkPermission(PermissionReadCalendars, getApplication<Application>().packageName) ==
            PackageManager.PERMISSION_GRANTED ||
            pm.checkPermission(PermissionReadCalendar, getApplication<Application>().packageName) ==
            PackageManager.PERMISSION_GRANTED
    }

    /** 待申请的日历权限：优先系统实际定义的名称（vivo 框架为 READ_CALENDAR） */
    fun calendarPermissionToRequest(): String {
        val pm = getApplication<Application>().packageManager
        return listOf(PermissionReadCalendars, PermissionReadCalendar).firstOrNull { permission ->
            runCatching { pm.getPermissionInfo(permission, 0) }.isSuccess
        } ?: PermissionReadCalendars
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
