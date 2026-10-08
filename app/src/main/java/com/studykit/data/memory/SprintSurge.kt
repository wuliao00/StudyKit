package com.studykit.data.memory

/**
 * 考前涌量预警（v3.0-B，文档 L1891 / L1895）。
 *
 * 文档点名的是「WorkloadSimulator 峰值/中位 >2」。本仓没有那个类，但它存在等价物：
 * v3.0-A 的 [DueForecast.forecast] 就是那台模拟器（负荷带 = 每天要还的卡数分布）。
 * 所以这里只做一个纯读数函数：Task 7 拿**冲刺保留率**把同一批关联学科的卡**重跑一遍**负荷带，
 * 把那一份 bands 喂进来。**只跑这一遍** —— 没有第二份"自然排期"的预测作对照，闸门那边比的只是
 * 两个目标保留率（见 `ui/stats/ForecastCards.kt` 的 `sprintSurgeFor`）。因此 [peakMedianRatio]
 * 说的是**同一次冲刺排期自己**挤出来的尖峰，不是"比不冲刺时多了 N 倍"。
 *
 * 零 Android、零时钟、零 Room。
 */
object SprintSurge {

    /** 报警阈值：文档给的是「>2」，严格大于（恰好 2 倍不吵） */
    const val SURGE_RATIO = 2.0

    /**
     * 峰值 / 中位数。<2 个格子、或中位数为 0（库里几乎没东西可还）时返回 null ——
     * 那种时候算出来的"倍数"是 0 除法产物或无意义的无穷，报出去反而是在骗人。
     */
    fun peakMedianRatio(bands: List<DayBand>): Double? {
        if (bands.size < 2) return null
        val means = bands.map { it.mean }.sorted()
        val median = medianOf(means)
        if (median <= 0.0) return null
        return means.max() / median
    }

    /**
     * null 走"没有读数"这一支，**不**当"没有涌量"报警：算不出来的时候屏幕上那句
     * 「峰值约是中位的 N 倍」根本无从落笔，硬凑一个 0 进去是在编数字。
     */
    fun shouldWarn(ratio: Double?): Boolean = ratio != null && ratio > SURGE_RATIO

    /** 屏幕上那句话（文档 L1895 的说明 + 实测倍数）；调用方已确认 ratio 非 null */
    fun warningLine(ratio: Double): String =
        "考前这段时间，这科卡片的目标保留率被提到冲刺值，复习量会涨：" +
            "峰值约是中位的 ${"%.1f".format(ratio)} 倍，曲线里能看到涌量。"

    private fun medianOf(sorted: List<Double>): Double {
        val n = sorted.size
        return if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0
    }
}
