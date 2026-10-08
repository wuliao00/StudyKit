package com.studykit.ui.study

import com.studykit.data.entity.MockSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 套卷状态机（v3.0-B T10，文档 L1883）。
 *
 * 判分本身**不在这里重新实现** —— 交给已经过 6 条单测的 [MockExamState]，本文件只加它没有的四件事：
 * 阶段、时限、暂停语义、未答进待批改。这样"套卷判分"和"练习判分"不可能分家。
 *
 * ⛔ 文档 L1889 要的两条（超时自动提交、未答入 grading）+ paused 两态，都在这一组里钉死。
 *
 * 纯 JVM 用例（无 Robolectric）：这一层零 Android、零 Room、零时钟，`org.json` 由
 * `testImplementation(libs.json)` 提供真实实现（android.jar 那份是 `Stub!` 占位）。
 */
class MockSessionMachineTest {

    private val ids = listOf(1L, 2L, 3L, 4L)
    private val key = mapOf(1L to 0, 2L to 1, 3L to 2, 4L to 0)

    @Test
    fun `硬闸 未答的题进待批改队列，不判成错`() {
        val out = submitOutcome(ids, selections = mapOf(1L to 0, 2L to 0), answerKey = key, answerCount = 3)
        assertEquals(listOf(3L, 4L), out.toGrade)
        assertEquals(2, out.graded.size)
        assertEquals(2, out.total - out.toGrade.size)
        // 1 答对、2 答错
        assertEquals(1, out.correctCount)
        assertTrue(out.graded.getValue(1L).correct)
        assertFalse(out.graded.getValue(2L).correct)
    }

    @Test
    fun `硬闸 待批改的题不出判词`() {
        val out = submitOutcome(ids, mapOf(1L to 0), key, 3)
        assertTrue(out.graded.keys.none { it in out.toGrade })
        out.toGrade.forEach { assertNull("${it} 未答，不该有 verdict", out.graded[it]) }
    }

    @Test
    fun `全答完时待批改为空`() {
        val out = submitOutcome(ids, ids.indices.associate { ids[it] to 0 }, key, 3)
        assertTrue(out.toGrade.isEmpty())
        assertEquals(4, out.answeredCount)
    }

    @Test
    fun `空卷不炸`() {
        val out = submitOutcome(emptyList(), emptyMap(), emptyMap(), 0)
        assertTrue(out.toGrade.isEmpty())
        assertEquals(0, out.total)
        assertEquals(0, out.correctCount)
    }

    @Test
    fun `答案键里没有的题判为错而不是崩`() {
        val out = submitOutcome(listOf(9L), mapOf(9L to 1), emptyMap(), 3)
        assertEquals(0, out.correctCount)
        assertFalse(out.graded.getValue(9L).correct)
    }

    @Test
    fun `阶段序号与文档 L1861 严格对齐`() {
        assertEquals(0, encodePhase(MockPhase.SCHED))
        assertEquals(1, encodePhase(MockPhase.RUN))
        assertEquals(2, encodePhase(MockPhase.PAUSED))
        assertEquals(3, encodePhase(MockPhase.GRADING))
        assertEquals(4, encodePhase(MockPhase.DONE))
        MockPhase.values().forEach { assertEquals(it, decodePhase(encodePhase(it))) }
    }

    /**
     * ⛔ 配对闸：`mock_sessions.state` 那一列是**用户跨版本留下的数据**，机器侧的序号与库侧的
     * 常量必须逐一对得上。两边都写字面量（不引用被测常量当期望值），任何一侧改号这里就红。
     */
    @Test
    fun `库里那五个状态整数与阶段序号逐一对得上`() {
        assertEquals(0, MockSession.STATE_SCHED)
        assertEquals(1, MockSession.STATE_RUN)
        assertEquals(2, MockSession.STATE_PAUSED)
        assertEquals(3, MockSession.STATE_GRADING)
        assertEquals(4, MockSession.STATE_DONE)
        assertEquals(MockSession.STATE_SCHED, encodePhase(MockPhase.SCHED))
        assertEquals(MockSession.STATE_RUN, encodePhase(MockPhase.RUN))
        assertEquals(MockSession.STATE_PAUSED, encodePhase(MockPhase.PAUSED))
        assertEquals(MockSession.STATE_GRADING, encodePhase(MockPhase.GRADING))
        assertEquals(MockSession.STATE_DONE, encodePhase(MockPhase.DONE))
    }

    @Test
    fun `库里来的垃圾阶段退回未开始，不退 done`() {
        // 退回 DONE 会伪造一份"已交卷"的历史；退回 SCHED 最多让用户重新开一卷
        assertEquals(MockPhase.SCHED, decodePhase(-1))
        assertEquals(MockPhase.SCHED, decodePhase(99))
    }

    @Test
    fun `硬闸 超时判定：到点即到期，不限时永不到期`() {
        assertTrue(isExpired(nowTs = 1_000L, deadlineTs = 1_000L))   // 边界：等于即到期
        assertFalse(isExpired(999L, 1_000L))
        assertFalse(isExpired(9_999L, null))
    }

    @Test
    fun `硬闸 暂停不扣时：截止时刻按暂停时长往后推`() {
        val shifted = deadlineAfterResume(nowTs = 5_000L, pausedAt = 3_000L, deadlineTs = 8_000L, pauseCountsTime = false)
        assertEquals(10_000L, shifted)
    }

    @Test
    fun `硬闸 暂停扣时：截止时刻一动不动`() {
        val same = deadlineAfterResume(5_000L, 3_000L, 8_000L, pauseCountsTime = true)
        assertEquals(8_000L, same)
        assertNull(deadlineAfterResume(5_000L, 3_000L, null, pauseCountsTime = true))
    }

    @Test
    fun `推出来的截止不许倒挂到过去`() {
        // 暂停前就已经跑过截止（deadline 8000，暂停在 19000，现在 20000）：
        // 8000 + (20000-19000) = 9000，那是过去 —— 夹回"现在"，让恢复的瞬间还能看见 00:00 而不是负数
        val shifted = deadlineAfterResume(nowTs = 20_000L, pausedAt = 19_000L, deadlineTs = 8_000L, pauseCountsTime = false)
        assertEquals(20_000L, shifted)
    }

    @Test
    fun `正常暂停后推出来的截止就是加回暂停那一段`() {
        assertEquals(25_000L, deadlineAfterResume(nowTs = 20_000L, pausedAt = 3_000L, deadlineTs = 8_000L, pauseCountsTime = false))
    }

    @Test
    fun `时钟往回跳不当成正的暂停时长`() {
        val shifted = deadlineAfterResume(nowTs = 1_000L, pausedAt = 5_000L, deadlineTs = 9_000L, pauseCountsTime = false)
        assertEquals(9_000L, shifted)    // elapsed 负 -> 当 0
    }

    @Test
    fun `剩余毫秒读数：负数夹到 0，不限时为 null`() {
        assertEquals(2_000L, remainingMs(8_000L, 10_000L))
        assertEquals(0L, remainingMs(11_000L, 10_000L))
        assertNull(remainingMs(1L, null))
    }

    @Test
    fun `剩余时间文案：整分钟与最后六十秒都念得出`() {
        assertEquals("45:00", remainingLabel(0L, 45 * 60_000L))
        assertEquals("00:59", remainingLabel(0L, 59_000L))
        assertEquals("00:00", remainingLabel(0L, 0L))
        assertEquals(null, remainingLabel(0L, null))
    }

    @Test
    fun `题目 id 列表序列化往返`() {
        assertEquals(ids, decodeQuestionIds(encodeQuestionIds(ids)))
        assertEquals(emptyList<Long>(), decodeQuestionIds("[]"))
        assertEquals(emptyList<Long>(), decodeQuestionIds("不是 json"))
        assertEquals(emptyList<Long>(), decodeQuestionIds("[\"a\",null]"))
        assertEquals(listOf(1L, 3L), decodeQuestionIds("[1,\"x\",3]"))   // 单条脏只丢那条
    }

    @Test
    fun `作答表序列化往返`() {
        val map = mapOf(1L to 0, 4L to 2)
        assertEquals(map, decodeAnswers(encodeAnswers(map)))
        assertEquals(emptyMap<Long, Int>(), decodeAnswers(""))
        assertEquals(emptyMap<Long, Int>(), decodeAnswers("{bad"))
        assertEquals(mapOf(1L to 0), decodeAnswers("""{"1":0,"x":1,"2":"y"}"""))  // 键/值脏的丢掉
    }

    @Test
    fun `判分结果序列化往返，含未答项的空所选`() {
        val out = submitOutcome(ids, mapOf(1L to 0, 2L to 1), key, 3)
        val back = decodeResults(encodeResults(out.graded))
        assertEquals(out.graded, back)
        assertEquals(emptyMap<Long, MockExamState.Answer>(), decodeResults(null))
        assertEquals(emptyMap<Long, MockExamState.Answer>(), decodeResults("{}"))
        assertEquals(emptyMap<Long, MockExamState.Answer>(), decodeResults("nonsense"))
        // 未答（selected=null）的那一项也要能往返：判词丢了没事，把 null 读成 0 就是伪造一次作答
        val withNull = mapOf(7L to MockExamState.Answer(selected = null, correct = false))
        assertEquals(withNull, decodeResults(encodeResults(withNull)))
    }

    @Test
    fun `交卷摘要（分数）序列化往返`() {
        val out = submitOutcome(ids, mapOf(1L to 0, 2L to 1), key, 3)
        val json = encodeExamResult(out)
        val back = decodeExamResult(json)!!
        // 这两题所选正是答案键里的下标（1→0、2→1），所以判过的两题全对；未答的 3、4 进待批改
        assertEquals(2, back.correctCount)
        assertEquals(4, back.total)
        assertEquals(listOf(3L, 4L), back.toGrade)
        assertNull(decodeExamResult(null))
        assertNull(decodeExamResult("}{"))
    }
}
