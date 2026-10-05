package com.studykit.data.memory

import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** 一张卡在预测起点上的可见状态。 */
data class ForecastCard(
    /** FSRS v6 稳定度（天）。非有限或 <=0 视为脏数据，被丢弃。 */
    val stabilityDays: Double,
    /** 距上次复习多少天。 */
    val elapsedDays: Double,
)

/** 负荷带预测的政策参数。 */
data class ForecastCfg(
    val horizonDays: Int = 14,
    /** 目标保留率，来自 `Scheduling.targetRecall`，**不要**在这里写死默认值——那会悄悄分叉调度政策。 */
    val retention: Double,
    /** 每日新词 intake（`AppSettings.dailyWordGoal`）。 */
    val newPerDay: Int = 20,
    /** 每张新词随后产生的学习步次数。 */
    val learningPerNew: Double = 2.5,
    /** 学习步 / 返工在「当天起 +0..+2」三天的摊派权重，和应为 1。 */
    val inflowW: DoubleArray = defaultInflow(),
)

/**
 * 摊派权重的默认值——用函数而非 `companion object val` 常量：`DoubleArray` 可变，
 * 常量默认值会被 `data class copy()` 与原构造共享成**同一个引用**，任一调用方原地
 * 改写就污染全局默认。每次调用返回新数组，改不坏。
 */
private fun defaultInflow() = doubleArrayOf(0.5, 0.3, 0.2)

/** 窗口里某一天：期望复习数与它的标准差。 */
data class DayBand(val dayIndex: Int, val mean: Double, val sd: Double)

/**
 * 把「已排期卡片的未来轨迹」前推成 N 天负荷带。
 *
 * ## 为什么不吃落库的到期时刻（`Word.nextReviewAt`）
 * 落库的到期时刻只反映**上一次评分**排出来的结果；用户今天多评一轮，后面十几天会整体重排。
 * 所以这里由 `S` 与目标保留率**反解**出「还有几天到期」，再叠上已流逝天数：
 * `remain = intervalDays(w, S, R) - elapsed`。这样「今天没练」会如实变成「明天堆积」。
 * 也正因为如此，[ForecastCard] 不预留 `dueAt` 字段——留着就是穿过整条计划的死数据。
 *
 * ## 三件质量来源
 * 1. **到期本事件**：`mean[d0] += 1`，确定性 → 不贡献方差。
 * 2. **返工**：忘了（概率 `1-R`）的卡会在之后 `+1..+3` 天再回来，按 `inflowW` 摊开；
 *    这一项是伯努利，故同时累加 `p(1-p)·w²` 到方差。
 * 3. **新词学习步**：每天进 `newPerDay` 张，每张产生 `learningPerNew` 次，摊到引入日起 `+0..+2`。
 *    窗口末尾落出窗外的尾部**不强计**（宁缺不假，与出窗卡同一口径），故窗内和可小于名义总量。
 *
 * 出窗（`round(remain) >= horizon`）的卡**直接跳过**——把它们堆到最后一格会凭空造一个假峰。
 * 脏 `S`（非有限 / <=0）丢弃：宁缺不假。
 */
object DueForecast {

    /** 反解上限：给足，让夹取只由调用方的政策上限决定，而不是被 365 天兜底截断。 */
    private const val REVERSE_CAP_DAYS = 3650.0

    /** 给定 S 与目标保留率，反解「上次复习后第几天到期」。与调度器同一个函数，口径不分叉。 */
    fun intervalDays(w: DoubleArray, stabilityDays: Double, retention: Double): Double =
        FsrsModel.nextIntervalDays(w, stabilityDays, retention, REVERSE_CAP_DAYS)

    fun forecast(cards: List<ForecastCard>, cfg: ForecastCfg, w: DoubleArray = FsrsModel.DEFAULT_PARAMS): List<DayBand> {
        val n = cfg.horizonDays.coerceAtLeast(1)
        val mean = DoubleArray(n)
        val varSum = DoubleArray(n)

        // 循环不变量：返工概率只由 retention 决定，提到卡片循环外算一次。
        val p = (1.0 - cfg.retention).coerceIn(0.0, 1.0)
        for (card in cards) {
            if (!card.stabilityDays.isFinite() || card.stabilityDays <= 0.0) continue
            val remain = max(0.0, intervalDays(w, card.stabilityDays, cfg.retention) - card.elapsedDays)
            val d0 = remain.roundToInt()
            if (d0 >= n) continue
            mean[d0] += 1.0

            for (k in cfg.inflowW.indices) {
                val d = d0 + 1 + k
                if (d >= n) break
                val weight = cfg.inflowW[k]
                mean[d] += p * weight
                varSum[d] += p * (1.0 - p) * weight * weight
            }
        }

        // 循环不变量：每日流入的学习步总量与新词张数、每张步数有关，与引入日无关。
        val intake = cfg.newPerDay * cfg.learningPerNew
        for (intro in 0 until n) {
            for (k in cfg.inflowW.indices) {
                val d = intro + k
                if (d >= n) break
                mean[d] += intake * cfg.inflowW[k]
            }
        }

        return (0 until n).map { DayBand(it, mean[it], sqrt(max(0.0, varSum[it]))) }
    }
}
