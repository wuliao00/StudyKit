package com.studykit.ui.stats

import com.studykit.ui.habit.buildHeatmapCells
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 热力图的一格。
 *
 * [date] 为 `null` 表示这一格落在**今天之后**（末周残缺），没有事实可画；此时 [level] 是
 * [ReviewHeatmapLogic.LEVEL_FUTURE]，视图画一枚更淡的占位格，**不画成"零复习"的灰格**——
 * 把还没发生的日子画成空，读起来就是"你这几天没坚持"，而那句话没有依据。
 * 日期为 null 这重表达沿用 [com.studykit.ui.habit.buildHeatmapCells] 的同一套约定，
 * 两幅热力图对"未来"的说法因此不会分叉。
 */
data class HeatCell(val date: LocalDate?, val level: Int)

/**
 * 复习热力图（v3.0-A Task 8）的算法层。**纯函数、零 Android / 零 Room**，与 [MemoryHealth]、
 * [StabilityBins] 同一层。
 *
 * ## 这张图只数两路：单词复习与题目练习
 * 原料是 `word_reviews.reviewed_at` 与 `practice_records.at`。**书摘侧没有"每次复习"的流水表**——
 * `excerpts` 只有 `next_review_at` / `review_count` / `stability` 三个当前态列（每次复习是当场由
 * [com.studykit.data.memory.ExcerptReview] 算的，不落行），`book_reviews` 那是**书评**（rating + 正文），
 * 不是复习记录。所以这张图**不是**全部学习行为，视图的 caption 必须照这么写，不许说成"你所有的学习"。
 *
 * ## 网格：列 = 周（旧 → 新）、行 = 周一..周日
 * 日期对齐全仓唯一那一份实现 [com.studykit.ui.habit.buildHeatmapCells]：首列起于「today 所在周的周一」
 * 再往前 `weeks - 1` 周，所以首列永远是完整的一周，残缺只可能落在末列今天之后。
 * 自己再写一遍这段对齐，两幅图就会在某个改动之后各切各的周。
 *
 * ## 色阶是**相对的**，不是写死阈值
 * [level] 的基准是窗口内**非零日**的 90 分位（[nonZeroP90]，nearest-rank）。同一个"20 次/天"，
 * 在"平时 100 次"的历史里是很浅的一档、在"平时 20 次"的历史里才是顶档——写死阈值给不出这个行为，
 * 而写死的另一头是：练得少的人整片浅色，看起来像没在学。
 * 退化路径两条都钉着：全零历史基准取 1.0（不除零，且因为没有非零日而**全图第零档**，不到顶）；
 * 调用方把基准传坏（0 / NaN / 无穷）时一律降到第 1 档，既不崩也不"全图最深"。
 * 相对刻度有一个如实的代价：**非零日全为同值时它们都到顶档**（"和你自己最忙的日子一样"），
 * 这不是 bug，但读图的人需要知道深浅只跟自己比。
 *
 * ## 窗口外的数据既不进格子也不稀释色阶
 * 分位数只按窗口内的天算，所以三年前那波突击不会把这一年的格子压平。脏时间戳（0 / 负数）
 * 落在 1970 年，天然在窗口外，不需要额外判据。
 *
 * ## 不读时钟、不读时区
 * `today` 与 `zone` 都由调用方喂进来（[MemoryHealth.forecastByDay] 同一条理由：按本地日切才谈得上
 * 时区，SQL 的 `date()` 不吃时区）。
 */
object ReviewHeatmapLogic {

    /** 默认窗口：一年（52 列 × 7 行） */
    const val DEFAULT_WEEKS = 52

    /** 占位档：今天之后，没有事实可画（与"零复习"的 [LEVEL_NONE] 必须分得开） */
    const val LEVEL_FUTURE = -1

    /** 有日期但那天一次都没有 */
    const val LEVEL_NONE = 0

    /** 最深的一档；[LEVEL_NONE] + 1..MAX_LEVEL 是四个强度档 */
    const val MAX_LEVEL = 4

    /** 非零日的分位取第几位 */
    private const val QUANTILE = 0.9

    /**
     * 逐本地日计数。**两路时间戳由调用方拼成一个列表传进来**（词的 `reviewed_at` + 练习的 `at`），
     * 落在同一天就相加——不是取最大值，也不是后一路覆盖前一路。
     *
     * 返回的是**全部**有记录的日期，包括窗口之外的（本函数不知道窗口，窗口是 [cells] 的事）；
     * 这样"两路同日相加"这件事有一个能被单独钉住的地方，而不是埋在格子循环里。
     */
    fun dayCounts(timestamps: List<Long>, zone: ZoneId): Map<LocalDate, Int> =
        timestamps.groupingBy { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }.eachCount()

    /**
     * 窗口内**非零日**的 90 分位（nearest-rank：第 `ceil(0.9 × n)` 小的非零值）。
     *
     * 零日不进分母：把 350 个零天一起排进去，90 分位就落在 0 上，色阶会被稀释成
     * "只有最忙的那几天才稍微有点颜色"。全零时返回 1.0 而不是 0.0——基准为 0 会让 [level] 除零。
     */
    fun nonZeroP90(counts: Collection<Int>): Double {
        val sorted = counts.filter { it > 0 }.sorted()
        if (sorted.isEmpty()) return 1.0
        val rank = Math.ceil(sorted.size * QUANTILE).toInt().coerceIn(1, sorted.size)
        return sorted[rank - 1].toDouble().coerceAtLeast(1.0)
    }

    /**
     * 一次数 → 强度档。`count == 0` 恒为 [LEVEL_NONE]；其余按 `count / p90` 均分四档，
     * 达到或超过基准就是顶档。
     *
     * 基准不可用（非有限 / 小于 1）时**降到第 1 档而不是顶档**：宁可让整片格子都浅浅地"有颜色"，
     * 也不要画出一幅"天天都是最忙"的图——后者是凭空造出来的好消息，跟凭空造坏消息一样糟。
     */
    fun level(count: Int, p90: Double): Int {
        if (count <= 0) return LEVEL_NONE
        if (!p90.isFinite() || p90 < 1.0) return LEVEL_NONE + 1
        return (1 + ((count / p90) * (MAX_LEVEL - 1)).toInt()).coerceAtMost(MAX_LEVEL)
    }

    /**
     * 一年 × 七天 = `weeks * 7` 个格子，按 [com.studykit.ui.habit.buildHeatmapCells] 的列序
     * （第 0 列最旧，每列周一→周日）摊平。
     *
     * @param timestamps 两路时间戳**拼好**的列表（见 [dayCounts]：同日相加）
     * @param weeks 往回看多少周；`<= 0` 退化为空网格（不抛），与
     *   [com.studykit.ui.habit.buildHeatmapCells] 同一个边界行为
     */
    fun cells(
        timestamps: List<Long>,
        today: LocalDate,
        weeks: Int = DEFAULT_WEEKS,
        zone: ZoneId,
    ): List<HeatCell> {
        val counts = dayCounts(timestamps, zone)
        val dates = buildHeatmapCells(today, weeks).flatten()
        // 每格那天窗口内的次数（没有记录就是 0；未来格先占 0，稍后被整格换成占位档）
        val perDay = dates.map { date -> date?.let { counts[it] } ?: 0 }
        val p90 = nonZeroP90(perDay)
        return dates.mapIndexed { i, date ->
            // 先判 date：未来格连"次数"都不成立，让它去比色阶就是把没发生的事画成没坚持
            if (date == null) HeatCell(null, LEVEL_FUTURE) else HeatCell(date, level(perDay[i], p90))
        }
    }
}
