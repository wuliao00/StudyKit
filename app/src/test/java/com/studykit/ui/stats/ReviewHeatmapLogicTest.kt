package com.studykit.ui.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * 复习热力图算法层（v3.0-A Task 8）的单测：纯 JVM、无 runner、不碰 Room。
 *
 * ## 钉死的四件事
 * 1. **格数 = 周数 × 7**（少了格子等于悄悄抹掉几天，用户看不出来）。
 * 2. **未来日是占位格，不是"零复习"**：今天之后的日子画成灰格，读起来就是"你这几天没练"，
 *    而那根本还没发生。占位由 `date == null` + [ReviewHeatmapLogic.LEVEL_FUTURE] 两重表达，
 *    与 [com.studykit.ui.habit.buildHeatmapCells] 的"未来的日子还没有事实可画 → null"同一套约定。
 * 3. **两路同日相加**：词卡流水与练习流水落在同一天时次数求和，不是取最大值、也不是只数一路。
 * 4. **等级来自非零日的分位数**，不是写死的阈值：同一个 20 次，在"你平时 100 次/天"和
 *    "你平时 20 次/天"两种历史下必须是深浅不同的两格。
 *
 * 另外钉两条退化路径：全零历史不许除零、也不许"整片到顶档"；分位数只按**非零日**算
 * （把 350 个零天算进分母，色阶会被稀释成"练得最多的那几天才稍微有点颜色"）。
 *
 * `today` 与所有时刻都是喂进来的，本文件不读时钟、也不依赖本机时区（用 [ZoneOffset.UTC]）。
 */
class ReviewHeatmapLogicTest {

    private val zone: ZoneOffset = ZoneOffset.UTC

    /** 2026-01-15 是**周四**，所以本周还剩周五/周六/周日三天是未来日 */
    private val today: LocalDate = LocalDate.of(2026, 1, 15)

    private fun at(day: LocalDate, hour: Int = 9): Long =
        day.atStartOfDay(zone).plusHours(hour.toLong()).toInstant().toEpochMilli()

    private fun stampDay(day: LocalDate, times: Int, firstHour: Int = 9): List<Long> =
        (0 until times).map { at(day, firstHour + it) }

    // ---- ① 格数 ----

    @Test
    fun `格子数等于周数乘七`() {
        assertEquals(52 * 7, ReviewHeatmapLogic.cells(emptyList(), today, 52, zone).size)
        assertEquals(20 * 7, ReviewHeatmapLogic.cells(emptyList(), today, 20, zone).size)
    }

    /** 格数跟着 `weeks` 走，而不是写死 364——上一行换成 20 周才会露出"写死"的马脚 */
    @Test
    fun `首格是窗口第一周的周一，末三格是本周还没到的三天`() {
        val cells = ReviewHeatmapLogic.cells(emptyList(), today, 52, zone)
        // 手算：today=2026-01-15（周四）所在周的周一是 2026-01-12，再往前 51 周 = 2025-01-20
        assertEquals(LocalDate.of(2025, 1, 20), cells.first().date)
        assertEquals(1, cells.first().date!!.dayOfWeek.value)
        assertEquals(today, cells[cells.size - 4].date)
        assertEquals(3, cells.count { it.date == null })
    }

    // ---- ② 未来日 ----

    /**
     * brief 给的样例断言 `cells.filter { it.date != null && it.date.isAfter(today) }` 是**恒真**的：
     * 未来日的 date 本来就是 null（沿用 [com.studykit.ui.habit.buildHeatmapCells] 的约定），
     * filter 出来是空集合。这里改成钉住"哪几格是占位"，让它真的能红。
     */
    @Test
    fun `未来日是占位格而不是零复习`() {
        val cells = ReviewHeatmapLogic.cells(emptyList(), today, 52, zone)
        val placeholders = cells.filter { it.date == null }
        // 周四是 today → 本列剩 周五/周六/周日 三格
        assertEquals(3, placeholders.size)
        assertTrue(placeholders.all { it.level == ReviewHeatmapLogic.LEVEL_FUTURE })
        // 占位格只可能在末尾：中间冒出 null 就是网格错位了
        assertEquals(cells.subList(cells.size - 3, cells.size), placeholders)
        // 有日期的格子永远不是占位档
        assertTrue(cells.none { it.date != null && it.level == ReviewHeatmapLogic.LEVEL_FUTURE })
        // 今天**不是**占位格：它已经过完了，零复习要如实画成灰格
        assertEquals(0, cells.last { it.date == today }.level)
    }

    /** 未来日即使有时间戳（时钟回拨/脏数据）也不该被画成有颜色的一格。 */
    @Test
    fun `未来的时间戳不会把占位格涂成有数据`() {
        val cells = ReviewHeatmapLogic.cells(stampDay(today.plusDays(1), 5), today, 52, zone)
        assertEquals(ReviewHeatmapLogic.LEVEL_FUTURE, cells.last().level)
    }

    // ---- ③ 两路同日相加 ----

    @Test
    fun `两路同日相加而不是取最大`() {
        val day = today.minusDays(1)
        val counts = ReviewHeatmapLogic.dayCounts(
            stampDay(day, 2) + stampDay(day, 3, firstHour = 15),
            zone,
        )
        assertEquals(1, counts.size)
        assertEquals(5, counts[day])
    }

    /**
     * 端到端那一条：1 月 14 日 4 次词 + 4 次练习（合计 8），1 月 13 日 8 次词。
     * 若练习那一路没被算进来，14 日只有 4 次，色阶会比 13 日浅一档；取最大值也一样浅。
     * 所以断言"两格同为最深"能同时钉住相加与两路都读。
     */
    @Test
    fun `练习那一路没算进来时色阶会浅一档`() {
        val d14 = today.minusDays(1)
        val d13 = today.minusDays(2)
        val words = stampDay(d14, 4) + stampDay(d13, 8)
        val practice = stampDay(d14, 4)
        val cells = ReviewHeatmapLogic.cells(words + practice, today, 52, zone)
        assertEquals(8, ReviewHeatmapLogic.dayCounts(words + practice, zone)[d14])
        assertEquals(ReviewHeatmapLogic.MAX_LEVEL, cells.first { it.date == d14 }.level)
        assertEquals(ReviewHeatmapLogic.MAX_LEVEL, cells.first { it.date == d13 }.level)
        // 对照：只数词卡那一路时 14 日确实更浅——这条断言证明上一条不是恒真
        assertEquals(
            ReviewHeatmapLogic.MAX_LEVEL - 2,
            ReviewHeatmapLogic.cells(words, today, 52, zone).first { it.date == d14 }.level,
        )
    }

    // ---- ④ 等级来自非零日分位数 ----

    /** 同一个 20 次：基准 100 时是很浅的一档，基准 20 时才是顶档。写死阈值不可能有这个行为。 */
    @Test
    fun `同一次数在不同分位基准下深浅不同`() {
        assertEquals(1, ReviewHeatmapLogic.level(count = 20, p90 = 100.0))
        assertEquals(4, ReviewHeatmapLogic.level(count = 20, p90 = 20.0))
        assertEquals(4, ReviewHeatmapLogic.level(count = 999, p90 = 10.0))
    }

    @Test
    fun `零次永远是第零档`() {
        assertEquals(0, ReviewHeatmapLogic.level(count = 0, p90 = 10.0))
        assertEquals(0, ReviewHeatmapLogic.level(count = 0, p90 = 0.0))
    }

    /** 分位数只按**非零日**算：350 个零天混进分母会把色阶整体压平。 */
    @Test
    fun `分位数按非零日算，零日不进分母`() {
        val counts = List(350) { 0 } + (1..15).toList()
        // nearest-rank：ceil(0.9×15)=14 → 第 14 小的非零日 = 14
        assertEquals(14.0, ReviewHeatmapLogic.nonZeroP90(counts), 0.0)
    }

    /** 全零历史：基准退化为 1.0（不是 0，除零），而每一格都是第零档（不是顶档）。 */
    @Test
    fun `全零历史不除零也不整片到顶档`() {
        assertEquals(1.0, ReviewHeatmapLogic.nonZeroP90(List(364) { 0 }), 0.0)
        val cells = ReviewHeatmapLogic.cells(emptyList(), today, 52, zone)
        assertEquals(0, cells.count { it.level > 0 })
        assertEquals(0, cells.count { it.level == ReviewHeatmapLogic.MAX_LEVEL })
    }

    /** 分位基准被调用方传坏（0 / NaN / 无穷）时降到第 1 档：既不崩，也不"全图最深"。 */
    @Test
    fun `坏的分位基准降级为最浅可见档`() {
        assertEquals(1, ReviewHeatmapLogic.level(count = 5, p90 = 0.0))
        assertEquals(1, ReviewHeatmapLogic.level(count = 5, p90 = Double.NaN))
        assertEquals(1, ReviewHeatmapLogic.level(count = 5, p90 = Double.POSITIVE_INFINITY))
    }

    /** 窗口外的旧账既不进格子，也不稀释色阶（分位数只按窗口内的天算）。 */
    @Test
    fun `窗口外的旧数据不改变窗口内的深浅`() {
        val recent = today.minusDays(3)
        val withOld = stampDay(recent, 4) + stampDay(today.minusYears(3), 200)
        val without = stampDay(recent, 4)
        assertEquals(
            ReviewHeatmapLogic.cells(without, today, 52, zone),
            ReviewHeatmapLogic.cells(withOld, today, 52, zone),
        )
    }

    @Test
    fun `脏时间戳不进任何格子`() {
        val day = today.minusDays(1)
        // 0 / 负数落到 1970 年之前，天然在窗口外：格子数与"只有那一天有复习"一致
        val cells = ReviewHeatmapLogic.cells(listOf(0L, -1L) + stampDay(day, 2), today, 52, zone)
        assertEquals(1, cells.count { it.level > 0 })
        assertEquals(day, cells.first { it.level > 0 }.date)
    }

    /** 同一条流水，UTC 与东九区切出来的**不是同一天**——格子按调用方给的时区切，不是硬按 UTC。 */
    @Test
    fun `格子按调用方给的时区切日`() {
        // 2026-01-14 23:30 UTC：东九区已经是 1 月 15 日
        val epoch = LocalDateTime.of(2026, 1, 14, 23, 30).toInstant(ZoneOffset.UTC).toEpochMilli()
        val utc = ReviewHeatmapLogic.cells(listOf(epoch), today, 52, ZoneOffset.UTC)
        val plus9 = ReviewHeatmapLogic.cells(listOf(epoch), today, 52, ZoneOffset.ofHours(9))
        assertEquals(LocalDate.of(2026, 1, 14), utc.first { it.level > 0 }.date)
        assertEquals(LocalDate.of(2026, 1, 15), plus9.first { it.level > 0 }.date)
    }

    @Test
    fun `默认周数是一年`() {
        assertEquals(52, ReviewHeatmapLogic.DEFAULT_WEEKS)
        assertEquals(364, ReviewHeatmapLogic.cells(emptyList(), today, zone = zone).size)
    }

    /** `weeks <= 0` 时网格是空的，而不是抛——视图那边钳不钳都不会崩。 */
    @Test
    fun `零周给出空网格而不是抛`() {
        assertEquals(emptyList<Any>(), ReviewHeatmapLogic.cells(emptyList(), today, 0, zone))
    }
}
