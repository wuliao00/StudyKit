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
import com.studykit.data.entity.Word
import com.studykit.data.memory.SchedulingKernel
import com.studykit.data.memory.kernelStateFor
import com.studykit.data.memory.recallForDisplay
import com.studykit.ui.study.KernelHub
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

private val ONE_DAY_MS: Long = TimeUnit.DAYS.toMillis(1)

/**
 * 这批到期词「现在大约还记得多少」（纯函数，本轮 B 段）。
 *
 * **不另建一套曲线**：逐条走 [recallForDisplay]（它按活跃内核取那条曲线），锚点 `lastReviewAt ?: createdAt`，
 * 对整批取概率的算术平均 —— 与首页 `TomorrowForecast.of` 同一口径（同一个函数、同一个锚点约定、
 * 同一个"脏 h 行留在分母"）。两处唯一实质差别是取值时刻：那一格拿的是"各自明天到期时"，
 * 这里是"现在"（这批词已经到期了）。两个时刻平均出的百分比会差很多，但那是口径本身，不是算法分家。
 *
 * @param kernel **活跃内核**，由调用方从设置里取（[KernelHub.forId]）；默认回 FSRS 只为保住既有调用点。
 *   不许拿行上的 `words.kernel` 列逐行挑内核：那一列是"上一次谁原生写了这行"的审计戳，
 *   用户切了内核之后，它会把一批词拆成两条曲线去读，而通知里那句"还记得 X%"必须与
 *   评分按钮上那行同源（spec §2.1；`KernelHub` 的 KDoc）。
 *
 * @return 0~100；**词集为空、或整批半衰期都不可信时返回 null**。
 *   后者宁可整段不提也不能印 0%：半衰期那条曲线对 h ≤ 0 回 0 是"当作全忘了"的降级，
 *   而不是测出来的值；把它当成实测印到通知里，就是拿不存在的坏消息推动用户打开应用。
 *   （双写之后 FSRS 写的行也会把镜像列填成可信正值，所以这道闸在两个内核下都成立。）
 *
 * 入参直接复用 `WordDao.getDueForReview` 那一条 `SELECT *`：同一行里就有
 * `half_life_days` / `last_review_at` / `created_at` / `fsrs_stability`，所以结构上不存在"两次查询再内存配对"。
 */
internal fun dueRetentionPercent(
    words: List<Word>,
    now: Long,
    kernel: SchedulingKernel = KernelHub.forId(null),
): Int? {
    if (words.isEmpty()) return null
    // 至少得有一个可信的 h，否则算出来的数来路不明（全脏时逐条会是 0 ⇒ 平均 0%）
    if (words.none { it.halfLifeDays.isFinite() && it.halfLifeDays > 0.0 }) return null
    val probabilities = words.map { word ->
        val anchor = word.lastReviewAt ?: word.createdAt
        val gapDays = (now - anchor).coerceAtLeast(0L) / ONE_DAY_MS.toDouble()
        recallForDisplay(kernel, kernelStateFor(kernel, word), gapDays)
    }
    return ((probabilities.sum() / probabilities.size) * 100).roundToInt()
}

/**
 * 通知正文（纯函数）。零的那一类不出现在文案里；两类都为零 ⇒ null，调用方据此**不发通知**。
 *
 * 保留率拿不到（[retentionPercent] = null）时整段不提，也不拿 0% 顶上。
 * 那半句主语是"这些单词"：错题没有半衰期字段（`Mistake` 只有 `reviewAt`），
 * 把它们也算进一个"你还记得 X%"就是在说假话。
 */
internal fun reminderContentText(mistakeCount: Int, wordCount: Int, retentionPercent: Int?): String? {
    if (mistakeCount <= 0 && wordCount <= 0) return null
    val parts = buildList {
        if (mistakeCount > 0) add("$mistakeCount 道错题到期")
        if (wordCount > 0) add("$wordCount 个单词待复习")
        // 单词为 0 时保留率没有归属对象，那一半句不能挂到错题身上
        if (retentionPercent != null && wordCount > 0) add("这些单词现在大约还记得 $retentionPercent%")
    }
    return parts.joinToString("，") + "，打开 StudyKit 开始复习"
}

/**
 * 复习提醒 Worker（每 6 小时由 [ReminderScheduler] 周期触发）：
 * 查询「已到期待复习的错题」与「今日到期单词」，有则发通知，点击打开应用。
 *
 * 正文由 [reminderContentText] 拼：零的那一类不提，两类都为零就不发；单词那一档
 * 本轮额外带上 [dueRetentionPercent] 算出的整体预测保留率（取不到就整段不提）。
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

        val contentText = reminderContentText(
            mistakeCount = dueMistakes.size,
            wordCount = dueWords.size,
            // 与上面那批词同一个列表、同一个 now，不重查也不配对。
            // 内核从设置取：worker 手上就有 container.settingsRepository，不需要拿 rows.kernel 做例外
            retentionPercent = dueRetentionPercent(
                dueWords,
                now,
                KernelHub.forId(container.settingsRepository.current().schedulingKernel),
            ),
        ) ?: return Result.success().also { Log.i(TAG, "到期量为 0，本次不发通知") }

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
