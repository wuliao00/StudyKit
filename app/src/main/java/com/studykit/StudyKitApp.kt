package com.studykit

import android.app.Application
import com.studykit.ui.optimize.CalibrateWorker
import com.studykit.worker.ReminderScheduler
import com.studykit.worker.ReminderWorker

/**
 * 应用进程：在 [onCreate] 里建 [AppContainer]（手动 DI，本仓不用 Hilt）并把周期任务排上。
 *
 * `open` 只为一个测试接缝：Robolectric 用例要覆写 [createContainer] 换成一次性内存库
 * （理由见 [AppContainer] 类注释）。生产 manifest 里挂的仍是这个类本身。
 */
open class StudyKitApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = createContainer()
        ReminderWorker.ensureChannel(this)
        // 提醒周期现在来自用户设置（默认仍是 6 小时）。观察而非只读一次：
        // 在设置页改完不必重启进程。
        ReminderScheduler.observeAndApply(this, container.settingsRepository)
        // 干跑校准的周期任务（充电 + 空闲、不要网）：注册内部已 try/catch 落 ERROR，
        // 排不上只意味着“本进程不会自动校准”，绝不允许把异常带进 onCreate（见 CalibrateWorker）。
        CalibrateWorker.ensureRegistered(this)
    }

    /**
     * 依赖容器的创建点。生产恒等 [AppContainer] 的 `Context` 构造子（进程单例数据库）；
     * Robolectric 用例覆写它换成一次性内存库，理由见 [AppContainer] 的类注释（单例跨用例存活
     * + 每个用例拆 SQLite 影子连接 = 第二个用例起 `Illegal connection pointer`）。
     */
    protected open fun createContainer(): AppContainer = AppContainer(this)
}
