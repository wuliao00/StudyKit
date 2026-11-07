package com.studykit.util.signin

import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

/** 签到日历里的一格：日期 + 当天是否已签到。 */
data class SignInDay(val date: LocalDate, val signed: Boolean, val makeup: Boolean = false)

/** 整月日历的一格；[date] 为 null 表示月初/月末的占位格。 */
data class SignInMonthCell(
    val date: LocalDate?,
    val signed: Boolean,
    val today: Boolean,
    val makeup: Boolean = false,
)

/** 一个月的签到网格：固定 6 行 x 7 列，行序从周一开始，便于底部弹层高度稳定。 */
data class SignInMonth(val yearMonth: YearMonth, val cells: List<SignInMonthCell>)

/** 某个月的签到进度：当前月按“已过天数”做分母，历史月按整月天数。 */
data class SignInMonthStats(val signedCount: Int, val dayCount: Int) {
    val percent: Int get() = if (dayCount <= 0) 0 else (signedCount * 100 / dayCount)
}

/**
 * 首页签到卡的一屏读数。
 *
 * - [signedToday]：今天是否已经签到；
 * - [currentStreak]：截至今天/昨天的连续签到天数（今天还没签到时保留昨天的连数，避免一觉醒来归零）；
 * - [longestStreak]：历史最长连续签到；
 * - [last14]：最近 14 天，按时间从旧到新，最后一项是今天；
 * - [signedDays]：全量签到日期（供整月日历翻页）；
 * - [weekDays]：本周周一到周日（未来日期 signed=false）；
 * - [weekSignedCount]：本周已签到的天数（只数到今天）。
 */
data class SignInSummary(
    val signedToday: Boolean = false,
    val currentStreak: Int = 0,
    val longestStreak: Int = 0,
    val totalDays: Int = 0,
    val last14: List<SignInDay> = emptyList(),
    val signedDays: Set<LocalDate> = emptySet(),
    val makeupDays: Set<LocalDate> = emptySet(),
    val weekDays: List<SignInDay> = emptyList(),
    val weekSignedCount: Int = 0,
)

/**
 * 签到统计纯逻辑：只吃 ISO 日期字符串（Room 里存 yyyy-MM-dd），不碰系统时钟。
 *
 * 语义：
 * - 今天签到 -> 从今天往前数；
 * - 今天未签到、昨天签到 -> 从昨天往前数（首页显示“已连续 N 天，今天签到续上”）；
 * - 今天和昨天都没有 -> 0；
 * - 未来日期（时钟漂移/补录）直接忽略，不算进连数和累计。
 */
object SignInStats {

    const val WINDOW_DAYS = 14

    /** 连续签到里程碑；到点当天由 ViewModel 发一次事件，首页弹金色提示。 */
    val MILESTONES = listOf(3, 7, 14, 30, 66)

    private val ISO = DateTimeFormatter.ISO_LOCAL_DATE

    fun summary(
        days: Collection<String>,
        today: LocalDate,
        makeupDays: Collection<String> = emptyList(),
    ): SignInSummary {
        val signedDays = days
            .mapNotNull { parse(it) }
            .filter { !it.isAfter(today) }
            .toSet()
        val makeupSet = makeupDays
            .mapNotNull { parse(it) }
            .filter { !it.isAfter(today) }
            .toSet()
        val weekDays = weekDays(today, signedDays, makeupSet)
        return SignInSummary(
            signedToday = signedDays.contains(today),
            currentStreak = currentStreak(signedDays, today),
            longestStreak = longestStreak(signedDays),
            totalDays = signedDays.size,
            last14 = (WINDOW_DAYS - 1 downTo 0).map { offset ->
                val date = today.minusDays(offset.toLong())
                SignInDay(
                    date = date,
                    signed = signedDays.contains(date),
                    makeup = makeupSet.contains(date),
                )
            },
            signedDays = signedDays,
            makeupDays = makeupSet,
            weekDays = weekDays,
            weekSignedCount = weekDays.count { it.signed && !it.date.isAfter(today) },
        )
    }

    fun currentStreak(signedDays: Set<LocalDate>, today: LocalDate): Int {
        var cursor = if (signedDays.contains(today)) today else today.minusDays(1)
        if (!signedDays.contains(cursor)) return 0
        var count = 0
        while (signedDays.contains(cursor)) {
            count++
            cursor = cursor.minusDays(1)
        }
        return count
    }

    fun longestStreak(signedDays: Set<LocalDate>): Int {
        if (signedDays.isEmpty()) return 0
        val sorted = signedDays.sorted()
        var best = 1
        var run = 1
        for (i in 1 until sorted.size) {
            run = if (sorted[i - 1].plusDays(1) == sorted[i]) run + 1 else 1
            if (run > best) best = run
        }
        return best
    }

    /** 本周（周一为第一天）7 天；未来日期的 signed 永远为 false。 */
    fun weekDays(
        today: LocalDate,
        signedDays: Set<LocalDate>,
        makeupDays: Set<LocalDate> = emptySet(),
    ): List<SignInDay> {
        val monday = today.minusDays((today.dayOfWeek.value - 1).toLong())
        return (0..6).map { offset ->
            val date = monday.plusDays(offset.toLong())
            SignInDay(
                date = date,
                signed = !date.isAfter(today) && signedDays.contains(date),
                makeup = !date.isAfter(today) && makeupDays.contains(date),
            )
        }
    }

    /**
     * 整月网格：周一开始，月初补空、月末补到 42 格（6 行），保证底部弹层翻月时高度不跳。
     * 未来日期不涂签到色（历史库理论上不会出现，防御脏数据）。
     */
    fun monthGrid(
        month: YearMonth,
        signedDays: Set<LocalDate>,
        today: LocalDate,
        makeupDays: Set<LocalDate> = emptySet(),
    ): SignInMonth {
        val first = month.atDay(1)
        val leading = first.dayOfWeek.value - 1
        val cells = ArrayList<SignInMonthCell>(42)
        repeat(leading) { cells.add(SignInMonthCell(date = null, signed = false, today = false)) }
        for (day in 1..month.lengthOfMonth()) {
            val date = month.atDay(day)
            cells.add(
                SignInMonthCell(
                    date = date,
                    signed = signedDays.contains(date),
                    today = date == today,
                    makeup = makeupDays.contains(date),
                ),
            )
        }
        while (cells.size < 42) cells.add(SignInMonthCell(date = null, signed = false, today = false))
        return SignInMonth(yearMonth = month, cells = cells)
    }

    /** 某个月已签到天数 / 分母（当前月只算到今天，历史月按整月）。 */
    fun monthStats(month: YearMonth, signedDays: Set<LocalDate>, today: LocalDate): SignInMonthStats {
        val dayCount = if (month == YearMonth.from(today)) today.dayOfMonth else month.lengthOfMonth()
        val signedCount = (1..month.lengthOfMonth()).count { signedDays.contains(month.atDay(it)) }
        return SignInMonthStats(signedCount = signedCount, dayCount = dayCount)
    }

    /** 已达到的里程碑（由长到短/由小到大按 [MILESTONES] 顺序返回）。 */
    fun reachedMilestones(longestStreak: Int): List<Int> = MILESTONES.filter { longestStreak >= it }

    /** 补签窗口：最近 [windowDays] 天内的过去日期；今天/未来不可补签。 */
    fun canMakeUp(date: LocalDate, today: LocalDate, windowDays: Int = 3): Boolean {
        if (!date.isBefore(today)) return false
        return !date.isBefore(today.minusDays(windowDays.toLong()))
    }

    /** 连续天数恰好命中里程碑时返回该天数，否则 null。 */
    fun milestoneFor(streak: Int): Int? = if (MILESTONES.contains(streak)) streak else null

    private fun parse(raw: String): LocalDate? = try {
        LocalDate.parse(raw, ISO)
    } catch (e: Exception) {
        null
    }
}
