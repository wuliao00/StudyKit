package com.studykit.ui.habit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 专注计时的纯逻辑（v2.4 批次三）。
 *
 * 这组算术错了全是静默的：早一秒完成、少记一分钟、出现负数倒计时，用户当场看不出来，
 * 账面上也只有"专注 N 分钟"一行字可对。所以边界全部钉死：递减、到点、不越界、封顶、脏数据。
 */
class FocusSessionTest {

    /** 起点：随便取的一个 elapsedRealtime 值，测试里只关心相对偏移 */
    private val start = 100_000L

    private fun session(minutes: Int): FocusSession =
        FocusSession(habitId = 1L, durationMinutes = minutes, startElapsedMs = start)

    // ── 剩余秒数随时间递减 ──────────────────────────────────────

    @Test
    fun `remaining seconds decrease as time passes`() {
        val s = session(25)
        assertEquals(25 * 60, s.remainingSeconds(start))
        assertEquals(25 * 60 - 1, s.remainingSeconds(start + 1_000L))
        assertEquals(25 * 60 - 61, s.remainingSeconds(start + 61_000L))
    }

    /** 秒级精度：不满一秒不扣秒（elapsedRealtime 是毫秒，只按整秒取） */
    @Test
    fun `partial second does not tick the clock`() {
        val s = session(25)
        assertEquals(25 * 60, s.remainingSeconds(start + 999L))
    }

    // ── 到点判定 ────────────────────────────────────────────────

    @Test
    fun `isComplete only at the deadline`() {
        val s = session(25)
        assertFalse(s.isComplete(start))
        assertFalse(s.isComplete(start + 24 * 60_000L + 59_999L))
        assertTrue(s.isComplete(start + 25 * 60_000L))
        assertTrue(s.isComplete(start + 26 * 60_000L))
    }

    // ── 永不出现负数 ────────────────────────────────────────────

    @Test
    fun `remaining never goes negative even far past the deadline`() {
        val s = session(15)
        assertEquals(0, s.remainingSeconds(start + 100 * 60_000L))
        assertEquals(0, s.remainingSeconds(start + 10 * 60 * 60_000L))
    }

    /** now 早于起点（时钟源被换过/乱序调用）按"还没开始"处理，也不许出负数 */
    @Test
    fun `now before start counts as not started`() {
        val s = session(15)
        assertEquals(15 * 60, s.remainingSeconds(start - 5_000L))
        assertEquals(0, s.elapsedMinutes(start - 5_000L))
    }

    // ── elapsedMinutes 封顶 ─────────────────────────────────────

    @Test
    fun `elapsed minutes floor to completed minutes and cap at duration`() {
        val s = session(25)
        assertEquals(0, s.elapsedMinutes(start))
        assertEquals(4, s.elapsedMinutes(start + 4 * 60_000L + 30_000L))
        assertEquals(25, s.elapsedMinutes(start + 25 * 60_000L))
        assertEquals(25, s.elapsedMinutes(start + 90 * 60_000L))
    }

    // ── 非法时长被钳制 ──────────────────────────────────────────

    /**
     * 选了**钳制**而不是拒绝（抛异常）：正常构造入口只有 PRESETS/DEFAULT_MINUTES，
     * 到不了非法值；这道防线防的是脏路由参数 —— 纯逻辑类在构造时崩掉应用，
     * 比把一场坏数据当 1 分钟处理代价大得多。
     */
    @Test
    fun `zero duration is clamped to a one minute session`() {
        val s = session(0)
        assertFalse(s.isComplete(start))
        assertEquals(60, s.remainingSeconds(start))
        assertTrue(s.isComplete(start + 60_000L))
        assertEquals(1, s.elapsedMinutes(start + 60_000L))
    }

    @Test
    fun `negative duration is clamped to a one minute session`() {
        val s = session(-7)
        assertEquals(60, s.remainingSeconds(start))
        assertTrue(s.isComplete(start + 60_000L))
        assertEquals(1, s.elapsedMinutes(start + 10 * 60_000L))
    }

    // ── 常量与设计文档一致 ──────────────────────────────────────

    @Test
    fun `presets and default match the design doc`() {
        assertEquals(listOf(15, 25, 45), FocusSession.PRESETS)
        assertEquals(25, FocusSession.DEFAULT_MINUTES)
    }
}
