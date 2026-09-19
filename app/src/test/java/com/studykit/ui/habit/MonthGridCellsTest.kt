package com.studykit.ui.habit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

/**
 * 月网格骨架（[padToFullWeeks] / [MonthGridCells] / [WeekHeader]）的纯逻辑单元测试。
 *
 * 契约只有一条：**无论哪个月、月初落在周几，网格恒为 [MonthGridCells] = 6 行 × 7 列**。
 * 两页日历（`HabitCalendarScreen` / `GlobalCalendarScreen`）靠它保证翻月时
 * `AnimatedContent` 的两帧等高，所以「补齐」这件事一旦被改坏，症状是转场收束那一帧
 * 卡片突然跳一行 —— 属真机才看得见的回归，正好该由单测钉死。
 *
 * 前导空位口径与页面一致：`month.atDay(1).dayOfWeek.value - 1`（周一 = 0 … 周日 = 6），
 * [monthDays] 就是两页里那两行构造的复刻。基准月挑真实日历上确实落在周日/周一的
 * 2026-03 与 2026-06，加上 28 天的 2026-02，全是**不读系统时钟**的定值。
 */
class MonthGridCellsTest {

    /** 复刻两页日历的网格输入：月初前导空位 + 当月 1..月末 */
    private fun monthDays(month: YearMonth): List<LocalDate?> {
        val leadingBlanks = month.atDay(1).dayOfWeek.value - 1
        return List(size = leadingBlanks) { null } +
            (1..month.lengthOfMonth()).map { month.atDay(it) }
    }

    /** 造一段长度确定、内容互不相同的日期序列，用来测纯格数边界 */
    private fun daysOf(size: Int): List<LocalDate?> =
        List(size = size) { LocalDate.of(2026, 1, 1).plusDays(it.toLong()) }

    private fun nullsOf(size: Int): List<LocalDate?> = List(size = size) { null }

    // ── 纯格数边界：满 42 / 35 / 28 / 超出 ────────────────────────────────

    @Test fun `满四十二格原样返回不增不减`() {
        val input = daysOf(size = 42)
        val padded = padToFullWeeks(days = input)
        assertEquals(MonthGridCells, padded.size)
        assertEquals(input, padded)
    }

    @Test fun `三十五格补到四十二且尾部七格为空`() {
        val input = daysOf(size = 35)
        val padded = padToFullWeeks(days = input)
        assertEquals(42, padded.size)
        assertEquals(input, padded.take(35))
        assertEquals(nullsOf(size = 7), padded.drop(35))
    }

    @Test fun `二十八格补到四十二且尾部十四格为空`() {
        val input = daysOf(size = 28)
        val padded = padToFullWeeks(days = input)
        assertEquals(42, padded.size)
        assertEquals(input, padded.take(28))
        assertEquals(14, padded.count { it == null })
    }

    @Test fun `超过四十二格也原样返回不裁切`() {
        // 真实月份最多 6 + 31 = 37 格走不到这里；钉住「宁多不裁」这一半契约：
        // 哪天窗口形状改了，默默吃掉格子比多出一行更难查
        val input = daysOf(size = 45)
        val padded = padToFullWeeks(days = input)
        assertEquals(45, padded.size)
        assertEquals(input, padded)
    }

    @Test fun `补齐是幂等的`() {
        val once = padToFullWeeks(days = daysOf(size = 30))
        assertEquals(once, padToFullWeeks(days = once))
    }

    // ── 真实月份的跨月首日前导空位 ────────────────────────────────────────

    @Test fun `首日落在周日时前导空位是六个`() {
        val march = YearMonth.of(2026, 3)   // 2026-03-01 = 周日，31 天
        assertEquals(6, march.atDay(1).dayOfWeek.value - 1)
        val padded = padToFullWeeks(days = monthDays(march))

        assertEquals(42, padded.size)
        assertTrue(padded.take(6).all { it == null })
        assertEquals(LocalDate.of(2026, 3, 1), padded[6])
        assertEquals(LocalDate.of(2026, 3, 31), padded[36])
        // 37 格 → 尾部补 5；整列的 null 是「6 个前导 + 5 个补位」= 11，别只数尾巴
        assertEquals(nullsOf(size = 5), padded.drop(37))
        assertEquals(11, padded.count { it == null })
        assertEquals(31, padded.count { it != null })
    }

    @Test fun `首日落在周一时没有任何前导空位`() {
        val june = YearMonth.of(2026, 6)    // 2026-06-01 = 周一，30 天
        assertEquals(0, june.atDay(1).dayOfWeek.value - 1)
        val padded = padToFullWeeks(days = monthDays(june))

        assertEquals(LocalDate.of(2026, 6, 1), padded.first())
        assertEquals(LocalDate.of(2026, 6, 30), padded[29])
        // 没有前导空位，这一枚才等于「尾部补了几格」
        assertEquals(12, padded.count { it == null })
        assertEquals(30, padded.count { it != null })
    }

    @Test fun `二十八天的二月同样补满六行`() {
        val february = YearMonth.of(2026, 2) // 2026-02-01 = 周日，28 天
        val padded = padToFullWeeks(days = monthDays(february))

        assertEquals(42, padded.size)
        assertEquals(6, padded.take(6).count { it == null })
        assertEquals(28, padded.count { it != null })
        // 34 格 → 尾部补 8；前导 6 + 补位 8 = 14 枚 null
        assertEquals(nullsOf(size = 8), padded.drop(34))
        assertEquals(14, padded.count { it == null })
    }

    @Test fun `任何月份的日期数都等于当月天数且顺序连续`() {
        listOf(
            YearMonth.of(2024, 2),   // 闰年 29 天
            YearMonth.of(2026, 2),   // 平年 28 天
            YearMonth.of(2026, 1),   // 31 天、首日落在周四
            YearMonth.of(2026, 12),  // 31 天、首日落在周二
        ).forEach { month ->
            val days = padToFullWeeks(days = monthDays(month)).filterNotNull()
            assertEquals(month.lengthOfMonth(), days.size)
            assertEquals(month.atDay(1), days.first())
            assertEquals(month.atEndOfMonth(), days.last())
        }
    }

    // ── 形状：恒为六行七格 ────────────────────────────────────────────────

    @Test fun `补齐后按七天一行恰好切出六行`() {
        listOf(YearMonth.of(2026, 2), YearMonth.of(2026, 3), YearMonth.of(2026, 6)).forEach { month ->
            val rows = padToFullWeeks(days = monthDays(month)).chunked(7)
            assertEquals(6, rows.size)
            rows.forEach { assertEquals(7, it.size) }
        }
    }

    @Test fun `表头七格与网格列数同源`() {
        assertEquals(7, WeekHeader.size)
        assertEquals(listOf("一", "二", "三", "四", "五", "六", "日"), WeekHeader)
        assertEquals(0, MonthGridCells % WeekHeader.size)
        assertEquals(6, MonthGridCells / WeekHeader.size)
    }

    @Test fun `空输入也拿满四十二格且全是空位`() {
        val padded = padToFullWeeks(days = emptyList())
        assertEquals(42, padded.size)
        assertTrue(padded.all { it == null })
    }

    @Test fun `只差一格也补到四十二`() {
        val padded = padToFullWeeks(days = daysOf(size = 41))
        assertEquals(MonthGridCells, padded.size)
        assertEquals(1, padded.count { it == null })
    }
}
