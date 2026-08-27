package com.studykit

import android.app.Application
import android.util.Log
import com.studykit.worker.ReminderScheduler
import com.studykit.worker.ReminderWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class StudyKitApp : Application() {

    lateinit var container: AppContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        ReminderWorker.ensureChannel(this)
        ReminderScheduler.ensureScheduled(this)
        warmUpDatabase()
    }

    /** 触发数据库首次创建与演示数据播种，并输出各表记录数用于验证 */
    private fun warmUpDatabase() {
        appScope.launch {
            try {
                val db = container.database
                val words = db.wordDao().getAll().size
                val subjects = db.questionDao().countBySubject("数学")
                val habits = db.habitDao().getAll().size
                val books = db.bookDao().getById(1L)?.let { 1 } ?: 0
                Log.i(
                    "StudyKitDB",
                    "数据库初始化完成 words=$words 数学题=$subjects habits=$habits books>=${if (books > 0) 1 else 0}",
                )
            } catch (e: Exception) {
                Log.e("StudyKitDB", "数据库初始化失败", e)
            }
        }
    }
}
