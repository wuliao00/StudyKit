package com.studykit.ui.study

import com.studykit.data.memory.ExamEntry
import com.studykit.data.memory.MemoryScheduler
import com.studykit.data.memory.ReviewStrictness
import java.time.LocalDate
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 首页倒计时那一行（文档 L1894：`%1$s · 还剩 %2$d 天 · 冲刺保留 %3$.0f%%`）。
 *
 * 钉四条（评审 1 之后 `countdownLine` 自取 base，旧签名的 `rBase = 0.80` 逐条译成
 * `strictness = RELAXED` + `exams = listOf(那场)`）：
 * ① 没设考试 = null（页面那句都不该出现）；
 * ② 「今天考试」五个字与 v2.7 逐字相同；已过那支自 v3.0-B 起是「name」 已过 N 天
 *    （带考试名与直角引号 —— 旧注释宣称整句"一字不改"与代码不符，评审 4 已订正说法）；
 * ③ **只有 ramp 真的抬过它**（窗内且 `effective > base`）才追加「冲刺保留」那截 ——
 *    窗口外、或 exam 绑了别的学科时印它，是在告诉用户一件没发生的事；
 * ④ 打印的那个百分数**就是调度器用的那个数**（`forSubject` 对照那条钉的是"两处不许分家"
 *    这件事本身，不是拿生产函数取期望；其余期望值仍是手算字面量：
 *    `0.80 + (0.95-0.80)*(1-10/30) = 0.90`、`0.80 + (0.90-0.80)*(1-10/20) = 0.85`）。
 */
class ExamCountdownTest {

    private val today = LocalDate.of(2026, 10, 6)

    private fun exam(offset: Long, subjects: List<String> = emptyList(), sprint: Double = 0.95, window: Int = 30) =
        ExamEntry("期末考", today.plusDays(offset).toEpochDay(), subjects, sprint, window)

    @Test
    fun `没设考试时不给这一行`() {
        assertEquals(null, countdownLine(null, today, ReviewStrictness.RELAXED, emptyList()))
    }

    @Test
    fun `窗口外只说还剩几天`() {
        val e = exam(45L, window = 30)
        assertEquals("「期末考」 · 还剩 45 天", countdownLine(e, today, ReviewStrictness.RELAXED, listOf(e)))
    }

    @Test
    fun `窗口起点那条边界不印冲刺值`() {
        // left == window 时 ramp 给的是 base（Task 1 的 ⛔ 那条），此时印冲刺值是在说一件没发生的事
        val e = exam(30L, window = 30)
        assertEquals("「期末考」 · 还剩 30 天", countdownLine(e, today, ReviewStrictness.RELAXED, listOf(e)))
    }

    @Test
    fun `窗口内带上冲刺保留率`() {
        val e = exam(10L, sprint = 0.95, window = 30)
        // RELAXED base=0.80：0.80 + (0.95-0.80)*(1-10/30) = 0.90
        assertEquals("「期末考」 · 还剩 10 天 · 冲刺保留 90%", countdownLine(e, today, ReviewStrictness.RELAXED, listOf(e)))
    }

    @Test
    fun `冲刺率取整后不带小数点`() {
        // 0.80 + (0.90-0.80)*(1-10/20) = 0.85，正好落在整数百分比上
        val e = exam(10L, sprint = 0.90, window = 20)
        val line = countdownLine(e, today, ReviewStrictness.RELAXED, listOf(e))!!
        assertTrue(line, line.contains("冲刺保留 85%"))
        assertFalse(line, line.contains("."))
    }

    @Test
    fun `考试当天`() {
        val e = exam(0L)
        assertEquals("今天考试", countdownLine(e, today, ReviewStrictness.RELAXED, listOf(e)))
    }

    @Test
    fun `已过说已过`() {
        val e = exam(-3L)
        assertEquals("「期末考」 已过 3 天", countdownLine(e, today, ReviewStrictness.RELAXED, listOf(e)))
    }

    @Test
    fun `窗口为 0 的那场只剩今天，当天那句不掺冲刺字样`() {
        val e1 = exam(1L, window = 0)
        assertEquals("「期末考」 · 还剩 1 天", countdownLine(e1, today, ReviewStrictness.RELAXED, listOf(e1)))
        val e0 = exam(0L, window = 0)
        assertEquals("今天考试", countdownLine(e0, today, ReviewStrictness.RELAXED, listOf(e0)))
    }

    // ── 评审 6 补的两条鉴别用例 ────────────────────────────────────────────

    @Test
    fun `打印的冲刺保留就是调度器用的那个数`() {
        // RELAXED + 全学科考试 10 天后：调度器 base 0.80 → ramp **一次** = 0.90。
        // 修复前首页把 schedulingForSubject 的输出（已 ramp 的 0.90）当 rBase 传进来再 ramp，
        // 屏幕印 93%（red 轮实证：expected 90 but was 93）。
        val e = exam(10L, sprint = 0.95, window = 30)
        val exams = listOf(e)
        val sched = MemoryScheduler.forSubject(
            strictness = ReviewStrictness.RELAXED,
            exams = exams,
            subject = null,
            today = today,
        )
        val line = countdownLine(e, today, ReviewStrictness.RELAXED, exams)!!
        assertEquals("「期末考」 · 还剩 10 天 · 冲刺保留 90%", line)
        // 对照的是"同一个数"这条契约本身，不是用生产函数给别的用例取期望
        assertEquals("「期末考」 · 还剩 10 天 · 冲刺保留 ${(sched.targetRecall * 100).roundToInt()}%", line)
        assertFalse(line, line.contains("93%"))
    }

    @Test
    fun `绑了学科的考试这一行不印冲刺保留`() {
        // 只绑数学的那场：subject=null 的它（词卡、首页这一行）根本不沾，ramp 不作用在这里。
        // 修复前这里会把没抬过的 base 原样印成「冲刺保留 80%」（red 轮实证），那是说谎。
        val e = exam(10L, subjects = listOf("数学"))
        val line = countdownLine(e, today, ReviewStrictness.RELAXED, listOf(e))
        assertEquals("「期末考」 · 还剩 10 天", line)
        assertFalse(line!!, line.contains("冲刺保留"))
    }
}
