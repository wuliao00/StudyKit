package com.studykit.ui.study

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 交错阈值的数据化自洽检查（v3.0-C Task 2）。
 *
 * 这里只问「这把尺子的刻度排布自不自洽」，不问「刻度定得准不准」—— 后者要 200+200 人工标注对
 * 上的 AUC，属语义包计划的质量门，不在这份计划里。顺序、值域、上下界关系是纯算术关系，
 * 不依赖任何实测分布，所以能在本仓钉死。
 */
class InterleavePolicyTest {

    @Test fun `词面刻度的五个阈值顺序自洽`() {
        val p = LexicalInterleave.DEFAULT
        // tauLo < drillLo < tauHi < drillHi：缓冲卡够不像、混淆带跨过相邻线、上界仍低于"重复"
        assertTrue("tauLo=${p.tauLo}", p.tauLo < p.drillLo)
        assertTrue("drillLo=${p.drillLo}", p.drillLo < p.tauHi)
        assertTrue("tauHi=${p.tauHi}", p.tauHi < p.drillHi)
    }

    @Test fun `词面刻度全部落在零到一`() {
        val p = LexicalInterleave.DEFAULT
        for (v in listOf(p.tauHi, p.tauLo, p.drillLo, p.drillHi)) {
            assertTrue("越界 $v", v > 0f && v < 1f)
        }
    }

    @Test fun `间隔下界不大于上界且至少为二`() {
        val p = LexicalInterleave.DEFAULT
        assertTrue(p.gapLo >= 2)      // gap=1 就是相邻，"隔开 1 题"等于没隔
        assertTrue(p.gapLo <= p.gapHi)
    }

    @Test fun `考点地板值落在混淆带内`() {
        val p = LexicalInterleave.DEFAULT
        assertTrue("CONCEPT_FLOOR=${TextSimilarity.CONCEPT_FLOOR} 不在 [${p.drillLo}, ${p.drillHi}]",
            TextSimilarity.CONCEPT_FLOOR >= p.drillLo && TextSimilarity.CONCEPT_FLOOR <= p.drillHi)
    }

    @Test fun `文档的余弦刻度没被误用成词面刻度`() {
        // ⛔ 这条钉的是偏差 D3：0.85/0.30 是余弦的尺子，套到 Dice 上闸门永远关不上。
        // 期望值写成字面量，不读文档也不读别处常量 —— 改 DEFAULT 就必须让这条红。
        assertEquals(0.60f, LexicalInterleave.DEFAULT.tauHi, 1e-6f)
        assertEquals(0.15f, LexicalInterleave.DEFAULT.tauLo, 1e-6f)
        assertEquals(0.40f, LexicalInterleave.DEFAULT.drillLo, 1e-6f)
        assertEquals(0.75f, LexicalInterleave.DEFAULT.drillHi, 1e-6f)
        assertEquals(3, LexicalInterleave.DEFAULT.gapLo)
        assertEquals(5, LexicalInterleave.DEFAULT.gapHi)
        assertEquals(8, LexicalInterleave.DEFAULT.topK)
        assertEquals(12, LexicalInterleave.DEFAULT.lookahead)
        assertEquals(4, LexicalInterleave.DEFAULT.maxRepairRounds)
    }

    @Test fun `相邻线上界的严格性有定义域`() {
        // tauHi 用"严格大于"判违规（文档 L1243 `sim(...) > TAU_HI`），恰好等于不算违规。
        // 这条不是自证：它要求 Task 3 的实现用 > 而不是 >=，改了实现就要红。
        val p = LexicalInterleave.DEFAULT
        assertTrue(p.tauHi > p.tauLo)
    }
}
