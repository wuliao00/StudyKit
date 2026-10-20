package com.studykit.modelpack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [BudgetGuard] 的纯函数守卫（计划 T3 / 文档 L2220 的 ⛔ 项）。
 *
 * 钉三件事：① 边界是 [BudgetGuard.DEFAULT_BUDGET_BYTES] 的 "≤ 允许、+1 字节拒"；
 * ② 移除建议挑"最小且够腾"的包（少删一点）；③ 用计划 §2.6 的实测体积跑一遍五包全装 ——
 * 让"600MB 装不下真实五包"这个体积账矛盾在 CI 里可复现，而不是埋在文档里。
 */
class BudgetGuardTest {

    private fun mb(value: Double): Long = (value * 1024.0 * 1024.0).toLong()

    private fun usage(id: String, mb: Double): PackUsage = PackUsage(id, "包 $id", mb(mb))

    private fun check(
        installed: List<PackUsage>,
        incoming: PackUsage,
        budgetBytes: Long = BudgetGuard.DEFAULT_BUDGET_BYTES,
    ): BudgetDecision = BudgetGuard.check(installed, incoming, budgetBytes)

    @Test
    fun `默认预算是六百MB且能换算成MB`() {
        assertEquals("文档 L2220 的 600MB 上限", 600L * 1024 * 1024, BudgetGuard.DEFAULT_BUDGET_BYTES)
        assertEquals(600L, BudgetGuard.toMb(BudgetGuard.DEFAULT_BUDGET_BYTES))
        assertEquals(0L, BudgetGuard.toMb(1024L * 1024L - 1))
    }

    @Test
    fun `空库装小包允许`() {
        val decision = check(emptyList(), usage("voice-tts", 30.0))
        assertTrue(decision.allowed)
        assertEquals(0L, decision.installedBytes)
        assertEquals(mb(30.0), decision.newBytes)
        assertEquals(0L, decision.needFreeBytes)
        assertNull(decision.suggestRemove)
    }

    @Test
    fun `恰好等于预算允许`() {
        val decision = check(listOf(usage("voice-tts", 200.0)), usage("voice-asr", 400.0))
        assertTrue("= 预算不该拒（文档是超过才拒）", decision.allowed)
        assertEquals(0L, decision.needFreeBytes)
        assertNull(decision.suggestRemove)
    }

    @Test
    fun `超一字节就拒并给得出能腾够的包`() {
        val installed = listOf(usage("voice-tts", 200.0))
        val incoming = PackUsage("voice-asr", "包 voice-asr", mb(400.0) + 1L)
        val decision = check(installed, incoming)
        assertFalse(decision.allowed)
        assertEquals(1L, decision.needFreeBytes)
        assertEquals("200MB 的包能腾出 1 字节", "voice-tts", decision.suggestRemove?.id)
    }

    @Test
    fun `建议挑最小且够腾的那个`() {
        val installed = listOf(
            usage("llm-zh-06b-int4", 433.4),
            usage("voice-tts", 30.1),
            usage("voice-asr", 70.6),
        )
        // 总占用 534.1MB，再装 100MB → 超 34.1MB；能腾够的是 ASR(70.6) 与 LLM(433.4)，挑小的 ASR
        val decision = check(installed, usage("embed-zh-small-int8", 100.0))
        assertFalse(decision.allowed)
        assertTrue(
            "还差约 34.1MB（体积按 MB 常量换算会有 1 字节级截断，允许 1 字节误差）",
            Math.abs(decision.needFreeBytes - mb(34.1)) <= 1L,
        )
        assertEquals("voice-asr", decision.suggestRemove?.id)
    }

    @Test
    fun `没有单个包能腾够时建议为空`() {
        val installed = listOf(usage("voice-tts", 30.0))
        val decision = check(installed, usage("llm-zh-06b-int4", 500.0), budgetBytes = mb(100.0))
        assertFalse(decision.allowed)
        assertEquals("需要腾 430MB，但只有 30MB 可删", mb(430.0), decision.needFreeBytes)
        assertNull(decision.suggestRemove)
    }

    @Test
    fun `重装同一个包不重复计旧占用`() {
        val installed = listOf(usage("voice-tts", 30.0))
        val decision = check(installed, usage("voice-tts", 30.0))
        assertTrue("重装 = 替换，不该被自己的旧包挤出预算", decision.allowed)
        assertEquals(0L, decision.installedBytes)
        assertEquals(mb(30.0), decision.newBytes)
        assertEquals(0L, decision.needFreeBytes)
    }

    @Test
    fun `五包全装必超六百MB预算`() {
        // 计划 §2.6 的实测/估算体积（四个已装 + 逐个 incoming）
        val llm = usage("llm-zh-06b-int4", 433.4)
        val tts = usage("voice-tts", 30.1)
        val asr = usage("voice-asr", 70.6)
        val structure = usage("ocr-structure", 90.0)
        val dehand = usage("de-handwrite", 45.0)

        val installed = mutableListOf<PackUsage>()
        var rejectedAt: String? = null
        for (pack in listOf(llm, tts, asr, structure, dehand)) {
            val decision = BudgetGuard.check(installed, pack)
            if (!decision.allowed) {
                rejectedAt = pack.id
                assertEquals("拒装时一定给得出要卸哪个包", pack.id, rejectedAt)
                assertTrue("要腾的字节应为正", decision.needFreeBytes > 0L)
                assertTrue("总量已超预算", decision.installedBytes + decision.newBytes > decision.budgetBytes)
                break
            }
            installed += pack
        }

        assertEquals("五包全装必被拒，且第一次拒发生在第几包要钉死", "ocr-structure", rejectedAt)
        assertEquals("被拒时只装得下前三个", 3, installed.size)
        // LLM 433.4 + TTS 30.1 + ASR 70.6 = 534.1MB（MB 常量换算有 1 字节级截断）
        assertTrue(
            "前三个合计应约为 534.1MB，实际 ${installed.sumOf { it.bytes }}",
            Math.abs(installed.sumOf { it.bytes } - mb(534.1)) <= 1L,
        )
    }

    @Test
    fun `五包加语义包的总账超过七百MB必拒`() {
        val all = listOf(
            usage("llm-zh-06b-int4", 433.4),
            usage("voice-tts", 30.1),
            usage("voice-asr", 70.6),
            usage("ocr-structure", 90.0),
            usage("de-handwrite", 45.0),
            usage("embed-zh-small-int8", 30.0),
        )
        val total = all.sumOf { it.bytes }
        assertTrue("计划 §2.6：≈699MB", total > mb(699.0) - mb(1.0))
        val decision = BudgetGuard.check(all.dropLast(1), all.last())
        assertFalse(decision.allowed)
        assertTrue(decision.needFreeBytes > 0L)
    }

    @Test
    fun `预算必须为正数`() {
        val error = org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            BudgetGuard.check(emptyList(), usage("voice-tts", 1.0), budgetBytes = 0L)
        }
        assertTrue(error.message.orEmpty().contains("预算"))
    }
}
