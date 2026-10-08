package com.studykit.ui.study

import com.studykit.data.memory.ExamEntry
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 首页倒计时那一行（文档 L1894：`%1$s · 还剩 %2$d 天 · 冲刺保留 %3$.0f%%`）。
 *
 * 钉三条：① 没设考试 = null（页面那句都不该出现）；② 「考试已过 N 天」与「今天考试」的旧说法
 * 一字不改（v2.7 起就在屏幕上的话）；③ **只有真在 ramp 窗口内**才追加「冲刺保留」那截 ——
 * 窗口外印着它，是在告诉用户一件没发生的事。
 *
 * 期望值全部手算后写字面量：`0.80 + (0.95-0.80)*(1-10/30) = 0.90`、
 * `0.80 + (0.90-0.80)*(1-10/20) = 0.85`。不引用生产常量，也不调生产函数取期望。
 */
class ExamCountdownTest {

    private val today = LocalDate.of(2026, 10, 6)

    private fun exam(offset: Long, subjects: List<String> = emptyList(), sprint: Double = 0.95, window: Int = 30) =
        ExamEntry("期末考", today.plusDays(offset).toEpochDay(), subjects, sprint, window)

    @Test
    fun `没设考试时不给这一行`() {
        assertEquals(null, countdownLine(null, today, 0.80))
    }

    @Test
    fun `窗口外只说还剩几天`() {
        assertEquals("「期末考」 · 还剩 45 天", countdownLine(exam(45L, window = 30), today, 0.80))
    }

    @Test
    fun `窗口起点那条边界不印冲刺值`() {
        // left == window 时 ramp 给的是 base（Task 1 的 ⛔ 那条），此时印冲刺值是在说一件没发生的事
        assertEquals("「期末考」 · 还剩 30 天", countdownLine(exam(30L, window = 30), today, 0.80))
    }

    @Test
    fun `窗口内带上冲刺保留率`() {
        val line = countdownLine(exam(10L, sprint = 0.95, window = 30), today, 0.80)
        // 0.80 + (0.95-0.80)*(1-10/30) = 0.90
        assertEquals("「期末考」 · 还剩 10 天 · 冲刺保留 90%", line)
    }

    @Test
    fun `冲刺率取整后不带小数点`() {
        // 0.80 + (0.90-0.80)*(1-10/20) = 0.85，正好落在整数百分比上
        val line = countdownLine(exam(10L, sprint = 0.90, window = 20), today, 0.80)!!
        assertTrue(line, line.contains("冲刺保留 85%"))
        assertFalse(line, line.contains("."))
    }

    @Test
    fun `考试当天`() {
        assertEquals("今天考试", countdownLine(exam(0L), today, 0.80))
    }

    @Test
    fun `已过说已过`() {
        assertEquals("「期末考」 已过 3 天", countdownLine(exam(-3L), today, 0.80))
    }

    @Test
    fun `窗口为 0 的那场只剩今天，当天那句不掺冲刺字样`() {
        assertEquals("「期末考」 · 还剩 1 天", countdownLine(exam(1L, window = 0), today, 0.80))
        assertEquals("今天考试", countdownLine(exam(0L, window = 0), today, 0.80))
    }
}
