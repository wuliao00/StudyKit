package com.studykit.ui.study

import com.studykit.data.dao.ScheduledMemoryRow
import com.studykit.data.memory.MemoryModel
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * 明天那一格的两个数：词数，以及这批词各自到期时的平均预测回忆概率。
 *
 * [wordCount] 与 [sampledCount] 必须相等 —— 它们分别是"这一行说有几个词"和
 * "那个百分比是把几个词平均出来的"。相等是"两个数说的是同一批词"唯一的证据，
 * 所以这里把它做成数据的一部分而不是注释里的一句约定：
 * [tomorrowLine] 只在不相等时**宁可少半句**（不说 X%），也不肯把两批词的数字并排印出来。
 */
data class TomorrowLoad(
    val wordCount: Int,
    val sampledCount: Int,
    /** 0~100。[wordCount] 为 0 时这个数没有意义，别拿去显示 */
    val predictedRecallPercent: Int,
)

/**
 * 「明天预计复习 N 词 · 到时候大约还记得 X%」的算法层。纯函数、零 Android / 零 Room，
 * 与 [StudyStreak]、`MemoryHealth` 同一层理由：这行数字错了不会报错，
 * 只会让用户对着一个没意义的百分比做今天的决定。
 */
object TomorrowForecast {

    private val ONE_DAY_MS: Long = TimeUnit.DAYS.toMillis(1)

    /**
     * 「明天」这个区间：`[明天 0 点, 后天 0 点)`，按**本地日**切。
     *
     * 这就是首页那一格一直在用的口径（改动前是就地写的
     * `today.plusDays(1).atStartOfDay(zone) until today.plusDays(2).atStartOfDay(zone)`），
     * 这里只是把它收成一处，让词数与保留率结构上不可能各自算一遍。
     * 日切也刻意留在 Kotlin 而不是 SQL 的 `date()` 里：那个不吃时区，
     * 跨零点或用 DST 的机器会让"明天的量"莫名多/少一天
     * （见 `WordDao.observeScheduledTimestamps` 的注释）。
     *
     * 用 `atStartOfDay(zone)` 而不是 `plusDays(1)` 再减 24 小时：DST 那两天真是 23/25 小时。
     *
     * ## 为什么不套 `AppSettings.dayBoundaryHour`（"一天从几点开始"）
     * 设计文档 §2.3 写着这一格含那个边界，但**代码里的 `tomorrowCount` 从来不含**：
     * 它写的是打卡（KDoc：凌晨 0~4 点打卡算**前一天**，唯一消费点在 `HabitViewModel`），
     * 默认 0 点。往前推同一把边界会让"明天 0 点到边界点之间"到期那批词**同时**掉出
     * 这一格和今日待办（它们那时还没到期，进不了 `dueCount`），变成首页上谁也看不见的
     * 一小批债 —— 那比按自然日切更糟。所以这里保持现状口径，只把边界收成一处。
     */
    fun window(zone: ZoneId, today: LocalDate): LongRange =
        today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() until
            today.plusDays(2).atStartOfDay(zone).toInstant().toEpochMilli()

    /**
     * 一次算出那一行的两个数。
     *
     * @param rows 全部已排期的词（[com.studykit.data.dao.WordDao.observeScheduledMemoryRows]
     *             那一条 SQL 的结果，别传两次查询拼出来的列表）
     * @param range [window] 算出来的那一个区间 —— 词数和保留率都从同一个 `range` 里筛出的
     *              同一批行来算，所以这一行上的两个数不会各说各话。
     *
     * 逐条 `recallProbability(gapDays = (到期时刻 − 锚点)/天, halfLifeDays)` 再取**算术平均**：
     * 平均的是概率而不是半衰期，因为要给用户看的正是"平均有多少把握"。
     *
     * 两点口径说明：
     * - 分母是这批词的**全部**词数，不挑掉 h 不可信的行。`MemoryModel.recallProbability`
     *   对 h ≤ 0 / NaN 已经回 0（"当作全忘了"），把它踢出分母反而会让 X% 与 N 数的不是同一批。
     * - 锚点用 [ScheduledMemoryRow.anchorAt]（`lastReviewAt ?: createdAt`），
     *   与 `StudyViewModel.gradeCard` 同一个口径，别让从没复习过的词算出 Δt=0 ⇒ 100%。
     */
    fun of(rows: List<ScheduledMemoryRow>, range: LongRange): TomorrowLoad {
        val tomorrow = rows.filter { it.nextReviewAt in range }
        val probabilities = tomorrow.map { row ->
            val gapDays = (row.nextReviewAt - row.anchorAt).coerceAtLeast(0L) / ONE_DAY_MS.toDouble()
            MemoryModel.recallProbability(gapDays = gapDays, halfLifeDays = row.halfLifeDays)
        }
        val average = if (probabilities.isEmpty()) 0.0 else probabilities.sum() / probabilities.size
        return TomorrowLoad(
            wordCount = tomorrow.size,
            sampledCount = probabilities.size,
            predictedRecallPercent = (average * 100).roundToInt(),
        )
    }
}

/**
 * 首页那一行的文案（纯函数，为的是能被单测钉住）。
 *
 * N=0 时保留原句「明天没有排期」：没排期却说"记得 0%"是废话（v2.5 §2.3）。
 * N>0 但两个数不同批（[TomorrowLoad.sampledCount] 对不上）时只报词数，不报百分比 ——
 * 少半句永远好过半句编错。
 */
internal fun tomorrowLine(tomorrow: TomorrowLoad): String {
    if (tomorrow.wordCount <= 0) return "明天没有排期"
    if (tomorrow.sampledCount != tomorrow.wordCount) return "明天预计复习 ${tomorrow.wordCount} 词"
    return "明天预计复习 ${tomorrow.wordCount} 词 · 到时候大约还记得 ${tomorrow.predictedRecallPercent}%"
}
