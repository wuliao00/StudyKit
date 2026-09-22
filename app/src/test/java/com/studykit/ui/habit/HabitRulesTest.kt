package com.studykit.ui.habit

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 打卡规则三件套的纯函数：日界归属与时段窗口。
 *
 * 这两个函数错了都是**静默**的：日期记错一天，账面上永远看不出来是规则错了还是用户真没打；
 * 窗口锁死，用户只会觉得"这个 app 坏了"。所以边界全部钉死。
 */
class HabitRulesTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    private fun at(h: Int, min: Int = 0): Instant =
        LocalDate.of(2026, 9, 22).atTime(h, min).atZone(zone).toInstant()

    // ── 日界归属 ────────────────────────────────────────────────

    @Test
    fun `zero boundary never shifts the day`() {
        assertEquals(LocalDate.of(2026, 9, 22), effectiveCheckInDate(at(0, 30), zone, 0))
        assertEquals(LocalDate.of(2026, 9, 22), effectiveCheckInDate(at(23, 59), zone, 0))
    }

    /** 熬夜场景：boundary=3 时，凌晨 1 点的打卡算**前一天** —— 这正是这个功能存在的理由 */
    @Test
    fun `early morning belongs to the previous day under a boundary`() {
        assertEquals(LocalDate.of(2026, 9, 21), effectiveCheckInDate(at(0, 30), zone, 3))
        assertEquals(LocalDate.of(2026, 9, 21), effectiveCheckInDate(at(2, 59), zone, 3))
        assertEquals(LocalDate.of(2026, 9, 22), effectiveCheckInDate(at(3, 0), zone, 3))
        assertEquals(LocalDate.of(2026, 9, 22), effectiveCheckInDate(at(5, 0), zone, 3))
    }

    @Test
    fun `boundary hour is clamped`() {
        // 配置写坏了（比如 25）也不许把日子挪到下个月去
        assertEquals(LocalDate.of(2026, 9, 21), effectiveCheckInDate(at(1, 0), zone, 25))
        assertEquals(LocalDate.of(2026, 9, 22), effectiveCheckInDate(at(1, 0), zone, -3))
    }

    // ── 时段窗口 ────────────────────────────────────────────────

    @Test
    fun `normal window is inclusive at start and exclusive at end`() {
        assertTrue(isWithinCheckInWindow(8 * 60, 8 * 60, 22 * 60))
        assertTrue(isWithinCheckInWindow(21 * 60 + 59, 8 * 60, 22 * 60))
        assertFalse(isWithinCheckInWindow(22 * 60, 8 * 60, 22 * 60))
        assertFalse(isWithinCheckInWindow(7 * 60 + 59, 8 * 60, 22 * 60))
    }

    /** 22:00–06:00 这种跨零点窗口：两头都算"在内"，下午算在外 */
    @Test
    fun `window wrapping midnight covers both ends`() {
        assertTrue(isWithinCheckInWindow(23 * 60, 22 * 60, 6 * 60))
        assertTrue(isWithinCheckInWindow(5 * 60, 22 * 60, 6 * 60))
        assertFalse(isWithinCheckInWindow(12 * 60, 22 * 60, 6 * 60))
    }

    /** start == end 是手滑写错时的兜底：视为不限制，绝不把用户永久锁在门外 */
    @Test
    fun `degenerate window means unrestricted`() {
        assertTrue(isWithinCheckInWindow(0, 8 * 60, 8 * 60))
        assertTrue(isWithinCheckInWindow(23 * 60 + 59, 8 * 60, 8 * 60))
    }

    @Test
    fun `out of range minutes are clamped before comparing`() {
        assertTrue(isWithinCheckInWindow(-5, 0, 24 * 60))
        assertTrue(isWithinCheckInWindow(2000, 0, 24 * 60))
    }
}
