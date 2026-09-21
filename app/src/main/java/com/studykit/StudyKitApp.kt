package com.studykit

import android.app.Application
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
    }
}
