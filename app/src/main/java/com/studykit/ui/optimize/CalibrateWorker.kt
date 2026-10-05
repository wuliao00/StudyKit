package com.studykit.ui.optimize

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.studykit.StudyKitApp
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

/**
 * 「充电 + 空闲」时的**干跑**校准任务（v2.9 计划 Task 11）。
 *
 * ## 它只做一件事：跑一次 `dryRun = true`，把报告留下，一个参数都不换
 *
 * 后台自己把调度内核换掉是不可接受的——用户没点头，排期行为就不该变。所以这条任务
 * 只把 [CalibrateState.describeForSettings] 那一行写进设置（[com.studykit.data.AppSettings.lastCalibrateReport]，
 * **只记不判**，同 `lastDictFailure` 的口径），校准页打开时摆出来；真要换核，是人在页面上点
 * "采纳"，走 [CalibrateViewModel.adopt]。
 *
 * ## 约束为什么是「充电 且 空闲、不要网」
 *
 * - `setRequiresCharging(true)` + `setRequiresDeviceIdle(true)`：训练是 CPU 密集（AdamW 迭代 21 个参数
 *   × 上千条 term），只在设备闲着充电时跑，绝不在用户手上正用时抢 CPU 与电量；
 * - `setRequiredNetworkType(NetworkType.NOT_REQUIRED)`：整条链路**全在端侧**——读 `review_log`、训练、
 *   门禁、写快照，一个字节都不出机器。这条显式写成"不需要网"是为了把设计意图钉在代码里：
 *   以后谁想加"把日志传上去训"，得先把这个约束改掉并解释为什么，而不是顺手挂个上传。
 *
 * 周期取 [REPEAT_INTERVAL_DAYS] 天一次（WorkManager 的周期任务最小 15 分钟，但这份数据一天也长不了多少，
 * 更密的周期只会更早撞上"数据不足"那道闸，白耗一次唤醒）。充电+空闲两个条件本来就不会准时到，
 * 实际间隔通常比声明的更长——这正是想要的行为。
 *
 * ## 注册与取消
 *
 * [Companion.ensureRegistered] 在 [StudyKitApp] 启动时调一次，策略 `KEEP`（沿用上次会话排下的任务，
 * 不把倒计时清零，与 [com.studykit.worker.ReminderScheduler] 首碰用 KEEP 同一条理由）；
 * [Companion.cancel] 留给校准页那个"不再后台自动校准"的开关。两者都包 try/catch 落 ERROR：
 * 排不上任务的代价是"这周没跑成"，而异常逃出协程的代价是本仓那个"随机红"
 * （Robolectric 把未捕获异常算给后面随便哪个测试类，见 [com.studykit.worker.ReminderScheduler] 的 KDoc）。
 *
 * 可取消：WorkManager 停任务时本协程会被 cancel，读库 / `withContext` 这些挂起点会立刻退出；
 * 唯一不理会的是一段正在执行的 JNI 训练（native 调用没法被 Kotlin 协作式取消打断），
 * 它跑完之后编排里下一次挂起点才结束——最坏多耗一轮训练，不换核（干跑本来就不写参数）。
 */
class CalibrateWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? StudyKitApp ?: run {
            Log.w(TAG, "Application 不是 StudyKitApp，跳过本次干跑")
            return Result.success()
        }
        val state = try {
            CalibrateViewModel.of(app.container).run(dryRun = true)
        } catch (ce: CancellationException) {
            throw ce // 任务被取消就正常让 WorkManager 收到取消，不写成"跑过了"
        } catch (t: Throwable) {
            // run() 本来不吃异常；这一层是双保险，免得"永不崩调度"这条纪律依赖另一个类的实现
            Log.e(TAG, "干跑校准冒出异常（已被兜住，参数未改动）", t)
            return Result.success()
        }

        val report = state.describeForSettings()
        try {
            app.container.settingsRepository.recordCalibrateReport(report)
        } catch (t: Throwable) {
            Log.e(TAG, "干跑报告没能写进设置：$report", t)
        }
        Log.i(TAG, "干跑结束：$report")
        // 恒 success、绝不 RETRY：失败的形状是"数据不足 / 原生不可用"，重试只会再来一次同样的失败，
        // 而本任务的约束（充电+空闲）本来就要等资源，重试等于把设备的空闲窗口占给一件做不成的事。
        return Result.success()
    }

    companion object {
        private const val TAG = "CalibrateWorker"
        private const val UNIQUE_WORK_NAME = "studykit_fsrs_calibrate_periodic"

        /** 声明周期：一天一次（实际会被充电+空闲两个条件推后）。 */
        private const val REPEAT_INTERVAL_DAYS = 1L

        /**
         * 干跑用的约束：充电 且 空闲 且 不需要网（端侧闭环，见类内 KDoc）。
         *
         * 做成函数而不是 `val` 常量：`Constraints` 在纯 JVM 单测里没有 Robolectric 拦截，
         * 写成伴生常量会在**任何**碰到本类的测试里提前加载它；函数只在真要排任务时才跑。
         */
        fun idleChargingConstraints(): Constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
            .setRequiresCharging(true)
            .setRequiresDeviceIdle(true)
            .build()

        /**
         * 保证那份周期任务存在（启动时调，幂等）。
         *
         * 返回是否真的排上：排不上不抛，只落 ERROR——调用点在 `Application.onCreate`，
         * 那里抛出去等于整个应用起不来，而"这周没自动校准"根本不值得。
         */
        fun ensureRegistered(context: Context): Boolean {
            val request = PeriodicWorkRequestBuilder<CalibrateWorker>(
                REPEAT_INTERVAL_DAYS, TimeUnit.DAYS,
            )
                .setConstraints(idleChargingConstraints())
                .addTag(TAG)
                .build()
            return try {
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    UNIQUE_WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request,
                )
                true
            } catch (t: Throwable) {
                Log.e(TAG, "FSRS 干跑任务没能入队（本次安装不会自动校准，下次启动会再试）", t)
                false
            }
        }

        /** 取消那份周期任务（校准页的开关关掉时）。已经排下的这一次也会被取消。 */
        fun cancel(context: Context) {
            try {
                WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
            } catch (t: Throwable) {
                Log.e(TAG, "取消 FSRS 干跑任务失败", t)
            }
        }
    }
}
