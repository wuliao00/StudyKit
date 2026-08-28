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
        ReminderScheduler.ensureScheduled(this)
    }
}
