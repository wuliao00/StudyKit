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
import com.studykit.AppContainer
import com.studykit.MainActivity
import com.studykit.R
import com.studykit.StudyKitApp
import com.studykit.data.AppSettings
import com.studykit.data.entity.Word
import com.studykit.data.memory.AlertKind
import com.studykit.data.memory.DueForecast
import com.studykit.data.memory.ForecastCfg
import com.studykit.data.memory.FsrsModel
import com.studykit.data.memory.MemoryScheduler
import com.studykit.data.memory.ReminderDecision
import com.studykit.data.memory.ReminderPolicy
import com.studykit.data.memory.SchedulingKernel
import com.studykit.data.memory.kernelStateFor
import com.studykit.data.memory.recallForDisplay
import com.studykit.ui.stats.ForecastCards
import com.studykit.ui.stats.toForecastCards
import com.studykit.ui.study.KernelHub
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
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
 * 通知正文（纯函数）。零的那一类不出现在文案里；两类都为零 ⇒ null。
 *
 * 这是**兜底路径**的判据：它为零只说明「今天没有东西躺在到期列表里」，
 * 而 [ReminderPolicy] 的堆积档说的恰恰是这种情况——所以 null 不等于「不发通知」，
 * 最终取舍在 [reminderCopy] 里按档位定。
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

/** 通知上要落的两行字：标题一行、正文一行。拆开是因为通知本来就是两行，不是拼接位。 */
internal data class ReminderCopy(val title: String, val text: String)

/** 兜底路径的标题 —— 改动前那句「今日复习」，一字不动。 */
internal const val FALLBACK_TITLE = "今日复习"

/**
 * 决策 → 通知那两行字的取舍（纯函数，v3.0-A Task 4）。
 *
 * 三档决策（跌破线 > 堆积 > 到期）在场时，标题与正文**逐字段**取自它：`headline` 进 title、
 * `body` 进 text，不做拼接——通知没有"一行两截"的渲染，拼起来等于把正文塞进标题行。
 * 决策为 null（今天练过了 / 没有可信的卡 / 三档都不成立 / 决策那一路读库挂了）时，
 * 退回 [reminderContentText] 的到期直陈。
 *
 * 直陈也是 null（两类到期皆为 0）**且决策也是 null** ⇒ 整条返回 null，调用方据此**不发通知**。
 * 这条与改动前逐字一致：新档位是升级，不是替换。
 *
 * ## 零到期日（[nothingDueToday]）按档位放行，不按"有没有东西到期"一刀切
 * 「两类皆 0 ⇒ 什么都不发」这道老闸是为**直陈**定的：对着一个今天没东西可复习的用户念"0 个单词待复习"
 * 是纯骚扰。但它一旦也打在决策上就会反过来咬掉两个档：
 *  - [AlertKind.LOAD_SPIKE] 的语义就是"今天没事做、明天要堆起来"（[com.studykit.data.memory.DueForecast]
 *    的类注释：「今天没练」会如实变成「明天堆积」）。**今天为空正是它唯一要开口的场合**，
 *    一刀切等于把三档里唯一为闲置用户设计的那档永久静音。
 *  - [AlertKind.RETENTION_DROP] 同理不总在该闸后面：跌破判定按 `fsrs_stability` 反解，而闸看的那两条
 *    SQL 口径都比它窄——词要 `next_review_at > 0 AND next_review_at <= now`（落库排期，切内核/改严格度
 *    之后不跟反解值同步），错题还要 `review_at IS NOT NULL`（v2.7 起那列是"手动覆盖，可空"，从没手动
 *    排过的错题进不了到期列表却就在预测卡集里），而书摘（第三张表）**根本不喂给这道闸**。
 *    于是"只有书摘跌破线"、"只有没手动排过的错题跌破线"这类运行，两类皆 0 而跌破档成立——照样被闸打死。
 *  - [AlertKind.DUE_NOW] 维持原行为：它按定义是"有东西到期"的直陈升级版，
 *    列表为空时那句"N 项今天到期"是在催一批 App 里找不到的人。
 * 所以这里只把 DUE_NOW 那一档留在闸后，另两档放行。
 */
internal fun reminderCopy(
    decision: ReminderDecision?,
    fallbackText: String?,
    nothingDueToday: Boolean,
): ReminderCopy? = when {
    decision != null && !(nothingDueToday && decision.kind == AlertKind.DUE_NOW) ->
        ReminderCopy(decision.headline, decision.body)

    fallbackText != null -> ReminderCopy(FALLBACK_TITLE, fallbackText)
    else -> null
}

/**
 * 历史复习时刻的**众数整点**（提醒相位的原料，纯函数）。
 *
 * 为什么对齐到它：`PeriodicWorkRequest` 的倒计时从入队那一刻起算，于是"每 6 小时"的提醒落在
 * 用户哪天恰好打开应用的那个钟点上——可能凌晨三点，可能开会中间。复习时刻本身就是一条最强的
 * 行为线索：一个人大概率在同一个钟点段复习，把首次触发挪到众数整点上，提醒到达时他更可能真的能坐下练。
 * 对齐用的是 [ReminderScheduler] 的 `setInitialDelay`，**不是**精确闹钟（计划偏差 D4：
 * `SCHEDULE_EXACT_ALARM` 要用户授权，与本仓"不额外要权限"的口径冲突），所以落点是尽力而为。
 *
 * 并列时取**较晚**的那个整点（用户更可能在晚间复习，晚间那一档也更该被当成提醒时刻）。
 * 这靠的是 (次数, 整点) 双键比较，不是 map 的迭代顺序——`groupingBy` 的序是实现细节，
 * 拿它当语义的话「9 点先出现就选 9 点」这种反向行为会悄无声息地进来。
 *
 * @return null = 没有任何复习史，调用方据此**不加** `setInitialDelay`，退回今天之前的固定节奏。
 */
internal fun sessionHourMode(timestamps: List<Long>, zone: ZoneId): Int? {
    if (timestamps.isEmpty()) return null
    val counts = timestamps.groupingBy { Instant.ofEpochMilli(it).atZone(zone).hour }.eachCount()
    val byCountThenHour: Comparator<Map.Entry<Int, Int>> = compareBy({ it.value }, { it.key })
    return counts.entries.maxWithOrNull(byCountThenHour)?.key
}

/**
 * 到下一个 [hour] 整点的毫秒数（`setInitialDelay` 的实参，纯函数）。
 *
 * 严格取**未来**的那个整点：此刻正好是 21:00 也算"已过"，推到明天同一钟点。延迟 0 等于任务
 * 入队当场就跑，而这条延迟的目的恰恰是把提醒从"入队的那一刻"挪走。
 *
 * 跨夏令时用 `ZonedDateTime.plusDays(1)`（加的是**日历日**，不是 86_400_000 毫秒），
 * 所以对齐的是"钟点"本身而不是"24 小时后"。日期从 [nowMs] 推而不是 `LocalDate.now(zone)`：
 * 后者会让这条纯函数在测试里跟着真实时钟漂，也会让「同一瞬间、不同调用」给出不一样的答案。
 */
internal fun delayMillisToHour(hour: Int, nowMs: Long, zone: ZoneId): Long {
    val now = Instant.ofEpochMilli(nowMs).atZone(zone)
    // 越界的脏整点夹进 0..23 而不是抛异常——本仓对脏值一律"降级不异常"（见 MemoryModel.recallProbability）
    val next = now.toLocalDate().atTime(hour.coerceIn(0, 23), 0).atZone(zone)
    val target = if (next.toInstant().isAfter(now.toInstant())) next else next.plusDays(1)
    return Duration.between(now.toInstant(), target.toInstant()).toMillis()
}

/**
 * 复习提醒 Worker（每 6 小时由 [ReminderScheduler] 周期触发）：
 * 查询「已到期待复习的错题」与「今日到期单词」，有则发通知，点击打开应用。
 *
 * 通知那两行字先问 [ReminderPolicy]（v3.0-A Task 4）：跌破线 > 堆积 > 到期，三档都不成立
 * （或今天已经练过词）才退回 [reminderContentText] 的到期直陈——两类皆 0 时直陈为空，照旧不发。
 * **今日没有到期内容不等于不发**：[AlertKind.LOAD_SPIKE]（今天闲、明天堆积）与
 * [AlertKind.RETENTION_DROP]（跌破线，含只存在于书摘表的那些卡）就是为那种日子准备的，
 * 只有 [AlertKind.DUE_NOW] 留在旧闸后面，取舍见 [reminderCopy]。
 * 决策那一路要多读三张表的快照，读挂了也只退回直陈（见 [forecastDecision]）。
 * 直陈里单词那一档带 [dueRetentionPercent] 算出的整体预测保留率（取不到就整段不提）。
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

        /** 负荷带前推天数：计划（v3.0-A Task 4 Step 3）定的 14 天窗口，也是文档 §负荷预测 的窗口 */
        private const val FORECAST_HORIZON_DAYS = 14

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

        // 今日两类皆 0 —— 这只是一个**事实**，不是"不发"的决定（v3.0-A/T4 的这处修复）。
        // 改动前这里直接 `return Result.success()`，于是 LOAD_SPIKE 唯一要开口的场合（今天没东西
        // 到期、明天堆积）连同"只有书摘跌破线"的那批 RETENTION_DROP 一起被静音。发不发由
        // [reminderCopy] 按档位定：跌破与堆积放行，到期直陈维持旧的零到期日静默。
        // 代价说清楚：零到期日现在也会走一遍设置与三张表的快照（每 6 小时一次），换的是那两档能说话。
        val nothingDueToday = dueMistakes.isEmpty() && dueWords.isEmpty()

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

        val settings = container.settingsRepository.current()

        // 新档位（v3.0-A Task 4）：该催哪件事由 ReminderPolicy 定，见 forecastDecision。
        // 这条新路径挂了只该让「到期直陈」照旧发出去，不该把一条本来能发的提醒一起带走；
        // 但捕获必须落 ERROR 带原始异常——否则「提醒变笨了」会被读成「今天确实没什么可催的」，
        // 与 ReminderScheduler 那条「静默回落会把没生效伪装成正常」是同一件事（这里刻意不用
        // runCatching：它连 CancellationException 一起吞，而那是"这次运行不该再发通知"的意思，
        // 不是"决策读挂了"，必须照原样往外抛）。
        val decision = try {
            forecastDecision(container, settings, now)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            Log.e(TAG, "负荷预测/提醒决策没跑成，本次退回到期直陈文案", t)
            null
        }

        val fallbackText = reminderContentText(
            mistakeCount = dueMistakes.size,
            wordCount = dueWords.size,
            // 与上面那批词同一个列表、同一个 now，不重查也不配对。
            // 内核从设置取：worker 手上就有 container.settingsRepository，不需要拿 rows.kernel 做例外
            retentionPercent = dueRetentionPercent(
                dueWords,
                now,
                KernelHub.forId(settings.schedulingKernel),
            ),
        )
        // "不发"在这里收口，两种情形（逐条见 [reminderCopy] 的零到期日规则）：
        //  ① 两类皆 0 且没有决策——旧行为，一字未改；
        //  ② 两类皆 0 且决策是 DUE_NOW——列表本就空，那句"N 项今天到期"催的是 App 里找不到的一批人。
        // 跌破线 / 堆积这两档在 ①② 之外照发：那正是它们存在的理由。
        val copy = reminderCopy(decision, fallbackText, nothingDueToday) ?: return Result.success().also {
            val reason = if (decision?.kind == AlertKind.DUE_NOW) "到期直陈档今日无到期内容" else "今日无可催的档"
            Log.i(TAG, "$reason，本次不发通知")
        }

        val openIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(copy.title)
            .setContentText(copy.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(copy.text))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(openIntent)
            .build()

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        // 带上走的是哪一路：排查「提醒没道理」时，「三档里哪一档命中的」与「退回直陈了」是两种病因
        Log.i(TAG, "复习提醒已发送（${decision?.kind ?: "到期直陈"}）：${copy.title} / ${copy.text}")
        return Result.success()
    }

    /**
     * 装配 → 负荷带 → 决策（v3.0-A Task 4 的接线路径，也是 [ReminderPolicy] 的第一个生产消费者）。
     *
     * ## 求值一次（[ForecastCards.of] 的 KDoc 契约，不是风格建议）
     * 三张表的快照各 `.first()` 一次、`of` 只调一次，结果绑到 `cards` 这一个局部量，然后把
     * **同一个列表**同时喂给 [DueForecast.forecast] 与 [ReminderPolicy.decide]。
     * 调两次的代价不是"多算一遍"而是**取两次快照**：两次之间只要有一次评分落库，负荷带数的就是
     * 旧卡集、决策数的是新卡集，通知里那句「明天会堆到 N 项」和那句「有 M 条过了线」不再是
     * 同一批卡——而且一句都不报错。本仓在 `WordDao.observeScheduledMemoryRows` 的 KDoc 里
     * 为同一件事写过一次返工。
     *
     * 三条快照都从 `container.database` 直接取：[ForecastCards] 类 KDoc 点名的就是这三条查询，
     * 而书摘那条（`BookDao.observeScheduledExcerpts`）还没有仓储转发，三条一起走 DAO 比一半走
     * 仓储一半走库好读。
     *
     * ## 两个数只有一个来源
     * 目标保留率走 [MemoryScheduler.forSettings] 的 `Scheduling.targetRecall`（写死 0.9 等于
     * 在提醒侧另起一份排期政策），FSRS 参数走 `settings.fsrsActiveParams`，没校准过时才取发布默认。
     * 时钟只读一次：`today` 与 `dayStart` 都从同一个 [now] 推，免得跨过零点那一次调用里
     * 「今天」和「今天零点」不是同一天。
     */
    private suspend fun forecastDecision(
        container: AppContainer,
        settings: AppSettings,
        now: Long,
    ): ReminderDecision? {
        val zone = ZoneId.systemDefault()
        // 不用 LocalDate.ofInstant(...)——那是 Java 9 的静态方法，API 26 的 java.time 里没有
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val sched = MemoryScheduler.forSettings(
            strictness = settings.reviewStrictness,
            examEpochDay = settings.examEpochDay,
            today = today,
        )
        val w = settings.fsrsActiveParams ?: FsrsModel.DEFAULT_PARAMS
        // 「今天练过吗」只数词卡流水（口径见那条查询的 KDoc：练习与书摘检索不在 word_reviews 里）
        val todayReviewed = container.database.wordDao()
            .observeReviewTimestampsBetween(today.atStartOfDay(zone).toInstant().toEpochMilli(), now)
            .first()
            .isNotEmpty()
        val cards = ForecastCards.of(
            words = container.database.wordDao().observeScheduledMemoryRows().first(),
            mistakes = container.database.mistakeDao().observeUnmastered().first(),
            excerpts = container.database.bookDao().observeScheduledExcerpts().first(),
            now = now,
        ).toForecastCards()
        val bands = DueForecast.forecast(
            cards,
            ForecastCfg(
                horizonDays = FORECAST_HORIZON_DAYS,
                retention = sched.targetRecall,
                newPerDay = settings.dailyWordGoal,
                // learningPerNew 取 ForecastCfg 声明的默认值：这里重述一遍就多了个可能分叉的地方
            ),
            w = w,
        )
        return ReminderPolicy.decide(
            cards = cards,
            bands = bands,
            retention = sched.targetRecall,
            todayReviewed = todayReviewed,
            w = w,
        )
    }
}
