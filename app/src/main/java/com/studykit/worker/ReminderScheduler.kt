package com.studykit.worker

import android.content.Context
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.studykit.StudyKitApp
import com.studykit.data.AppSettings
import com.studykit.data.repository.SettingsRepository
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * 复习提醒任务调度器：保证「每 N 小时」的周期任务存在，N 由用户的本地设置决定。
 *
 * 两个入口：应用启动时 [StudyKitApp] 观察设置流并调 [applyInterval]；
 * 开机广播走 [ensureScheduled]（那时还没有 Compose 生命周期，只能自己起协程读库）。
 *
 * ## 为什么首碰用 KEEP、之后才 REPLACE
 *
 * `REPLACE` 会把周期任务的倒计时清零。如果每次冷启动都 REPLACE，一个"每 6 小时"的提醒
 * 对天天打开 App 的人就永远等不到。所以本进程记住上一次真正排下去的周期：
 * 没排过 → `KEEP`（沿用上次会话留下的任务）；排过且值变了（用户真改了设置）→ `REPLACE`，
 * 代价是那一次"下一提醒"从改动的时刻重新计时，这是应该的。
 */
object ReminderScheduler {

    private const val UNIQUE_WORK_NAME = "studykit_review_reminder_periodic"
    private const val TAG = "ReminderScheduler"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 本进程真正往 WorkManager 排过的周期；null 表示还没碰过 */
    @Volatile
    private var appliedHours: Int? = null

    /**
     * 从设置读周期后再排。
     *
     * 读库失败退回默认值，但**必须留一句话**：静默回落会把"设置没生效"伪装成"提醒一切正常"
     * —— 用户设了每 2 小时，实际收到的是每 6 小时，而他没有任何办法知道。
     * 这里不能弹 toast（开机广播路径上根本没有界面），所以落一条 ERROR 带原始异常。
     */
    fun ensureScheduled(context: Context) {
        val repository = (context.applicationContext as? StudyKitApp)?.container?.settingsRepository
        if (repository == null) {
            Log.w(TAG, "拿不到 SettingsRepository（Application 不是 StudyKitApp？），按默认周期排")
            applyInterval(context, AppSettings.DEFAULT_REMINDER_HOURS)
            return
        }
        scope.launch {
            val read = runCatching { repository.current().reminderEveryHours }
            val hours = read.getOrElse {
                Log.e(TAG, "读提醒周期失败，退回默认 ${AppSettings.DEFAULT_REMINDER_HOURS} 小时" +
                        "—— 用户设的值可能没生效", it)
                AppSettings.DEFAULT_REMINDER_HOURS
            }
            applyInterval(context, hours)
        }
    }

    /**
     * 观察到的设置值（含首帧），幂等：值没变就直接返回。
     *
     * 排任务这一步**包起来**，理由有两条，第二条是这轮实测出来的：
     * ① 对用户来说"没排上提醒"比"应用崩了"好，所以捕获；但捕获必须落 ERROR 带原始异常，
     *    否则就是上面那条"静默回落"的老毛病。
     * ② 不捕获的话，异常从 `Dispatchers.Default` 上的协程里逃出去，成为**未捕获异常**，
     *    而 Robolectric 会把未捕获异常算给**后面随便哪个测试类**
     *    （`UncaughtExceptionsBeforeTest`，受害者取决于执行顺序）。本仓"给契约仪式加渲染测试
     *    最后被迫删掉"的根因就在这条上。
     *
     * 失败时**不写** [appliedHours]：那样下一次设置变动或下一趟观察还能再试一次，
     * 而不是把"没排上"记成"已经按这个值排过了"。
     */
    fun applyInterval(context: Context, hours: Int) {
        val target = hours.coerceIn(AppSettings.HOURS_RANGE)
        val previous = appliedHours
        if (previous == target) return
        val policy = if (previous == null) {
            ExistingPeriodicWorkPolicy.KEEP
        } else {
            ExistingPeriodicWorkPolicy.REPLACE
        }
        val request = PeriodicWorkRequestBuilder<ReminderWorker>(target.toLong(), TimeUnit.HOURS)
            .addTag(TAG)
            .build()
        try {
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(UNIQUE_WORK_NAME, policy, request)
        } catch (t: Throwable) {
            Log.e(TAG, "复习提醒任务没能入队（每 $target 小时，策略 $policy）—— " +
                    "提醒可能不会按时来，下次设置变动会再试", t)
            return
        }
        appliedHours = target
        Log.i(TAG, "复习提醒周期任务已入队（每 $target 小时，策略 $policy）")
    }

    /**
     * 由 [StudyKitApp] 在启动时挂上：设置里的周期一变就重新入队。
     *
     * **整段收集都要包住**，不能只包 `applyInterval` —— 异常不在"排任务"那一步，
     * 而在**读设置流**这一步，而它发生在另一个线程上，外面套多少层 try 都拦不住，
     * 除非套在收集本身上。这条是 2026-09-26 用一份完整堆栈定位到的，
     * 它同时解释了这个仓追了两轮的那个现象（见下）。
     *
     * ## 这段协程逃出去的异常，就是本仓 Robolectric 测试"随机红"的源头
     *
     * `scope` 挂在 `object` 上，**生命周期等于进程**，而 [android.app.Application] 没有
     * `onDestroy`，所以这里没有任何东西会取消它。真机上这是对的：库开一整个进程，
     * 观察者也该活一整个进程。但在 Robolectric 下不是：
     * 每个测试类都会重建一次 `StudyKitApp`（于是多挂一个收集者），
     * 而每个类结束时 Robolectric 会把 SQLite 连接拆掉 —— **收集者还活着**。
     * 下一个类一开始，那个上一轮的收集者就去查一张已经关掉的库：
     *
     * ```
     * Exception in thread "DefaultDispatcher-worker-2 @coroutine#52"
     * java.lang.IllegalStateException: Illegal connection pointer 138.
     *     Current pointers for thread Thread[#47,arch_disk_io_1 ...] []
     *   at org.robolectric.shadows.ShadowLegacySQLiteConnection...
     *   at com.studykit.data.dao.AppSettingDao_Impl$6.call(AppSettingDao_Impl.java:132)
     *   at com.studykit.worker.ReminderScheduler$observeAndApply$1.invokeSuspend(ReminderScheduler.kt:112)
     * ```
     *
     * 它落在 `Dispatchers.Default` 上、没人接，于是成为**未捕获异常**；
     * 而 kotlinx-coroutines-test 在**下一个**进入 `runTest` 的测试那里把它报成
     * `UncaughtExceptionsBeforeTest` —— **红的不是那条测试的断言，受害者取决于跑序**。
     * 这就是 2026-09-24 那条 Compose 渲染守卫"聚焦跑绿、全量跑红"最后被整批撤回的真因
     * （当时只查到"来自后台线程上的一次数据库访问"，没查到是哪一条）。
     *
     * 捕获之后它不再外溢，所以那条渲染守卫**可以**留在 CI 里。
     * 代价是真机上如果设置流哪天读挂了，这个观察者会静默停止工作 ——
     * 所以必须落一条 ERROR，且说清"本进程内后续改周期不会再自动重排"。
     */
    fun observeAndApply(context: Context, repository: SettingsRepository) {
        scope.launch {
            try {
                repository.settings
                    .map { it.reminderEveryHours }
                    .distinctUntilChanged()
                    .collect { applyInterval(context, it) }
            } catch (t: Throwable) {
                Log.e(TAG, "复习提醒的设置观察者已停止：本进程内再改提醒周期不会自动重排" +
                        "（下次冷启动会重新挂上）", t)
            }
        }
    }
}
