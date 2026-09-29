package com.studykit.ui.study

import com.studykit.data.dao.ScheduledMemoryRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * 学习首页「明天预计复习 N 词 · 到时候大约还记得 X%」那一格的算法。
 *
 * 为什么单独钉这一格：它是那种**算错了不会报错**的数字。N 和 X% 一旦数的是两批词，
 * 界面照常渲染，用户却对着一句没意义的话决定今天背多少 —— 所以每个样例都手算过一遍。
 */
class TomorrowForecastTest {

    /**
     * 用 [ZoneOffset]（它本身就是一个 `ZoneId`）而不是 `ZoneId.of(...)`：
     * `LocalDateTime.toInstant` 只收 `ZoneOffset`，夹具里少一次转换就少一处编译期踩雷。
     */
    private val utc: ZoneOffset = ZoneOffset.UTC

    private val today = LocalDate.of(2026, 9, 29)
    private val tomorrow = LocalDate.of(2026, 9, 30)
    private val dayAfter = LocalDate.of(2026, 10, 1)

    private fun stamp(date: LocalDate, hour: Int = 0, minute: Int = 0): Long =
        date.atTime(hour, minute).toInstant(utc).toEpochMilli()

    private val DAY_MS = 24L * 60 * 60 * 1000

    /**
     * 造一条已排期的词：[reviewedDaysBeforeDue] 是"到期时刻往前多少天复习的"，
     * 传 null 表示从没复习过（锚点退回 [createdDaysBeforeDue] 那天）。
     */
    private fun row(
        dueAt: Long,
        halfLifeDays: Double,
        reviewedDaysBeforeDue: Double? = 0.0,
        createdDaysBeforeDue: Double = 0.0,
    ): ScheduledMemoryRow {
        val anchorFromDays = reviewedDaysBeforeDue ?: createdDaysBeforeDue
        return ScheduledMemoryRow(
            nextReviewAt = dueAt,
            halfLifeDays = halfLifeDays,
            lastReviewAt = reviewedDaysBeforeDue?.let { dueAt - (it * DAY_MS).toLong() },
            createdAt = dueAt - (anchorFromDays * DAY_MS).toLong(),
        )
    }

    // ── 保留率：手算对齐 ─────────────────────────────────────────

    /**
     * 三个词的算术平均：
     * ① Δt=1 天、h=1 天 → 2^(−1) = 0.5
     * ② Δt=2 天、h=2 天 → 2^(−1) = 0.5
     * ③ Δt=0（到期时刻就是锚点）→ 1.0
     * 平均 = 2/3 → 67%。
     *
     * 另外四条**故意**放在窗口外（今天 01:00、今天 23:59、后天 00:00、后天 23:00），
     * 而且全是 p≈0 的坏数据（h=0.02 天 ≈ 29 分钟）：
     * 要是哪天有人把窗口写松了，平均值会被拖到 40% 上下 —— 这一条断言当场就红，
     * 而不是留着一行"看起来也挺合理"的错数字。
     */
    @Test
    fun predicted_recall_is_the_average_of_each_words_probability_at_its_own_due_time() {
        val rows = listOf(
            row(dueAt = stamp(tomorrow, 8, 0), halfLifeDays = 1.0, reviewedDaysBeforeDue = 1.0),
            row(dueAt = stamp(tomorrow, 12, 0), halfLifeDays = 2.0, reviewedDaysBeforeDue = 2.0),
            row(dueAt = stamp(tomorrow, 20, 0), halfLifeDays = 1.0, reviewedDaysBeforeDue = 0.0),
            // 窗口外：今天 23:59、后天 00:00、以及更远的两天
            row(dueAt = stamp(today, 23, 59), halfLifeDays = 0.02, reviewedDaysBeforeDue = 30.0),
            row(dueAt = stamp(dayAfter, 0, 0), halfLifeDays = 0.02, reviewedDaysBeforeDue = 30.0),
            row(dueAt = stamp(dayAfter, 23, 0), halfLifeDays = 0.02, reviewedDaysBeforeDue = 30.0),
            row(dueAt = stamp(today, 1, 0), halfLifeDays = 0.02, reviewedDaysBeforeDue = 30.0),
        )
        val load = TomorrowForecast.of(rows, TomorrowForecast.window(utc, today))
        assertEquals(3, load.wordCount)
        assertEquals(3, load.sampledCount)
        assertEquals(67, load.predictedRecallPercent)
    }

    // ── 这一条才是本次改动最容易悄悄做错的地方 ────────────────────

    /**
     * **N 和 X% 数的是同一批词**（v2.5 §2.3 的硬要求）。
     *
     * 夹具里埋了两种"会被悄悄踢出平均"的行：`halfLifeDays = 0.0`（模型对它回 0，
     * 但任何一句 `filter { it.halfLifeDays > 0 }` 都会把它从分母里摘掉）和
     * `lastReviewAt = null`（任何一句 `mapNotNull` 同理）。两种都必须留在批里，
     * 所以 [TomorrowLoad.sampledCount] 必须等于 [TomorrowLoad.wordCount]，
     * 而 wordCount 必须等于独立写一遍的窗口过滤结果。三处一起钉，
     * 谁把它们拆开算就红在这里。
     */
    @Test
    fun the_batch_behind_the_percentage_is_the_same_batch_n_counts() {
        val rows = listOf(
            row(dueAt = stamp(tomorrow, 6, 0), halfLifeDays = 2.0, reviewedDaysBeforeDue = 2.0),   // 0.5
            row(dueAt = stamp(tomorrow, 9, 0), halfLifeDays = 0.0, reviewedDaysBeforeDue = 1.0),   // 脏 h → 0
            row(dueAt = stamp(tomorrow, 12, 0), halfLifeDays = 1.0, reviewedDaysBeforeDue = null,  // 没复习过
                createdDaysBeforeDue = 1.0),                                                        // → 0.5
            row(dueAt = stamp(tomorrow, 23, 59), halfLifeDays = 2.0, reviewedDaysBeforeDue = 4.0), // 0.25
            row(dueAt = stamp(today, 12, 0), halfLifeDays = 5.0, reviewedDaysBeforeDue = 1.0),      // 窗口外
            row(dueAt = stamp(dayAfter, 6, 0), halfLifeDays = 5.0, reviewedDaysBeforeDue = 1.0),    // 窗口外
        )
        val window = TomorrowForecast.window(utc, today)
        val load = TomorrowForecast.of(rows, window)

        // ① 词数就是"落在明天那一格里的行数"，独立算一遍对照
        assertEquals(rows.count { it.nextReviewAt in window }, load.wordCount)
        // ② 求平均用的行数 = 那一格的行数（这条是重点）
        assertEquals(load.wordCount, load.sampledCount)
        assertEquals(4, load.wordCount)
        // ③ 而且这 4 个词的平均确实算出来了：(0.5 + 0 + 0.5 + 0.25) / 4 = 31.25 → 31
        assertEquals(31, load.predictedRecallPercent)
    }

    // ── 「明天」这个区间 ─────────────────────────────────────────

    /** 左闭右开：明天 0 点算明天，后天 0 点已经不算 */
    @Test
    fun window_is_local_tomorrow_and_excludes_both_neighbouring_midnights() {
        val window = TomorrowForecast.window(utc, today)
        assertEquals(stamp(tomorrow), window.first)
        assertEquals(stamp(dayAfter) - 1, window.last)
        assertFalse(stamp(tomorrow) - 1 in window)
        assertTrue(stamp(tomorrow) in window)
        assertTrue(stamp(dayAfter) - 1 in window)
        assertFalse(stamp(dayAfter) in window)
    }

    /**
     * 边界必须跟着**本地日**，不是"现在 + 24 小时"：柏林 2026-03-29 那天只有 23 小时
     * （夏令时从 02:00 跳到 03:00）。写 `plusDays(1)` 再减 24 小时的话，
     * 这一格会多算一小时、把后天 00:00 前那一小时（其实已经是后天）当成明天。
     */
    @Test
    fun window_follows_local_midnight_across_a_dst_day() {
        val berlin = ZoneId.of("Europe/Berlin")
        val window = TomorrowForecast.window(berlin, LocalDate.of(2026, 3, 28))
        val startMillis = LocalDate.of(2026, 3, 29).atStartOfDay(berlin).toInstant().toEpochMilli()
        val endMillis = LocalDate.of(2026, 3, 30).atStartOfDay(berlin).toInstant().toEpochMilli()
        assertEquals(startMillis, window.first)
        assertEquals(endMillis - 1, window.last)
        assertEquals(23L * 60 * 60 * 1000, window.last - window.first + 1)
    }

    // ── Δt 的锚点 ────────────────────────────────────────────────

    /**
     * 从没复习过的词（迁移折算出来的老 MASTERED 词、演示数据都是这种）不许算成 Δt=0 ⇒ 100%。
     * 锚点退回"加入学习那天"，与 `StudyViewModel.gradeCard` 里 `lastReviewAt ?: createdAt` 同一口径：
     * 这里加入于 2 天前、排到明天 00:00 ⇒ Δt=2 天、h=2 ⇒ 2^(−1) = 50%。
     */
    @Test
    fun a_word_never_reviewed_uses_its_creation_day_as_the_anchor() {
        val rows = listOf(
            ScheduledMemoryRow(
                nextReviewAt = stamp(tomorrow),
                halfLifeDays = 2.0,
                lastReviewAt = null,
                createdAt = stamp(tomorrow) - 2 * DAY_MS,
            ),
        )
        val load = TomorrowForecast.of(rows, TomorrowForecast.window(utc, today))
        assertEquals(1, load.wordCount)
        assertEquals(50, load.predictedRecallPercent)
    }

    // ── 空的那一格 ──────────────────────────────────────────────

    @Test
    fun an_empty_day_counts_zero_words() {
        val rows = listOf(row(dueAt = stamp(today, 12, 0), halfLifeDays = 3.0, reviewedDaysBeforeDue = 1.0))
        val load = TomorrowForecast.of(rows, TomorrowForecast.window(utc, today))
        assertEquals(0, load.wordCount)
        assertEquals(0, load.sampledCount)
        assertEquals("明天没有排期", tomorrowLine(load))
    }

    // ── 文案 ────────────────────────────────────────────────────

    /** N>0 才补上保留率；N=0 时那句「明天没有排期」必须原样留着（没排期说"记得 0%"是废话） */
    @Test
    fun copy_line_reads_both_numbers_from_the_same_batch() {
        assertEquals(
            "明天预计复习 12 词 · 到时候大约还记得 67%",
            tomorrowLine(TomorrowLoad(wordCount = 12, sampledCount = 12, predictedRecallPercent = 67)),
        )
        assertEquals("明天没有排期", tomorrowLine(TomorrowLoad(wordCount = 0, sampledCount = 0, predictedRecallPercent = 0)))
    }

    /**
     * 两个数对不上时**宁可少半句**：只报词数，不报百分比。
     * 今天这条按构造不可能走到（两个数出自同一次过滤），留着是因为它把"不许把两批词的
     * 数字并排印出来"这条规矩写进了代码里 —— 以后有人改成两处各算一遍，这里会立刻生效，
     * 而不是等真机上被人问"12 个词怎么会记得 92%"。
     */
    @Test
    fun copy_line_drops_the_percentage_when_the_two_numbers_disagree() {
        val line = tomorrowLine(TomorrowLoad(wordCount = 12, sampledCount = 9, predictedRecallPercent = 92))
        assertEquals("明天预计复习 12 词", line)
        assertFalse(line, line.contains("%"))
    }
}
