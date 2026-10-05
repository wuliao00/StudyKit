package com.studykit

import android.app.Application
import com.studykit.ui.optimize.CalibrateWorker
import com.studykit.worker.ReminderScheduler
import com.studykit.worker.ReminderWorker

class StudyKitApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        ReminderWorker.ensureChannel(this)
        // 提醒周期现在来自用户设置（默认仍是 6 小时）。观察而非只读一次：
        // 在设置页改完不必重启进程。
        ReminderScheduler.observeAndApply(this, container.settingsRepository)
        // 干跑校准的周期任务（充电 + 空闲、不要网）：注册内部已 try/catch 落 ERROR，
        // 排不上只意味着“本进程不会自动校准”，绝不允许把异常带进 onCreate（见 CalibrateWorker）。
        CalibrateWorker.ensureRegistered(this)
    }
}
