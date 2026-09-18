package com.studykit.ui.study

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 连续学习天数：复习与刷题时间戳合并，从今天（或昨天，若今天未学）向前连数。
 *
 * 纯 JVM 逻辑，便于单测；时区与「今天」由调用方注入，避免隐藏的系统时钟依赖。
 * 语义要点：
 * - 时间戳先按 [zone] 折算成自然日，同一天多次学习只算一天；
 * - 今天没学习但昨天学了 → 从昨天起算，不算断档；
 * - 只要今天与昨天都没有学习记录，立即为 0；未来时间戳（时钟漂移/补录）不参与连数。
 */
object StudyStreak {
    fun streakDays(timestampsMs: List<Long>, zone: ZoneId, today: LocalDate): Int {
        if (timestampsMs.isEmpty()) return 0
        val days = timestampsMs.map { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }.toHashSet()
        var cursor = if (days.contains(today)) today else today.minusDays(1)
        if (!days.contains(cursor)) return 0
        var count = 0
        while (days.contains(cursor)) {
            count++
            cursor = cursor.minusDays(1)
        }
        return count
    }
}
