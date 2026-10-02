package com.studykit.tips

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 科学小贴士文案库测试：每条 Tips 必须挂证据、触发时机唯一、措辞不得复活已被证伪的说法。
 *
 * 文案策略（来自 app.docx 的「打破学习错觉」思路）：
 * 用反直觉的科学事实，把用户推向检索、间隔、交错、重做这些「当下更费劲但记得更牢」的行为。
 */
class StudyTipsTest {

    @Test
    fun `every tip carries an evidence citation`() {
        assertTrue(StudyTips.all.isNotEmpty())
        StudyTips.all.forEach { tip ->
            assertTrue("${tip.id} 缺少证据来源", tip.evidence.isNotBlank())
            assertTrue("${tip.id} 文案为空", tip.text.isNotBlank())
        }
    }

    @Test
    fun `tip ids are unique`() {
        val ids = StudyTips.all.map { it.id }
        assertEquals("存在重复的 Tip id", ids.size, ids.toSet().size)
    }

    @Test
    fun `tips are short enough for a card footer`() {
        StudyTips.all.forEach { tip ->
            assertTrue("${tip.id} 文案过长（${tip.text.length} 字）", tip.text.length <= 90)
        }
    }

    @Test
    fun `tips never repeat the twenty one day myth or promise certainty`() {
        StudyTips.all.forEach { tip ->
            assertFalse("${tip.id} 仍在宣传 21 天养成习惯", tip.text.contains("21 天养成"))
            assertFalse("${tip.id} 使用了绝对化承诺", tip.text.contains("保证") || tip.text.contains("一定能"))
        }
    }

    @Test
    fun `first high confidence mistake triggers the hypercorrection tip`() {
        val tip = StudyTips.forEvent(TipEvent.HighConfidenceMistake)
        assertNotNull(tip)
        assertEquals(TipId.HYPERCORRECTION, tip!!.id)
        assertTrue(tip.evidence.contains("Metcalfe"))
    }

    @Test
    fun `a missed day triggers the forgiving tip not a guilt trip`() {
        val tip = StudyTips.forEvent(TipEvent.GapDay)
        assertEquals(TipId.MISS_ONE_DAY, tip!!.id)
        assertTrue(tip.evidence.contains("Lally"))
        assertFalse(tip.text.contains("断签"))       // 不用损失威胁话术
        assertFalse(tip.text.contains("清零"))
    }

    @Test
    fun `twenty one day streak triggers the sixty six day reminder`() {
        val tip = StudyTips.forEvent(TipEvent.StreakReached(21))
        assertEquals(TipId.SIXTY_SIX, tip!!.id)
        assertTrue(tip.text.contains("66"))
    }

    @Test
    fun `streak beyond sixty six no longer repeats the reminder`() {
        assertNull(StudyTips.forEvent(TipEvent.StreakReached(70)))
    }

    @Test
    fun `new card first look triggers pretesting tip`() {
        val tip = StudyTips.forEvent(TipEvent.NewCardFirstLook)
        assertEquals(TipId.PRETEST, tip!!.id)
        assertTrue(tip.evidence.contains("Kornell"))
    }

    @Test
    fun `about to flip without recall triggers testing effect tip`() {
        val tip = StudyTips.forEvent(TipEvent.AboutToFlip)
        assertEquals(TipId.RECALL_FIRST, tip!!.id)
        assertTrue(tip.evidence.contains("Roediger") || tip.evidence.contains("Karpicke"))
    }

    @Test
    fun `blocked practice run triggers interleaving tip`() {
        val tip = StudyTips.forEvent(TipEvent.BlockingStreak(items = 8, subjects = 1))
        assertEquals(TipId.INTERLEAVE, tip!!.id)
    }

    @Test
    fun `mixed subjects do not trigger the interleaving nag`() {
        assertNull(StudyTips.forEvent(TipEvent.BlockingStreak(items = 8, subjects = 3)))
    }

    @Test
    fun `chapter finish triggers elaborative interrogation tip`() {
        val tip = StudyTips.forEvent(TipEvent.ChapterFinished)
        assertEquals(TipId.EXPLAIN_WHY, tip!!.id)
    }

    @Test
    fun `highlight only reading triggers recall note tip`() {
        val tip = StudyTips.forEvent(TipEvent.ExcerptOnlyNoRecall)
        assertEquals(TipId.RECALL_NOTES, tip!!.id)
        assertTrue(tip.evidence.contains("Dunlosky"))
    }

    @Test
    fun `review struggle triggers desirable difficulty tip`() {
        val tip = StudyTips.forEvent(TipEvent.StrugglingReview)
        assertEquals(TipId.DESIRABLE_DIFFICULTY, tip!!.id)
    }

    @Test
    fun `unmapped event shows nothing rather than a random tip`() {
        assertNull(StudyTips.forEvent(TipEvent.Idle))
    }

    @Test
    fun `scientific tag is stable for the badge ui`() {
        assertEquals("[科学验证]", StudyTips.TAG_LABEL)
    }

    @Test
    fun `first habit saved with an if-then triggers the implementation-intention tip`() {
        val tip = StudyTips.forEvent(TipEvent.HabitFirstSave)
        assertEquals(TipId.IF_THEN, tip!!.id)
        assertTrue(tip.evidence.contains("Gollwitzer"))
        // spec D4 口径的原文（逐字钉住）
        assertTrue(tip.text.contains("元分析效应量 d=0.65（中到大）"))
    }

    @Test
    fun `first hint clicked on a mistake redo triggers the productive struggle tip`() {
        val tip = StudyTips.forEvent(TipEvent.FirstHintOnRedo)
        assertNotNull(tip)
        assertEquals(TipId.PRODUCTIVE_STRUGGLE, tip!!.id)
        // spec D8 口径的证据行（不写撤稿文献），逐字钉住
        assertEquals("Productive struggle / constructive struggle（2024–2026 教育 AI 文献）", tip.evidence)
        // 文案说的是「先只拿提示、别急着看全解」这件事本身
        assertTrue(tip.text.contains("提示"))
        assertTrue(tip.text.contains("长脑子"))
    }

    @Test
    fun `no tip ever leaks the dishonest percent framing`() {
        // D4：效应量按 d=0.65 讲，不得写成被否决的"成功率提升"百分比假口径。
        // needle 拆写，免得这行本身成为被禁字符串的命中点（全仓 grep 应当一个都没有）。
        val percent = "65" + "%"
        val claim = "成功率提升 " + "65" + "%"
        StudyTips.all.forEach { tip ->
            assertFalse("${tip.id} 文案泄漏了百分比假口径", tip.text.contains(percent))
            assertFalse("${tip.id} 证据泄漏了百分比假口径", tip.evidence.contains(percent))
            assertFalse("${tip.id} 复活了成功率提升的说法", tip.text.contains(claim))
        }
    }
}
