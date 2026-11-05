package com.studykit.util.signin

import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** 签到日历里的一格：日期 + 当天是否已签到。 */
data class SignInDay(val date: LocalDate, val signed: Boolean)

/**
 * 首页签到卡的一屏读数。
 *
 * - [signedToday]：今天是否已经签到；
 * - [currentStreak]：截至今天/昨天的连续签到天数（今天还没签到时保留昨天的连数，避免一觉醒来归零）；
 * - [longestStreak]：历史最长连续签到；
 * - [last14]：最近 14 天，按时间从旧到新，最后一项是今天。
 */
data class SignInSummary(
    val signedToday: Boolean = false,
    val currentStreak: Int = 0,
    val longestStreak: Int = 0,
    val totalDays: Int = 0,
    val last14: List<SignInDay> = emptyList(),
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

    private val ISO = DateTimeFormatter.ISO_LOCAL_DATE

    fun summary(days: Collection<String>, today: LocalDate): SignInSummary {
        val signedDays = days
            .mapNotNull { parse(it) }
            .filter { !it.isAfter(today) }
            .toSet()
        return SignInSummary(
            signedToday = signedDays.contains(today),
            currentStreak = currentStreak(signedDays, today),
            longestStreak = longestStreak(signedDays),
            totalDays = signedDays.size,
            last14 = (WINDOW_DAYS - 1 downTo 0).map { offset ->
                val date = today.minusDays(offset.toLong())
                SignInDay(date = date, signed = signedDays.contains(date))
            },
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

    private fun parse(raw: String): LocalDate? = try {
        LocalDate.parse(raw, ISO)
    } catch (e: Exception) {
        null
    }
}
