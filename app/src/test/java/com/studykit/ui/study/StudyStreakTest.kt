package com.studykit.ui.study

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * 「连续学习天数」纯逻辑单元测试：只喂时间戳 + 时区 + 今天，不依赖 Android / Room。
 * 时间戳来源（复习 + 刷题）由 DAO/Repository 负责，这里只验证日历语义。
 */
class StudyStreakTest {
    private val zone = ZoneOffset.UTC
    private fun ts(iso: String) = LocalDate.parse(iso).atStartOfDay(zone).toInstant().toEpochMilli()
    private val today = LocalDate.parse("2026-09-18")

    @Test fun `空数据 streak 为 0`() {
        assertEquals(0, StudyStreak.streakDays(emptyList(), zone, today))
    }
    @Test fun `今天与昨天学习连续计数`() {
        assertEquals(2, StudyStreak.streakDays(listOf(ts("2026-09-18"), ts("2026-09-17")), zone, today))
    }
    @Test fun `今天没学但昨天学了从昨天起算`() {
        assertEquals(1, StudyStreak.streakDays(listOf(ts("2026-09-17")), zone, today))
    }
    @Test fun `中间断档清零`() {
        assertEquals(0, StudyStreak.streakDays(listOf(ts("2026-09-15")), zone, today))
    }
    @Test fun `同一天多次只算一天`() {
        // 两条不同毫秒（00:00 与 13:00 同属 09-18）：若去重回归成「按时间戳条数计」会得 2，
        // 相同毫秒传两次则无法判别
        assertEquals(
            1,
            StudyStreak.streakDays(
                timestampsMs = listOf(ts("2026-09-18"), ts("2026-09-18") + 13 * 3600_000),
                zone = zone,
                today = today,
            ),
        )
    }

    // ── 以下为 brief 5 例之外的边界补充 ─────────────────────────────────

    @Test fun `跨月连续不断档`() {
        // 今天 10-01，昨天 09-30、前天 09-29：跨月不能被当成断档
        assertEquals(
            3,
            StudyStreak.streakDays(
                listOf(ts("2026-10-01"), ts("2026-09-30"), ts("2026-09-29")),
                zone,
                LocalDate.parse("2026-10-01"),
            ),
        )
    }

    @Test fun `乱序时间戳不影响结果`() {
        assertEquals(
            3,
            StudyStreak.streakDays(
                listOf(ts("2026-09-16"), ts("2026-09-18"), ts("2026-09-17")),
                zone,
                today,
            ),
        )
    }

    @Test fun `未来时间戳不计入`() {
        // 时钟漂移/补录产生的未来日期既不算今天，也不能把断档续上
        assertEquals(
            2,
            StudyStreak.streakDays(
                listOf(ts("2026-12-25"), ts("2026-09-18"), ts("2026-09-17")),
                zone,
                today,
            ),
        )
    }

    @Test fun `自然日按传入时区切分`() {
        val shanghai = ZoneId.of("Asia/Shanghai") // UTC+8，无夏令时，结果可预测
        // 2026-09-17T23:00Z → 上海已是 09-18；2026-09-16T01:00Z → 上海仍是 09-16
        val stamps = listOf(
            Instant.parse("2026-09-17T23:00:00Z").toEpochMilli(),
            Instant.parse("2026-09-16T01:00:00Z").toEpochMilli(),
        )
        // 上海：活跃日 {09-18, 09-16}，今天连续 1 天后断档
        assertEquals(1, StudyStreak.streakDays(stamps, shanghai, today))
        // UTC：活跃日 {09-17, 09-16}，今天没学 → 从昨天起算连续 2 天
        assertEquals(2, StudyStreak.streakDays(stamps, zone, today))
    }
}
