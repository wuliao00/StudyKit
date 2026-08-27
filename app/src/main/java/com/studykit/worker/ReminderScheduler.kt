package com.studykit.worker

import android.content.Context
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * 复习提醒任务调度器：幂等地保证「每 6 小时」周期任务存在。
 * 应用启动与开机广播都会调用 [ensureScheduled]。
 */
object ReminderScheduler {

    private const val UNIQUE_WORK_NAME = "studykit_review_reminder_periodic"
    private const val TAG = "ReminderScheduler"

    fun ensureScheduled(context: Context) {
        val request = PeriodicWorkRequestBuilder<ReminderWorker>(6, TimeUnit.HOURS)
            .addTag(TAG)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
        Log.i(TAG, "复习提醒周期任务已入队（每 6 小时，KEEP 幂等策略）")
    }
}
