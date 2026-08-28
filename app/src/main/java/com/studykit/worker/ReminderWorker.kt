package com.studykit.worker

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.studykit.MainActivity
import com.studykit.R
import com.studykit.StudyKitApp

/**
 * 复习提醒 Worker（每 6 小时由 [ReminderScheduler] 周期触发）：
 * 查询「已到期待复习的错题」与「今日到期单词」，有则发通知，点击打开应用。
 */
class ReminderWorker(
    private val context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    companion object {
        const val CHANNEL_ID = "studykit_review_reminder"
        const val CHANNEL_NAME = "复习提醒"
        private const val NOTIFICATION_ID = 7101
        private const val TAG = "ReminderWorker"

        /** 创建通知渠道（API 26+，幂等） */
        fun ensureChannel(context: Context) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "到期错题与单词的复习提醒"
            }
            manager.createNotificationChannel(channel)
        }
    }

    override suspend fun doWork(): Result {
        val container = (context.applicationContext as StudyKitApp).container
        val now = System.currentTimeMillis()

        // 到期条件均由 SQL WHERE 过滤，避免全表载入内存
        val dueMistakes = container.mistakeRepository.getDueForReview(now)
        val dueWords = container.wordRepository.getDueForReview(now)

        if (dueMistakes.isEmpty() && dueWords.isEmpty()) {
            Log.i(TAG, "无到期复习内容，本次不发通知")
            return Result.success()
        }

        // Android 13+ 未授予通知权限则跳过（代码兼容；渠道与权限由启动流程保证）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "通知权限未授予，跳过本次复习提醒")
            return Result.success()
        }
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            Log.w(TAG, "通知被系统关闭，跳过本次复习提醒")
            return Result.success()
        }

        val parts = buildList {
            if (dueMistakes.isNotEmpty()) add("${dueMistakes.size} 道错题到期")
            if (dueWords.isNotEmpty()) add("${dueWords.size} 个单词待复习")
        }
        val contentText = parts.joinToString("，") + "，打开 StudyKit 开始复习"

        val openIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("今日复习")
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(openIntent)
            .build()

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        Log.i(TAG, "复习提醒已发送：$contentText")
        return Result.success()
    }
}
