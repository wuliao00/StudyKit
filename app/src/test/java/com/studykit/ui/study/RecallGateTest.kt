package com.studykit.ui.study

import com.studykit.data.entity.Word
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 检索优先闸门 + 新词预测试的纯逻辑单测（v2.5 §4.1）。
 *
 * 钉的是四件事，每件都各挡一种坏法：
 *  - [decideRecallGate]：闸门到底拦不拦（三条口径 —— 开+未翻面拦、已翻面放、**关掉就放**）。
 *    第三条是"可关"承诺唯一的证据：它红了就是"关了开关还在拦人"。
 *  - [canGradeNow]：三个入口（滑动 / 两颗按钮 / 三档自评）是不是同一个条件，
 *    以及预测试进行中那一排是不是还灰着。
 *  - [buildRecallPretest]：干扰项去重、不足 3 条**整轮跳过**（不许退化成二选一）、
 *    正确项位置不固定。
 *  - [isPretestCandidate]：只有"闸门开 + 真是新词"才出题。
 *
 * 与 [SwipeDecisionTest] 同一个理由：这些规则留在 Composable 里就只能靠真机试手势。
 * 手势路径本身（拖进来 → 判定 → 到底有没有调 onGrade）由 `RecallGateRenderTest` 那条
 * Robolectric 渲染守卫覆盖 —— 纯函数测不到它。
 */
class RecallGateTest {

    private fun newWord(
        id: Long = 1L,
        word: String = "abandon",
        meaning: String = "放弃；丢弃",
        status: String = Word.STATUS_NEW,
        lastReviewAt: Long? = null,
    ) = Word(
        id = id,
        uuid = "u$id",
        word = word,
        meaning = meaning,
        example = "",
        status = status,
        lastReviewAt = lastReviewAt,
    )

    // ── 闸门三条口径（§4.1 逐条对应）────────────────────────────────────────

    @Test fun `闸门开 未翻面 滑动 ⇒ 翻面不结算`() {
        assertEquals(
            GateAction.FLIP,
            decideRecallGate(
                decision = SwipeDecision.KNOWN,
                gateEnabled = true,
                revealed = false,
            ),
        )
        // 左滑同规则：闸门只认"答案露过没有"，方向不参与拦截判断
        assertEquals(
            GateAction.FLIP,
            decideRecallGate(
                decision = SwipeDecision.UNKNOWN,
                gateEnabled = true,
                revealed = false,
            ),
        )
    }

    @Test fun `已翻面 滑动 ⇒ 按方向结算`() {
        assertEquals(
            GateAction.SETTLE_KNOWN,
            decideRecallGate(
                decision = SwipeDecision.KNOWN,
                gateEnabled = true,
                revealed = true,
            ),
        )
        assertEquals(
            GateAction.SETTLE_UNKNOWN,
            decideRecallGate(
                decision = SwipeDecision.UNKNOWN,
                gateEnabled = true,
                revealed = true,
            ),
        )
    }

    @Test fun `闸门关 未翻面 滑动 ⇒ 直接结算（开关真的有消费点）`() {
        assertEquals(
            GateAction.SETTLE_KNOWN,
            decideRecallGate(
                decision = SwipeDecision.KNOWN,
                gateEnabled = false,
                revealed = false,
            ),
        )
        assertEquals(
            GateAction.SETTLE_UNKNOWN,
            decideRecallGate(
                decision = SwipeDecision.UNKNOWN,
                gateEnabled = false,
                revealed = false,
            ),
        )
    }

    @Test fun `位移不够时闸门不给翻面这个机会`() {
        // 没跨过阈值的一手在任何档位都是回弹：闸门不能把"没拖够"也解释成"用户想翻面"
        for (gate in listOf(true, false)) {
            for (revealed in listOf(true, false)) {
                assertEquals(
                    GateAction.NONE,
                    decideRecallGate(
                        decision = SwipeDecision.NONE,
                        gateEnabled = gate,
                        revealed = revealed,
                    ),
                )
            }
        }
    }

    // ── 评分入口的条件：三个入口同一份判定 ──────────────────────────────────

    @Test fun `闸门开且未翻面时三档自评不可用`() {
        assertFalse(canGradeNow(gateEnabled = true, revealed = false, pretestActive = false))
    }

    @Test fun `闸门关时三档自评恒可用（完全退回旧行为）`() {
        assertTrue(canGradeNow(gateEnabled = false, revealed = false, pretestActive = false))
        assertTrue(canGradeNow(gateEnabled = false, revealed = true, pretestActive = false))
    }

    @Test fun `预测试进行中三档自评不可用`() {
        // 正确项已经标在屏幕上、用户还没确认 —— 这时能按就是"看一眼选项顺手按认识"
        assertFalse(canGradeNow(gateEnabled = true, revealed = false, pretestActive = true))
        // 预测试摆出来的那一刻起就该灰着，翻了面也一样（要的是"确认"这一步，不是"看见"）
        assertFalse(canGradeNow(gateEnabled = true, revealed = true, pretestActive = true))
    }

    @Test fun `预测试走完即视为已翻面 此时滑动直接结算`() {
        // §3.3：确认之后 revealed=true，于是闸门这条路算通过了 ——
        // 再逼用户点一次翻面去看他刚看过答案的东西是空转
        val revealedAfterPretest = true
        assertEquals(
            GateAction.SETTLE_KNOWN,
            decideRecallGate(
                decision = SwipeDecision.KNOWN,
                gateEnabled = true,
                revealed = revealedAfterPretest,
            ),
        )
        assertTrue(
            canGradeNow(
                gateEnabled = true,
                revealed = revealedAfterPretest,
                pretestActive = false,
            ),
        )
    }

    // ── 出题条件：闸门开 + 真是新词 ─────────────────────────────────────────

    @Test fun `新词判定要求 status 为 NEW 且从没复习过`() {
        assertTrue(isPretestCandidate(newWord(), gateEnabled = true))
        // lastReviewAt 一旦被写过就说明这个词已经被评分过一轮
        assertFalse(isPretestCandidate(newWord(lastReviewAt = 123L), gateEnabled = true))
        assertFalse(isPretestCandidate(newWord(status = Word.STATUS_LEARNING), gateEnabled = true))
        assertFalse(isPretestCandidate(newWord(status = Word.STATUS_MASTERED), gateEnabled = true))
        // 复用同一个开关，不新增第二个设置项：闸门关着就压根不出预测试
        assertFalse(isPretestCandidate(newWord(), gateEnabled = false))
    }

    // ── 干扰项选取 ──────────────────────────────────────────────────────────

    @Test fun `干扰项按释义文本去重`() {
        // 同词库里两个词共用一句释义是常态：不去重就会给出两个一模一样的选项
        val pretest = buildRecallPretest(
            word = newWord(),
            pool = listOf("借用", "借用", "借用", "放弃；丢弃", "借用", "容忍"),
            random = Random(7),
        )
        assertNotNull(pretest)
        pretest!!
        assertEquals(PRETEST_OPTION_COUNT, pretest.options.size)
        assertEquals(
            "选项里不能有重复释义",
            PRETEST_OPTION_COUNT,
            pretest.options.distinct().size,
        )
        assertEquals("放弃；丢弃", pretest.options[pretest.correctIndex])
        assertTrue(pretest.valid)
    }

    @Test fun `与正确项同文的干扰项一律丢掉`() {
        // 另一个词写着同一句释义时不排掉它，这道题就有两个正确答案。
        // "放弃 "（带尾空格）trim 后与正确项同文，同样要丢 —— 丢完只剩一条可用干扰项，
        // 于是整轮跳过（不足三条不做题），而不是退化出一道凑数的题。
        assertNull(
            buildRecallPretest(
                word = newWord(meaning = "放弃"),
                pool = listOf("放弃", "放弃 ", "容忍"),
                random = Random(1),
            ),
        )
        // 剩下那两条干净时正常出题，且选项里不会再有"放弃"
        val pretest = buildRecallPretest(
            word = newWord(meaning = "放弃"),
            pool = listOf("放弃", "容忍", "借用"),
            random = Random(1),
        )
        assertNotNull(pretest)
        pretest!!
        assertEquals(setOf("放弃", "容忍", "借用"), pretest.options.toSet())
        assertEquals("放弃", pretest.options[pretest.correctIndex])
    }

    @Test fun `可用释义不足三条时整轮跳过`() {
        // 二选一 / 只有一个正确项都是假预测试：猜中的概率 50% / 100%，那不叫检索练习
        assertNull(buildRecallPretest(word = newWord(), pool = emptyList(), random = Random(1)))
        assertNull(buildRecallPretest(word = newWord(), pool = listOf("只有这一条"), random = Random(1)))
        assertNull(buildRecallPretest(word = newWord(), pool = listOf("", "  ", "容忍"), random = Random(1)))
        assertNull(buildRecallPretest(word = newWord(), pool = listOf("容忍", "容忍"), random = Random(1)))
        // 正确项为空也没有可标的正确答案，同样跳过
        assertNull(buildRecallPretest(word = newWord(meaning = "   "), pool = listOf("容忍", "借用"), random = Random(1)))
    }

    @Test fun `三条齐时出的正是一道完整三选一`() {
        val pretest = buildRecallPretest(
            word = newWord(),
            pool = listOf("容忍", "借用", "放弃；丢弃", "抽象"),
            random = Random(3),
        )
        assertNotNull(pretest)
        pretest!!
        assertEquals(PRETEST_OPTION_COUNT, pretest.options.size)
        // 池子给得多于 2 条时只取够用的两条（正确项 + 2 干扰），多的那条不进题面
        assertEquals(2, pretest.options.count { it != "放弃；丢弃" })
        assertEquals(1L, pretest.wordId)
        assertTrue(pretest.valid)
    }

    @Test fun `正确项位置不固定`() {
        // 固定把正确项摆在第一个的话，用户背到第十个词就已经知道答案在哪一格了
        val word = newWord()
        val pool = listOf("容忍", "借用")
        val positions = (0L until 200L).map { seed ->
            val pretest = buildRecallPretest(word = word, pool = pool, random = Random(seed))
            assertNotNull(pretest)
            pretest!!.correctIndex
        }.toSet()
        assertEquals(
            "正确项应当能落在三个位置的任意一个上，实际只覆盖到 $positions",
            setOf(0, 1, 2),
            positions,
        )
    }

    @Test fun `题面自洽性由 valid 把关`() {
        val broken = RecallPretest(wordId = 1L, options = listOf("甲", "甲", "乙"), correctIndex = 0)
        assertFalse("重复选项的题必须是无效的", broken.valid)
        val outOfRange = RecallPretest(wordId = 1L, options = listOf("甲", "乙", "丙"), correctIndex = 3)
        assertFalse(outOfRange.valid)
        assertTrue(RecallPretest(wordId = 1L, options = listOf("甲", "乙", "丙"), correctIndex = 2).valid)
    }
}
