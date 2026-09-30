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
import com.studykit.srs.Fsrs
import com.studykit.srs.MemoryState
import kotlin.math.roundToInt

/** 一条复习提醒通知的标题与正文 */
data class ReminderContent(
    val title: String,
    val text: String,
)

/**
 * 把三类到期数量与整体预测保留率格式化成通知文案（纯函数，便于单测）。
 *
 * 约定：
 * - 三类全为 0 时返回 null，调用方据此不发通知；
 * - 数量为 0 的类别不出现在文案里；
 * - [averageRetention] 为到期集内已调度条目的平均预测保留率（0..1），按四舍五入显示为百分比；
 * - 措辞保持非威胁性（不用「断签」「清零」「再不复习就忘了」等），不加 emoji。
 */
fun buildReminderContent(
    wordDue: Int,
    mistakeDue: Int,
    excerptDue: Int,
    averageRetention: Double,
): ReminderContent? {
    val word = wordDue.coerceAtLeast(0)
    val mistake = mistakeDue.coerceAtLeast(0)
    val excerpt = excerptDue.coerceAtLeast(0)
    if (word + mistake + excerpt == 0) return null

    val parts = buildList {
        if (word > 0) add("单词 $word")
        if (mistake > 0) add("错题 $mistake")
        if (excerpt > 0) add("书摘 $excerpt")
    }
    val percent = (averageRetention.coerceIn(0.0, 1.0) * 100).roundToInt()
    return ReminderContent(
        title = "今日复习",
        text = "今日复习：${parts.joinToString("、")}；预测保留率 $percent%。",
    )
}

/**
 * 复习提醒 Worker（每 6 小时由 [ReminderScheduler] 周期触发）：
 * 汇总单词/错题/书摘的到期数量与到期集的整体预测保留率，有到期内容才发通知，点击打开应用。
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
                description = "单词、错题与书摘的到期复习提醒"
            }
            manager.createNotificationChannel(channel)
        }
    }

    override suspend fun doWork(): Result {
        val container = (context.applicationContext as StudyKitApp).container
        val db = container.database
        val now = System.currentTimeMillis()

        // 到期条件均由 SQL WHERE 过滤，避免全表载入内存
        val dueWords = db.wordDao().getDueForReview(now)
        val dueMistakes = db.mistakeDao().getDueSorted(now)
        val dueExcerpts = db.bookDao().getExcerptsDue(now)

        if (dueWords.isEmpty() && dueMistakes.isEmpty() && dueExcerpts.isEmpty()) {
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

        // 整体预测保留率：取本次到期条目中「已进入调度（stability>0）」部分的平均 retrievability；
        // 新词/未入队条目没有 FSRS 记忆状态，不参与平均，避免把保留率虚低成威胁性数字。
        val dueStates = buildList {
            dueWords.filter { it.isScheduled }.forEach {
                add(MemoryState(it.stability, it.difficulty, it.lastReviewAt, it.reps, it.lapses))
            }
            dueMistakes.filter { it.isScheduled }.forEach {
                add(MemoryState(it.stability, it.difficulty, it.lastReviewAt, it.reps, it.lapses))
            }
            dueExcerpts.filter { it.stability > 0.0 }.forEach {
                add(MemoryState(it.stability, it.difficulty, it.lastReviewAt, it.reps, it.lapses))
            }
        }
        val averageRetention = if (dueStates.isEmpty()) {
            Fsrs.DEFAULT_RETENTION
        } else {
            dueStates.sumOf { Fsrs.retrievability(it, now) } / dueStates.size
        }

        val content = buildReminderContent(
            wordDue = dueWords.size,
            mistakeDue = dueMistakes.size,
            excerptDue = dueExcerpts.size,
            averageRetention = averageRetention,
        ) ?: return Result.success()

        val openIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(content.title)
            .setContentText(content.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content.text))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(openIntent)
            .build()

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        Log.i(TAG, "复习提醒已发送：${content.text}")
        return Result.success()
    }
}
