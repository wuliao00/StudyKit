package com.studykit.ui.study

import com.studykit.data.entity.Question
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 反馈分层 + 挤牙膏提示（QuizFeedback）的纯逻辑单测（v2.5 缺口补齐）。
 *
 * 钉的是四件事：
 *  - **提示三档都不泄答案**：线索 / 第一步 / 方法方向逐档收紧，但没有一档给出正确项文本、
 *    也不出现「正确项」字样。这是「挤牙膏」的底线：提示只把思路往前推一步，不替用户把答案吐出来。
 *  - **完整解析只在答错时摊开**：答对给的是分层反馈，不是把整段解析再灌一遍（必要难度）。
 *  - **反馈分三层各说各的**：任务级（对不对 + 正确项）、过程级（错在哪一步 / 思路，取自 explanation）、
 *    自我调节级（下一步建议，随「用了几档提示」变化）。
 *  - **文案分寸**：出现「反馈不是万能」的边界说明，不许出现「保证 / 一定能」这类夸大。
 *
 * 与 [RecallGateTest] / [QuestionOrderingTest] 同一个理由：这些是全仓唯一能钉住「提示别剧透」的地方。
 */
class QuizFeedbackTest {

    private fun question(
        subject: String = "地理",
        stem: String = "下列城市中，哪一个是广东省的省会？",
        options: List<String> = listOf("北京", "上海", "广州", "深圳"),
        answerIndex: Int = 2,
        explanation: String = "广州是广东省省会，深圳是经济特区但不是省会。",
    ) = Question(
        id = 1L,
        uuid = "u1",
        subject = subject,
        stem = stem,
        optionsJson = org.json.JSONArray(options).toString(),
        answerIndex = answerIndex,
        explanation = explanation,
    )

    // ── 提示三档 ────────────────────────────────────────────────────────────

    @Test fun `提示恰好三档且互不相同`() {
        val tiers = hintTiers(question())
        assertEquals(HINT_TIER_COUNT, tiers.size)
        assertEquals(HINT_TIER_COUNT, tiers.distinct().size)
        assertTrue("每一档都不能是空文本", tiers.all { it.isNotBlank() })
    }

    @Test fun `三档提示都不给最终答案`() {
        val q = question()
        val options = parseOptions(q.optionsJson)
        val answer = options[q.answerIndex]
        hintTiers(q).forEachIndexed { i, tier ->
            assertFalse("第 $i 档提示泄露了正确项文本：$tier", tier.contains(answer))
            assertFalse("第 $i 档提示直接点名的字样：$tier", tier.contains("正确项"))
            assertFalse("第 $i 档提示泄露了完整解析：$tier", tier.contains(q.explanation))
        }
    }

    @Test fun `提示只吃学科不吃题干里的答案`() {
        // 极端脏数据：题干本身把答案文本嵌进去了。提示模板不引用题干，因此仍不该把答案带出来
        val q = question(stem = "答案就是广州，请确认", explanation = "广州")
        assertTrue(hintTiers(q).none { it.contains("广州") })
    }

    @Test fun `挤牙膏一次只往前挪一档且封顶三档`() {
        assertEquals(1, nextHintLevel(0))
        assertEquals(2, nextHintLevel(1))
        assertEquals(3, nextHintLevel(2))
        // 到第三档就到底，不再往前挤
        assertEquals(3, nextHintLevel(3))
        assertEquals(3, nextHintLevel(9))
    }

    @Test fun `揭示的提示条数等于当前档位且封顶`() {
        val q = question()
        assertEquals(0, revealedHints(q, level = 0).size)
        assertEquals(1, revealedHints(q, level = 1).size)
        assertEquals(3, revealedHints(q, level = 3).size)
        // 档位越界也不会多揭示
        assertEquals(HINT_TIER_COUNT, revealedHints(q, level = 99).size)
    }

    // ── 完整解析的揭示时机 ──────────────────────────────────────────────────

    @Test fun `答错才摊开完整解析答对不重复灌`() {
        assertTrue(shouldRevealFullExplanation(hintsUsed = 0, correct = false))
        assertTrue(shouldRevealFullExplanation(hintsUsed = 3, correct = false))
        assertFalse(shouldRevealFullExplanation(hintsUsed = 2, correct = true))
    }

    // ── 反馈三层 ────────────────────────────────────────────────────────────

    @Test fun `答对时任务级报对且不标错项`() {
        val q = question()
        val options = parseOptions(q.optionsJson)
        val fb = buildFeedback(question = q, selected = q.answerIndex, options = options, hintsUsed = 0)
        assertTrue(fb.verdict.contains("正确"))
        assertFalse("答对不该出现「回答错误」", fb.verdict.contains("错误"))
    }

    @Test fun `答错时任务级给出正确项字母与文本`() {
        val q = question()
        val options = parseOptions(q.optionsJson)
        val fb = buildFeedback(question = q, selected = 1, options = options, hintsUsed = 0)
        assertTrue(fb.verdict.contains("错误"))
        // 正确项是 index 2 → 字母 C、文本「广州」
        assertTrue("任务级应给出正确项字母：${fb.verdict}", fb.verdict.contains("C"))
        assertTrue("任务级应给出正确项文本：${fb.verdict}", fb.verdict.contains("广州"))
    }

    @Test fun `过程级取自解析文本`() {
        val q = question()
        val options = parseOptions(q.optionsJson)
        val fb = buildFeedback(question = q, selected = 1, options = options, hintsUsed = 0)
        assertTrue("过程级应引用 explanation 的思路：${fb.process}", fb.process.contains("深圳"))
    }

    @Test fun `解析为空的脏数据过程级降级不崩`() {
        val q = question(explanation = "   ")
        val options = parseOptions(q.optionsJson)
        val fb = buildFeedback(question = q, selected = 1, options = options, hintsUsed = 1)
        assertTrue("没有解析时也要给一句可执行的复盘引导", fb.process.isNotBlank())
    }

    @Test fun `自我调节级按提示用量分四种说法`() {
        val q = question()
        val options = parseOptions(q.optionsJson)
        val noHintRight = buildFeedback(q, q.answerIndex, options, hintsUsed = 0).selfReg
        val hintedRight = buildFeedback(q, q.answerIndex, options, hintsUsed = 2).selfReg
        val noHintWrong = buildFeedback(q, 1, options, hintsUsed = 0).selfReg
        val hintedWrong = buildFeedback(q, 1, options, hintsUsed = 3).selfReg
        // 四种状态两两不同
        val distinct = setOf(noHintRight, hintedRight, noHintWrong, hintedWrong)
        assertEquals("四种作答状态应给出四种下一步建议", 4, distinct.size)
        // 关键语义锚点
        assertTrue("借提示答对 ⇒ 明天再来一遍", hintedRight.contains("明天"))
        assertTrue("无提示答错（高置信答错）⇒ 优先重做", noHintWrong.contains("优先"))
    }

    // ── 文案分寸 ────────────────────────────────────────────────────────────

    @Test fun `反馈边界说明声明不是万能且不做夸大承诺`() {
        val note = FEEDBACK_SCOPE_NOTE
        assertTrue("要点明反馈不是万能的", note.contains("不是万能"))
        assertFalse("不许出现「保证」", note.contains("保证"))
        assertFalse("不许出现「一定能」", note.contains("一定能"))
    }

    @Test fun `反馈全链路文案都不含夸大词`() {
        val q = question()
        val options = parseOptions(q.optionsJson)
        for (hints in 0..HINT_TIER_COUNT) {
            for (sel in listOf(q.answerIndex, 1)) {
                val fb = buildFeedback(q, sel, options, hints)
                val all = fb.verdict + fb.process + fb.selfReg
                assertFalse("出现「保证」：$all", all.contains("保证"))
                assertFalse("出现「一定能」：$all", all.contains("一定能"))
            }
        }
    }
}
