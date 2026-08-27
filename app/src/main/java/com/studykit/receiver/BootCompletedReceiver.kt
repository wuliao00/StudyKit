package com.studykit.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.studykit.worker.ReminderScheduler

/** 开机完成后重新确保复习提醒周期任务存在（WorkManager 持久化兜底之外的显式重挂） */
class BootCompletedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        Log.i("ReminderScheduler", "收到开机广播，重挂复习提醒任务")
        ReminderScheduler.ensureScheduled(context)
    }
}
