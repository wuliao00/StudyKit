package com.studykit.ui.study

import com.studykit.data.entity.MockSession
import org.json.JSONObject
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

    /**
     * ⛔ 评审 F7：还活着的两份线上格式各由**字面量**钉一次（以前只有"自己编、自己解"的往返 ——
     * 键名换成别的、值从数字换成串，往返照样绿）。期望值不调用任何生产编码器取。
     *
     * 评审 F2 把 `result` 的读侧与逐题判词那份编解码（`encodeResults` / `decodeResults` /
     * `decodeExamResult`）整条删掉了：今天恢复路径只读 `question_ids` 与 `answers`，
     * 所以这里能钉的线上格式只剩这两份，加上 [encodeExamResult] 写出去的那些字节
     * （见下面 [交卷摘要写出的键与值都是字面量]）。
     */
    @Test
    fun `题目 id 列表的线上格式由字面 json 钉住`() {
        assertEquals("[7,8]", encodeQuestionIds(listOf(7L, 8L)))
        assertEquals("[]", encodeQuestionIds(emptyList()))
        // 读侧认的就是上面那一串字面量（顺序 = 发卷顺序，恢复时按它逐题取回）
        assertEquals(listOf(7L, 8L), decodeQuestionIds("[7,8]"))
        assertEquals(emptyList<Long>(), decodeQuestionIds("[\"7\"]"))
    }

    /**
     * `answers` 那一列：键是题目 id 的十进制串、值是所选下标的数字，两边都写字面量。
     * JSONObject 内部不保序，所以钉键与值、不钉整串的字符顺序。
     */
    @Test
    fun `作答表的线上格式由字面 json 钉住`() {
        val o = JSONObject(encodeAnswers(mapOf(7L to 0, 8L to 2)))
        assertEquals("键就是题目 id 的十进制串", 0, o.getInt("7"))
        assertEquals(2, o.getInt("8"))
        assertEquals("两题作答就两个键，不多写", 2, o.length())
        assertEquals(mapOf(7L to 0, 8L to 2), decodeAnswers("""{"7":0,"8":2}"""))
    }

    /**
     * 评审 F5 落在**还活着的那个解码**上：`answers` 是恢复路径唯一读回来的作答，脏值不许被折成
     * "用户当时选了第几项"。数字串 `"2"` 不是数字、布尔不是数字、嵌套对象不是数字 —— 一律丢掉，
     * 丢掉就意味着那题算未答（进待批改队列），而不是被凭空记成一次作答。
     * 违反这条口径的那个解码（`optInt("s", -1)`）连同它服务的那份格式一起被评审 F2 删除了。
     */
    @Test
    fun `作答列里脏的那一项不许凭空造出一个所选`() {
        assertEquals("数字串不是数字", emptyMap<Long, Int>(), decodeAnswers("""{"1":"2"}"""))
        assertEquals("布尔不是数字", emptyMap<Long, Int>(), decodeAnswers("""{"1":true}"""))
        assertEquals("null 不是数字", emptyMap<Long, Int>(), decodeAnswers("""{"1":null}"""))
        assertEquals("嵌套对象不是数字", emptyMap<Long, Int>(), decodeAnswers("""{"1":{"s":2}}"""))
        // 脏的一条不牵连干净的那条：这一句证明上面四条是"太脏所以丢"，不是"整个读不出来"
        assertEquals(mapOf(1L to 0), decodeAnswers("""{"1":0,"2":"y","3":true}"""))
    }

    // ── 评审 F1：跨进程恢复的暂停锚点 ────────────────────────────────────

    /**
     * ⛔ 评审 F1 的硬闸。旧实现把恢复时刻自己当暂停起点（`deadlineAfterResume(now, now, deadline)`），
     * 于是 pausedFor 恒 0、截止被 `.coerceAtLeast(now)` 夹到「现在」：用户按下「继续作答」后的第一个
     * tick 就自动交卷，整段停表被悄悄吃掉（默认 `examPauseCountsTime = false`，暂停本该停表）。
     *
     * 场景按裁定给：T 暂停、T+40 分钟回到页面、截止在 T+5 分钟就已经跑过。
     * 补对了，恢复后手里还剩 5 分钟；补错了，手里是 0 秒。
     */
    @Test
    fun `硬闸 跨进程恢复按库里那个暂停起点补时，不当瞬时暂停`() {
        val pausedAt = 1_000_000L                    // T：落在 paused_at 那一列的暂停起点
        val now = pausedAt + 40 * 60_000L            // T+40 分钟才回到页面
        val deadline = pausedAt + 5 * 60_000L        // 暂停后 5 分钟截止时刻就跑过了
        assertEquals(
            "那一列有值，锚点就是它，不是恢复时刻",
            pausedAt,
            pausedAnchorForRestore(rowPausedAt = pausedAt, nowTs = now),
        )
        val credited = deadlineAfterResume(
            nowTs = now,
            pausedAt = pausedAnchorForRestore(pausedAt, now),
            deadlineTs = deadline,
            pauseCountsTime = false,
        )
        assertEquals("截止按整段暂停往后推，而不是夹到现在", pausedAt + 45 * 60_000L, credited)
        assertTrue("恢复后手里还剩 5 分钟，不是当场自动交卷", credited!! > now)
    }

    /**
     * 锚点取不到就**不补时**：库里那一列为空（RUN 行崩在作答途中，钟本来就一直在跑）、
     * 或那一看就来自时钟往回跳（暂停起点落在未来），都退回「此刻才暂停」。
     * 白送时间等于允许「关掉 App 睡一觉再回来继续考」。
     */
    @Test
    fun `库里没有可信暂停起点时不白送时间`() {
        val now = 500_000L
        assertEquals(now, pausedAnchorForRestore(rowPausedAt = null, nowTs = now))
        assertEquals(now, pausedAnchorForRestore(rowPausedAt = now + 60_000L, nowTs = now))
        // 锚点=恢复时刻 ⇒ 补 0 分钟：已过期的截止夹到现在，不额外送一段
        val credited = deadlineAfterResume(
            nowTs = now,
            pausedAt = pausedAnchorForRestore(null, now),
            deadlineTs = now - 60_000L,
            pauseCountsTime = false,
        )
        assertEquals(now, credited)
    }

    /**
     * ⛔ 评审 F1 点名要的那条算例，全用字面量：**T 暂停、T+3600 秒按下继续、限时 45 分钟**。
     * 补对了，截止时刻比原来正好晚一小时（那 45 分钟一分不少，且此刻手里还剩整整 45 分钟）；
     * 旧实现把恢复时刻当暂停起点，于是补 0、再被 `.coerceAtLeast(now)` 夹到"现在"——
     * 用户按下「继续作答」后的第一个 tick 就把这卷判了。
     */
    @Test
    fun `硬闸 暂停一小时后继续截止时刻正好晚一小时`() {
        val open = 1_000_000L                      // T：开卷即暂停那一刻（库里 paused_at 的值）
        val deadline = open + 45 * 60_000L          // 限时 45 分钟 ⇒ 原截止时刻
        val resumed = open + 3_600_000L             // T + 3600 秒才按下「继续作答」
        val credited = deadlineAfterResume(
            nowTs = resumed,
            pausedAt = pausedAnchorForRestore(rowPausedAt = open, nowTs = resumed),
            deadlineTs = deadline,
            pauseCountsTime = false,
        )
        assertEquals("补回来的正是那一小时", deadline + 3_600_000L, credited)
        assertEquals("手里仍然握着完整的 45 分钟", 45 * 60_000L, credited!! - resumed)
    }

    /**
     * 暂停卡上那句「剩余」按**恢复那一刻会同口径算出的**读数念（界面守卫的另一半：
     * 用户在这张卡上就该知道自己还剩多少，而不是看着一个冻住的 00:00 猜）。
     * 因为整段暂停都补得回来，"在外面待多久"不影响结果：待 40 分钟与待 5 小时，卡上都念同一个数。
     */
    @Test
    fun `暂停卡上的剩余按恢复那一刻算`() {
        val pausedAt = 1_000_000L
        val deadline = pausedAt + 5 * 60_000L
        val back = pausedAt + 40 * 60_000L
        assertEquals(
            "停表：暂停那一刻还剩 5 分钟，卡上就念 5 分钟（不是冻住的 00:00）",
            "05:00",
            pausedRemainingLabel(back, pausedAt, deadline, pauseCountsTime = false),
        )
        assertEquals(
            "待得再久也是同一个读数：整段暂停都补，一分不白掉",
            "05:00",
            pausedRemainingLabel(pausedAt + 5 * 3_600_000L, pausedAt, deadline, pauseCountsTime = false),
        )
        assertEquals(
            "暂停也扣时：不补任何一段，时间在外面跑完了就照实念 00:00",
            "00:00",
            pausedRemainingLabel(back, pausedAt, deadline, pauseCountsTime = true),
        )
        assertNull("不限时的卷不给那一格", pausedRemainingLabel(back, pausedAt, null, pauseCountsTime = false))
        assertEquals(
            "库里没有可信起点（RUN 行崩在作答途中）：念真实剩余，一分不白送",
            "00:00",
            pausedRemainingLabel(back, null, deadline, pauseCountsTime = false),
        )
    }

    /**
     * ⛔ 摘要那一列同样只用字面量钉**写出去的字节**：`mock_sessions.result` 由 [encodeExamResult] 写，
     * 而今天没有任何地方把它读回屏幕（评审 F2 删掉无人调用的读侧），所以「编完再解回来」那种
     * 自证式往返在这里根本没有意义 —— 期望的是键名与取值本身。
     */
    @Test
    fun `交卷摘要写出的键与值都是字面量`() {
        val out = submitOutcome(ids, mapOf(1L to 0, 2L to 1), key, 3)
        val auto = JSONObject(encodeExamResult(out, autoSubmitted = true))
        assertEquals(2, auto.getInt("correct"))
        assertEquals(4, auto.getInt("total"))
        assertEquals(2, auto.getInt("graded"))
        assertEquals(1, auto.getInt("auto"))
        assertEquals(2, auto.getJSONArray("toGrade").length())
        assertEquals(3L, auto.getJSONArray("toGrade").getLong(0))
        assertEquals(4L, auto.getJSONArray("toGrade").getLong(1))
        // 用户自己按的交卷：那一位写 0，没有证据就不许让将来的读侧凭空标注「时间到」
        assertEquals(0, JSONObject(encodeExamResult(out)).getInt("auto"))
    }
}
