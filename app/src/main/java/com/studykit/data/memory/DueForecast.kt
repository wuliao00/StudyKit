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
    val horizonDays: Int = HORIZON_DAYS,
    /** 目标保留率，来自 `Scheduling.targetRecall`，**不要**在这里写死默认值——那会悄悄分叉调度政策。 */
    val retention: Double,
    /**
     * 间隔上限（天），来自 `Scheduling.maxIntervalDays`，与 [retention] 同一条纪律：**必填**。
     *
     * 它由考试日期推（`MemoryModel.maxIntervalDaysFor`：无考试日 36 天、考前 20 天只有 4 天），
     * 而调度器排每张卡时都套了这个上限。预测侧不套的话，凡是自然间隔超过上限的卡都会被带子
     * 排到**更晚**（甚至整个甩出 14 天窗），而 App 其实把它们排到了**更早**——考前那两周于是
     * 系统性地**少报**。同一份上限还喂给 [ReminderPolicy] 的堆积与到期两档，理由相同。
     */
    val maxIntervalDays: Double,
    /** 每日新词 intake（`AppSettings.dailyWordGoal`）。 */
    val newPerDay: Int = 20,
    /** 每张新词随后产生的学习步次数。 */
    val learningPerNew: Double = DEFAULT_LEARNING_PER_NEW,
    /** 学习步 / 返工在「当天起 +0..+2」三天的摊派权重，和应为 1。 */
    val inflowW: DoubleArray = defaultInflow(),
) {
    companion object {
        /**
         * 前推窗口天数：**看板的负荷带与提醒侧的堆积判断共用这一个数**。
         *
         * 放在这里而不是各自写一份字面量，是因为这两处读的是同一段未来（`StatsViewModel` 画的那条带、
         * `ReminderWorker` 那句「后天会堆到 N 项」）。窗口分叉时两边都照常工作、不报错，只是用户看到
         * 的不再是同一次预测。上一轮就同时在两处写过 `14`，靠 KDoc 里一句「同值」维持——没人执行。
         */
        const val HORIZON_DAYS: Int = 14

        /**
         * 每张新词随后产生的学习步次数——[ForecastCfg.learningPerNew] 的默认值。
         *
         * 提到常量是因为**视图也要念它**（[com.studykit.ui.stats.LoadBandChart] 的图注）：写死在文案里的
         * 那个数字与预测实际用的那个数分叉时，图上就印着一句关于自己算法的假话。
         */
        const val DEFAULT_LEARNING_PER_NEW: Double = 2.5

        /**
         * 「没有政策上下文」时用的反解上限（天）：⛔ 它**不是** [ForecastCfg.maxIntervalDays] 的默认值，
         * 也不许出现在生产调用点——有考试日/严格度可问的调用点必须交 `Scheduling.maxIntervalDays`。
         *
         * 存在的理由只有两类：纯数学面（合成语料的精度门要的是反解恒等式，不是某份排期政策）与测试夹具。
         * 与 [com.studykit.ui.study.CardRecallCurve] 那份 `DEFAULT_MAX_INTERVAL_DAYS` 同值、同一条纪律
         * （那边是 T9 为了同一个理由把上限改成必填的）。
         */
        const val MAX_INTERVAL_DAYS_UNSCOPED: Double = 3650.0
    }
}

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
 * `remain = intervalDays(w, S, R, maxIntervalDays) - elapsed`。这样「今天没练」会如实变成「明天堆积」。
 * 也正因为如此，[ForecastCard] 不预留 `dueAt` 字段——留着就是穿过整条计划的死数据。
 *
 * ## 反解用的两个政策数都来自调度器，一个都不许自己定
 * [ForecastCfg.retention] 与 [ForecastCfg.maxIntervalDays] 都取自调度器排期的那一份 `Scheduling`
 * （v3.0-B 评审 2 起，看板与提醒两条路都由 `schedulingForSubject(subject = null)` 给；两者皆**必填**）。
 * 上限尤其容易被漏：`S=30` 的卡在考前 20 天只会被排到**第 4 天**
 * （上限 `max(3, 20×0.2)=4`），带子若按 30 天放它，就会把它排到更晚、甚至甩出窗外——
 * 于是负荷带与提醒在考前那两周**系统性少报**。这条纪律与 T9 把 `maxIntervalDays` 改成必填同源。
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

    /**
     * 给定 S 与目标保留率，反解「上次复习后第几天到期」。与调度器同一个函数、**同一个上限**，
     * 两头都不分叉：`maxIntervalDays` 交调度器那份 `Scheduling.maxIntervalDays`，
     * 带子才会把考前被压短的卡放在 App 真正排它们的那一天。
     */
    fun intervalDays(
        w: DoubleArray,
        stabilityDays: Double,
        retention: Double,
        maxIntervalDays: Double,
    ): Double = FsrsModel.nextIntervalDays(w, stabilityDays, retention, maxIntervalDays)

    fun forecast(cards: List<ForecastCard>, cfg: ForecastCfg, w: DoubleArray = FsrsModel.DEFAULT_PARAMS): List<DayBand> {
        val n = cfg.horizonDays.coerceAtLeast(1)
        val mean = DoubleArray(n)
        val varSum = DoubleArray(n)

        // 循环不变量：返工概率只由 retention 决定，提到卡片循环外算一次。
        val p = (1.0 - cfg.retention).coerceIn(0.0, 1.0)
        for (card in cards) {
            if (!card.stabilityDays.isFinite() || card.stabilityDays <= 0.0) continue
            val remain = max(
                0.0,
                intervalDays(w, card.stabilityDays, cfg.retention, cfg.maxIntervalDays) - card.elapsedDays,
            )
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
