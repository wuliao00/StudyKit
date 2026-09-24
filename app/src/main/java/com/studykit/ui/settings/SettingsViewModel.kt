package com.studykit.ui.settings

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.text.format.Formatter
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.studykit.MainActivity
import com.studykit.StudyKitApp
import com.studykit.data.AppSettings
import com.studykit.data.GlassLevel
import com.studykit.data.ThemeMode
import com.studykit.data.memory.ReviewStrictness
import com.studykit.util.MistakeImageStore
import com.studykit.util.OneShotGate
import com.studykit.util.backup.BackupArchive
import com.studykit.util.backup.StorageStats
import com.studykit.util.backup.StorageStatsReader
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.system.exitProcess

/**
 * 最深一层带 message 的原因（与 `DictStoreViewModel.rootMessage` 同一套做法）：
 * 外层包装只会说「备份失败」，能判断的永远是 `FileNotFoundException` / 磁盘满 / zip 校验不过这类根因。
 */
private fun Throwable.rootMessage(): String {
    var cursor: Throwable? = this
    var best: String? = message
    while (cursor != null) {
        cursor.message?.takeIf { it.isNotBlank() }?.let { best = it }
        cursor = cursor.cause
    }
    return best ?: javaClass.simpleName
}

/** 短字节的中文说法：`Formatter` 给的是 "1.2 MB" 这种带单位的串，够用且不引新依赖 */
private fun Application.humanSize(bytes: Long): String = Formatter.formatShortFileSize(this, bytes)

/**
 * 设置页的 ViewModel：一份 [AppSettings] 快照 + 一次占用统计 + 一条一次性提示。
 *
 * ## 写侧：一格一次整表 upsert
 *
 * 每格改动都直接调 `SettingsRepository.update`（读-改-写），页面没有「保存」按钮，
 * 也就没有「改了没存」这种状态。文本格的防抖在**界面**那一侧（见 `SettingsScreen` 的 `DebounceMs`），
 * 这里收到的永远是已经能落库的完整值。写失败只提示不回滚：库是唯一真相，
 * 下一次 `repository.current()` 读回来就是这个键的真实样子。
 *
 * ## 读侧：`Eagerly` 的那一帧
 *
 * [settings] 用 `SharingStarted.Eagerly`。写路径本身走 `repository.current()`（库里真值），
 * 所以这份缓存只服务渲染 —— 用 Eagerly 是为了 VM 一建好就去订阅，界面尽早拿到真值，
 * 不必先按 [AppSettings] 的默认值画一帧（`MainActivity` 的冷启动注释里记过同一件事）。
 *
 * ## 备份与恢复的调用契约
 *
 * `util/backup/` 由并行的另一批代码提供，这里按契约调用：
 * `BackupArchive.exportTo(context, target): Long`（返回压缩字节数）、
 * `BackupArchive.restoreFrom(context, source): RestoreSummary`、
 * `StorageStatsReader.of(context): StorageStats`；失败一律抛带根因的 `BackupException`。
 */
class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as StudyKitApp).container
    private val repository = container.settingsRepository

    val settings: StateFlow<AppSettings> = repository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    private val _stats = MutableStateFlow<StorageStats?>(null)

    /** 本机占用统计；null = 还没算出来（首帧、或上一次读取失败后重算中） */
    val stats: StateFlow<StorageStats?> = _stats.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)

    /** 一次性提示（成功与失败原因共用一条通道）；界面显示后必须调 [consumeMessage] 关掉 */
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _busy = MutableStateFlow(false)

    /** 长任务进行中：导出、恢复（以及 [clearBusinessData]）。界面据此禁用这几个入口 */
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val statsGate = OneShotGate()
    private val exportGate = OneShotGate()
    private val restoreGate = OneShotGate()
    private val resetGate = OneShotGate()
    private val clearGate = OneShotGate()

    init {
        refreshStats()
    }

    // ── 占用统计 ────────────────────────────────────────────────

    /** 重算占用统计。失败只留下原因、不把已算出的数字抹掉（用户可能只是没权限读到某个目录） */
    fun refreshStats() {
        if (!statsGate.tryEnter()) return
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { StorageStatsReader.of(getApplication<Application>()) }
            }
                .onSuccess { snapshot -> _stats.value = snapshot }
                .onFailure { error -> _message.value = "读取占用统计失败：${error.rootMessage()}" }
            statsGate.leave()
        }
    }

    fun consumeMessage() {
        _message.value = null
    }

    // ── 外观与动效 ──────────────────────────────────────────────

    /** 主题三态。`MainActivity` 观察的是同一条 [AppSettings] 流，所以这一格改完整个 app 当场换色 */
    fun setThemeMode(mode: ThemeMode) = write { it.copy(themeMode = mode) }

    /** 玻璃档位。材质只服务底栏与弹层那几个浮层站点，卡面恒为实底 */
    fun setGlass(level: GlassLevel) = write { it.copy(glass = level) }

    /** 复习严格度：AUTO 由考试日期反推，其余三档把目标准确率钉死 */
    fun setReviewStrictness(value: ReviewStrictness) = write { it.copy(reviewStrictness = value) }

    /** 「一天」从几点开始：凌晨打卡算前一天，熬夜不再断签 */
    fun setDayBoundaryHour(value: Int) = write { it.copy(dayBoundaryHour = value) }

    fun setMakeupAllowed(on: Boolean) = write { it.copy(makeupAllowed = on) }

    /**
     * 限制时段。**与 spec 的偏离**：本版给四档预设而不是自由填两个时间点 ——
     * 自由时间要做 "HH:mm" 解析、校验、防 end≤start 的兜底 UI，一版塞不下；
     * 预设已覆盖常用档，底层字段（分钟数）不变，以后放开只动 UI。
     */
    fun setCheckInWindow(preset: Int) = write {
        when (preset) {
            1 -> it.copy(restrictCheckIn = true, restrictStartMin = 8 * 60, restrictEndMin = 22 * 60)
            2 -> it.copy(restrictCheckIn = true, restrictStartMin = 7 * 60, restrictEndMin = 23 * 60)
            3 -> it.copy(restrictCheckIn = true, restrictStartMin = 9 * 60, restrictEndMin = 24 * 60)
            else -> it.copy(restrictCheckIn = false)
        }
    }

    /** 减弱动效：关掉彩带、错峰入场与按压缩放 */
    fun setReduceMotion(on: Boolean) = write { it.copy(reduceMotion = on) }

    // ── 档案与目标 ──────────────────────────────────────────────

    /** 昵称：收边距、掐到 [AppSettings.NICKNAME_MAX]，与 [AppSettings.fromMap] 的收口口径一致 */
    fun setNickname(value: String) =
        write { it.copy(nickname = value.trim().take(AppSettings.NICKNAME_MAX)) }

    fun setDailyWordGoal(value: Int) =
        write { it.copy(dailyWordGoal = value.coerceIn(AppSettings.WORD_GOAL_RANGE)) }

    /**
     * 复习提醒周期。写完之后不需要在这里重排 WorkManager ——
     * `StudyKitApp` 已经 `ReminderScheduler.observeAndApply(...)`，值的变更自己会重新入队。
     */
    fun setReminderHours(value: Int) =
        write { it.copy(reminderEveryHours = value.coerceIn(AppSettings.HOURS_RANGE)) }

    /** null 或越界的日期都写回「未设置」（`examEpochDay = 0`）；界面已经先校验过，这里只是兜底 */
    fun setExamDate(date: LocalDate?) {
        val epochDay = date?.toEpochDay()?.takeIf { it in AppSettings.EXAM_DAY_RANGE } ?: 0L
        write { it.copy(examEpochDay = epochDay) }
    }

    // ── 数据管理 ────────────────────────────────────────────────

    /** 导出 zip 到 SAF 选定的位置；[Uri] 的写权限由系统随这次结果一起给，不必持久化授权 */
    fun exportTo(uri: Uri) {
        if (!exportGate.tryEnter()) return
        _busy.value = true
        viewModelScope.launch {
            val app = getApplication<Application>()
            runCatching { withContext(Dispatchers.IO) { BackupArchive.exportTo(app, uri) } }
                .onSuccess { bytes -> _message.value = "备份已导出，压缩包 ${app.humanSize(bytes)}" }
                .onFailure { error -> _message.value = "导出失败：${error.rootMessage()}" }
            _busy.value = false
            exportGate.leave()
        }
    }

    /**
     * 从 zip 恢复。**成功后立刻重启进程**（见 [restartApp]），因此这条路径上不还原 `busy`：
     * 进程还在的最后一帧仍该显示「恢复中」，而不是一个闪一下的按钮。
     */
    fun restoreFrom(uri: Uri) {
        if (!restoreGate.tryEnter()) return
        _busy.value = true
        viewModelScope.launch {
            val app = getApplication<Application>()
            runCatching { withContext(Dispatchers.IO) { BackupArchive.restoreFrom(app, uri) } }
                .onSuccess { summary ->
                    _message.value = "已恢复：数据库 ${app.humanSize(summary.dbBytes)}、" +
                        "错题图片 ${summary.imageCount} 张，正在重启"
                    _stats.value = null
                    restartApp()
                }
                .onFailure { error ->
                    _message.value = "恢复失败：${error.rootMessage()}"
                    _busy.value = false
                }
            restoreGate.leave()
        }
    }

    /** 恢复出厂式默认：整张 `app_settings` 清空即可（[AppSettings.fromMap] 对缺键取默认） */
    fun resetSettings() {
        if (!resetGate.tryEnter()) return
        viewModelScope.launch {
            runCatching { repository.resetToDefaults() }
                .onSuccess { _message.value = "设置已恢复默认，学习数据一条没动" }
                .onFailure { error -> _message.value = "恢复默认设置失败：${error.rootMessage()}" }
            resetGate.leave()
        }
    }

    /**
     * 清除学习数据，**保留设置**（`app_settings` 一行不动）。
     *
     * 覆盖十二张表：`words` + `word_reviews` + `word_lists`、`questions` + `practice_records`、
     * `mistakes`、`habits` + `check_ins` + `contracts`、`books` + `excerpts` + `book_reviews`。
     * 成对清是刻意的 —— 只删主表会留下悬空子表（复习记录挂在已消失的词上、打卡挂在已删除的习惯上），
     * 而热力图与"已学天数"会跟着虚高，那是比"没清干净"更难查的病。
     *
     * `contracts` 表是批次五新建的，而这里的清空当时漏了它：它指向习惯，却**没有外键**
     * （`Contract.habitId` 是裸 Long），所以删习惯时数据库不会替它做级联。漏掉的后果不是"占地方"，
     * 而是清完库仍然看得到旧契约、进度恒 0、到期被判未达成 —— 一条凭空多出来的失败记录。
     *
     * 顺序有两条硬规定：**图片路径必须在删 `mistakes` 行之前读出来**（行没了就再也找不到文件），
     * 而文件删除放在事务**之外**（磁盘操作失败不该回滚一次已经完成的清库，反之会把事务挂着等 IO）。
     */
    fun clearBusinessData() {
        if (!clearGate.tryEnter()) return
        _busy.value = true
        viewModelScope.launch {
            val app = getApplication<Application>()
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val db = container.database
                    val mistakesBefore = db.mistakeDao().observeAll().first()
                    val wordsBefore = db.wordDao().getAll().size
                    val listsBefore = db.wordListDao().observeAll().first().size
                    val imagePaths = mistakesBefore.mapNotNull { it.imagePath }
                    db.withTransaction {
                        db.habitDao().deleteAllCheckIns()
                        db.habitDao().deleteAllHabits()
                        db.contractDao().deleteAll()
                        db.practiceDao().deleteAll()
                        db.questionDao().deleteAll()
                        db.bookDao().deleteAllReviews()
                        db.bookDao().deleteAllExcerpts()
                        db.bookDao().deleteAllBooks()
                        db.wordDao().deleteAllReviews()
                        db.wordDao().deleteAll()
                        db.wordListDao().deleteAll()
                        db.mistakeDao().deleteAll()
                    }
                    imagePaths.forEach { path -> MistakeImageStore.delete(app, path) }
                    Triple(wordsBefore, listsBefore, mistakesBefore.size)
                }
            }
            result.onSuccess { (wordCount, listCount, mistakeCount) ->
                _message.value = "学习数据已清除：$wordCount 条单词（含 $listCount 本词库）、" +
                    "$mistakeCount 道错题，习惯 / 打卡 / 契约 / 题目 / 答题记录 / 读书三表一并清零。设置保留。"
                _stats.value = null
            }.onFailure { error ->
                _message.value = "清除失败：${error.rootMessage()}"
            }
            _busy.value = false
            clearGate.leave()
            // 清完顺手重算一次占用，否则页面上的数字会停在清除前
            refreshStats()
        }
    }

    /**
     * 重启进程：`startActivity` 重新拉起 [MainActivity]，随后 `exitProcess(0)`。
     *
     * **为什么必须重启**，而不是「清一下缓存再刷界面」：
     * `restoreFrom` 是拿 zip 里的 `studykit.db` 原地覆盖数据库文件，而本进程的
     * `AppDatabase` 单例（连接池 + WAL 索引 + Room 的查询缓存）还开着旧的那个文件句柄。
     * SQLite 在 POSIX 语义下仍会从被替换掉的 inode 读，于是界面显示的是恢复前的数据；
     * 更糟的是任何一次写入都会落回旧连接，把刚恢复出来的库再盖回去。
     * 进程死了连接才断，冷启动的 `getInstance` 才会打开真正的新文件 ——
     * 这也是设置页在恢复前要先弹一次二次确认的原因。
     */
    fun restartApp() {
        val app = getApplication<Application>()
        val intent = Intent(app, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        app.startActivity(intent)
        exitProcess(0)
    }

    /** 一格一次整表 upsert；失败只报原因，不把界面状态改回去（库才是真相） */
    private fun write(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch {
            runCatching { repository.update(transform) }
                .onFailure { error -> _message.value = "这条设置没存下来：${error.rootMessage()}" }
        }
    }
}
