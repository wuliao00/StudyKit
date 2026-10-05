package com.studykit.data.memory

import kotlin.math.roundToInt

/** 提醒的三档紧迫度，优先级从高到低。 */
enum class AlertKind { RETENTION_DROP, LOAD_SPIKE, DUE_NOW }

/** 一条要落到通知上的决策。文案在这里定，Worker 只管发。 */
data class ReminderDecision(val kind: AlertKind, val headline: String, val body: String)

/**
 * 该不该提醒、提醒什么。三档优先级：**跌破线 > 堆积 > 到期**。
 *
 * 「跌破线」指某张卡的预测召回已经低于目标保留率再减 [DELTA]——这时候催的不是"到期了"，
 * 而是"你以为还记得，其实已经忘了"，它比任何排期信号都更该打断用户。注意它在**时间轴上晚于**
 * 到期线（0.9 先跌破，0.85 后跌破），所以它不是"更早的闹钟"而是"更重的病情"：档序按严重度排，
 * 不按日历排。
 * 「堆积」指明天的期望复习量**严格超过**其余天中位数的 [SPIKE_RATIO] 倍（文档 §负荷预测）；
 * 恰好等于 1.5 倍不算，中位数只取其余天（含离群日时不被它拉高）。
 *
 * 反解一律走 [DueForecast.intervalDays]，与调度器同一个函数：提醒口径和排期口径不能分叉，
 * 否则会出现"通知说有 3 张到期、App 里只列 2 张"。同理，本文件不写任何手搓的逆变换式，
 * 也不引入 FSRS-5 的 `DECAY/FACTOR` 常量。
 *
 * 纯 JVM 模块：不 import Android，这样它能直接被 JVM 单测覆盖（Task 4 才把它接到 Worker 上）。
 */
object ReminderPolicy {

    /** 跌破判定相对目标保留率的下探量。 */
    const val DELTA = 0.05

    /** 明天/其余天中位数 的倍数门槛。 */
    const val SPIKE_RATIO = 1.5

    /** 还有几天到期（负数 = 已经到期）。 */
    fun dueInDays(
        w: DoubleArray,
        stabilityDays: Double,
        retention: Double,
        elapsedDays: Double,
    ): Double = DueForecast.intervalDays(w, stabilityDays, retention) - elapsedDays

    /**
     * 还有几天跌破 `retention - DELTA`（负数 = 已经跌破）。
     *
     * 下探后的目标**不**在这里夹取下界：[DueForecast.intervalDays] 一路走到
     * `FsrsModel.nextIntervalDays`，那里已按本仓"脏值降级不异常"的口径把目标夹进 `[0.5, 0.99]`，
     * 再套一层 `coerceAtLeast` 是永不生效的死代码。
     */
    fun dropInDays(
        w: DoubleArray,
        stabilityDays: Double,
        retention: Double,
        elapsedDays: Double,
    ): Double = DueForecast.intervalDays(w, stabilityDays, retention - DELTA) - elapsedDays

    fun decide(
        cards: List<ForecastCard>,
        bands: List<DayBand>,
        retention: Double,
        todayReviewed: Boolean,
        w: DoubleArray = FsrsModel.DEFAULT_PARAMS,
    ): ReminderDecision? {
        if (todayReviewed) return null
        // 脏 S（非有限 / <=0）先丢弃：`intervalDays` 会给它们算出 0 天间隔，
        // 于是"跌破线已过"凭空成立，通知会催一批用户在 App 里根本找不到的卡。
        val usable = cards.filter { it.stabilityDays.isFinite() && it.stabilityDays > 0.0 }
        if (usable.isEmpty() && bands.none { it.mean > 0.0 }) return null

        val dropped = usable.count { dropInDays(w, it.stabilityDays, retention, it.elapsedDays) <= 0.0 }
        if (dropped > 0) {
            return ReminderDecision(
                AlertKind.RETENTION_DROP,
                "有 $dropped 条记忆已经过了线",
                "现在回想起来会吃力，补一次能把它拉回去。",
            )
        }

        spike(bands)?.let { (tomorrow, median) ->
            return ReminderDecision(
                AlertKind.LOAD_SPIKE,
                "明天会堆到 ${tomorrow.roundToInt()} 项",
                "今天先清一点，明天不至于被压住（平时约 ${median.roundToInt()} 项）。",
            )
        }

        val dueNow = usable.count { dueInDays(w, it.stabilityDays, retention, it.elapsedDays) <= 0.0 }
        if (dueNow > 0) {
            return ReminderDecision(
                AlertKind.DUE_NOW,
                "$dueNow 项今天到期",
                "按现在的状态，这些还来得及。",
            )
        }
        return null
    }

    /** 明天是否异常堆积；不异常返回 null（返回值 = 明天的量与其余天的中位数，两条都要进文案）。 */
    private fun spike(bands: List<DayBand>): Pair<Double, Double>? {
        if (bands.size < 3) return null
        val tomorrow = bands[1].mean
        val rest = bands.filterIndexed { i, _ -> i != 1 }.map { it.mean }.sorted()
        if (rest.isEmpty() || tomorrow <= 0.0) return null
        val median = if (rest.size % 2 == 1) rest[rest.size / 2] else (rest[rest.size / 2 - 1] + rest[rest.size / 2]) / 2.0
        return if (median > 0.0 && tomorrow > median * SPIKE_RATIO) tomorrow to median else null
    }
}
