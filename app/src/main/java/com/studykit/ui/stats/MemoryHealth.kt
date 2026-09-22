package com.studykit.ui.stats

import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.pow

/** 一个持久度档位：半衰期 ≥ [minHalfLifeDays] 天 */
data class DurabilityBucket(val minHalfLifeDays: Int, val count: Int, val sharePercent: Int)

/** 某一天的复习负载 */
data class DayLoad(val date: LocalDate, val count: Int)

/** 一次真实复习：当时距上次隔了 [gapDays] 天，结果记起来了没有 */
data class GapSample(val gapDays: Double, val recalled: Boolean)

/**
 * 一个间隔桶的实测回忆率。
 *
 * [observedRecall] 为 null 表示**这个桶一条样本都没有** —— 必须与"0% 回忆率"区分开，
 * 否则新用户的曲线会被画成"你全忘了"，那是凭空造出来的坏消息。
 */
data class CurvePoint(
    val label: String,
    val midGapDays: Double,
    val observedRecall: Double?,
    val sampleCount: Int,
)

/**
 * 记忆看板的算法层。**纯函数、零 Android / 零 Room**，全部能在 JVM 单测里跑。
 *
 * 为什么单独一层：这三张图一旦算错，用户看到的是"我的记忆变差了"这种情绪结论，
 * 而不是一个能当场发现的报错。所以数字必须在没有真机、没有 CI 装机的情况下也能钉住。
 */
object MemoryHealth {

    /** 持久度档位。与墨墨同口径（≥10/30/60/90 天），便于用户横向理解 */
    val DurabilityThresholds = listOf(10, 30, 60, 90)

    /** 间隔分桶：标签 + 下界（含）+ 上界（不含） */
    val GapBuckets: List<Triple<String, Double, Double>> = listOf(
        Triple("1 天内", 0.0, 1.0),
        Triple("1-3 天", 1.0, 3.0),
        Triple("3-7 天", 3.0, 7.0),
        Triple("7-30 天", 7.0, 30.0),
        Triple("30 天以上", 30.0, Double.MAX_VALUE),
    )

    /**
     * 艾宾浩斯 1885 那条经典曲线（保留量 %，横轴天）。
     *
     * 用**通行的教科书数值**而不是重新拟合：这张图的意义是"你 vs 那条人人都听过的线"，
     * 换成另一组数字就没有参照物了。原始实验用的是"节省法"、且被后世反复重述，
     * 所以它只当**对照基准**，不当事实 —— 界面上也这么写。
     */
    val EbbinghausPoints: List<Pair<Double, Double>> = listOf(
        0.0 to 1.00,
        0.0069 to 0.58,   // 20 分钟
        0.0417 to 0.44,   // 1 小时
        0.375 to 0.36,    // 9 小时
        1.0 to 0.33,      // 1 天
        2.0 to 0.28,
        6.0 to 0.25,
        7.0 to 0.24,
        9.0 to 0.23,
        31.0 to 0.21,
    )

    /** 可信半衰期：正有限数。NaN / 0 / 负数一律不算，也不进分母 */
    private fun usable(h: Double): Boolean = h.isFinite() && h > 0.0

    /**
     * 持久度分布。分母是**全部可信的词**（不是各桶之和），
     * 所以四档是嵌套的 ≥ 关系，读法是"有百分之多少的词能扛过 X 天"。
     */
    fun durability(
        halfLives: List<Double>,
        thresholds: List<Int> = DurabilityThresholds,
    ): List<DurabilityBucket> {
        val valid = halfLives.filter(::usable)
        val total = valid.size
        return thresholds.map { t ->
            val c = valid.count { it >= t }
            DurabilityBucket(
                minHalfLifeDays = t,
                count = c,
                sharePercent = if (total == 0) 0 else Math.round(c * 100.0 / total).toInt(),
            )
        }
    }

    /**
     * 从 [from] 起连续 [days] 天的复习负载。
     *
     * 按**本地日**切，不用 SQL 的 `date()`：那个不吃时区，跨零点或换时区的用户
     * 会看到"明天的量"莫名多一个少一个。[from] 当天也算（用户关心"今天还剩多少"）。
     */
    fun forecastByDay(
        timestamps: List<Long>,
        zone: ZoneId,
        from: LocalDate,
        days: Int,
    ): List<DayLoad> {
        val starts = (0 until days)
            .map { from.plusDays(it.toLong()).atStartOfDay(zone).toInstant().toEpochMilli() }
        val ends = starts.drop(1) + Long.MAX_VALUE
        return (0 until days).map { i ->
            DayLoad(
                date = from.plusDays(i.toLong()),
                count = timestamps.count { it >= starts[i] && it < ends[i] },
            )
        }
    }

    /**
     * 用**用户自己的复习记录**分桶算实测回忆率。
     *
     * 空桶给 null 而不是 0.0（见 [CurvePoint]）；落在所有桶之外（gap ≤ 0 或非法）的样本
     * 直接不计，不猜进最近的桶。
     */
    fun forgettingCurve(samples: List<GapSample>): List<CurvePoint> {
        val usableSamples = samples.filter { it.gapDays.isFinite() && it.gapDays > 0.0 }
        return GapBuckets.map { (label, lo, hi) ->
            val inBucket = usableSamples.filter { it.gapDays >= lo && it.gapDays < hi }
            CurvePoint(
                label = label,
                // 「30 天以上」没有上界，取 60 天作代表值（图横轴会被夹住，只影响点位）
                midGapDays = if (hi == Double.MAX_VALUE) lo * 2.0 else (lo + hi) / 2.0,
                observedRecall = if (inBucket.isEmpty()) {
                    null
                } else {
                    inBucket.count { it.recalled }.toDouble() / inBucket.size
                },
                sampleCount = inBucket.size,
            )
        }
    }

    /** 模型自己的曲线：每个可信半衰期取 2^(−d/h)，再对全库求平均 */
    fun modelCurve(halfLives: List<Double>, atDays: List<Int>): List<Double> {
        val valid = halfLives.filter(::usable)
        if (valid.isEmpty()) return atDays.map { 0.0 }
        return atDays.map { d -> valid.sumOf { 2.0.pow(-d / it) } / valid.size }
    }
}
