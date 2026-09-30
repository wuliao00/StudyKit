package com.studykit.ui.mistake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 错题「重做式复习」的纯逻辑单测（v2.5 S3）。
 *
 * 钉的是四件事，每件各挡一种塌法：
 *  - [nextRedoPhase]：阶段机只有「重做 → 对照」和「对照 → 重做」两条边，重复按同一颗钮不许跳阶。
 *  - [encodeRedoPhase] / [decodeRedoPhase]：`rememberSaveable` 存的串与恢复口径 ——
 *    读不出来的串一律回 [RedoPhase.REDO]（默认遮住答案），错串绝不"顺手当成已展开"。
 *  - [splitForRedo]：把刷题收录那种「题干 + 选项 + 正确答案 + 解析」的正文按**标记行**切成两半，
 *    切完两段合起来必须还盖得住原文（不丢字），且题干里出现「答案」两字不误伤。
 *  - [buildRedoGate]：到底哪一段被遮、遮住时页面上少显示了什么；备注是机器写的 `qid:` 标记时
 *    不拿它当"解析"遮起来（那只是题号，遮了既无信息量又让人以为解析没了）。
 *
 * 文案（[redoCoverHint] / [selfExplainPrompt] / [redoHistoryBoundary]）也在纯函数层断言：
 * 这几句是本轮唯一的"证据口径"，被随手改成空话或改成暗示算法排期，只能靠测试拦下来。
 *
 * Compose 层（点按钮真的把解析展开、转屏不丢）不在本文件范围内 —— 本仓 Robolectric
 * 有跨类污染前科，UI 由真机走查验。
 */
class RedoFlowTest {

    /** 与 `StudyViewModel.addMistakeIfAbsent` 生成的正文同一份形状，改那边要同步改这里 */
    private val practiceContent = buildString {
        appendLine("题干：下列函数在 x = 0 处连续的是")
        appendLine("A. f(x) = x²")
        appendLine("B. f(x) = |x| / x")
        appendLine("C. f(x) = sin x")
        appendLine("正确答案：C. f(x) = sin x")
        append("解析：B 在 x = 0 处没有定义，C 在实数域处处连续。")
    }

    private fun gate(
        content: String = practiceContent,
        note: String = "qid:12",
        phase: RedoPhase = RedoPhase.REDO,
    ) = buildRedoGate(content = content, note = note, phase = phase)

    // ── 阶段机 ────────────────────────────────────────────────────────────

    @Test fun `重做阶段点我重做了一遍进入对照阶段`() {
        assertEquals(RedoPhase.CHECK, nextRedoPhase(RedoPhase.REDO, RedoAction.ConfirmedRedo))
    }

    @Test fun `对照阶段点重新遮住答案回到重做阶段`() {
        assertEquals(RedoPhase.REDO, nextRedoPhase(RedoPhase.CHECK, RedoAction.CoverAnswer))
        // 重做阶段里没有可收回的东西，这枚钮在该阶段不该把用户推进对照阶段
        assertEquals(RedoPhase.REDO, nextRedoPhase(RedoPhase.REDO, RedoAction.CoverAnswer))
    }

    @Test fun `同一颗钮连点不许跳阶`() {
        // 阶段只有两格，重复按「我重做了一遍」不该按出一个第三态、也不该把已展开的收回去
        assertEquals(RedoPhase.CHECK, nextRedoPhase(RedoPhase.CHECK, RedoAction.ConfirmedRedo))
        assertEquals(RedoPhase.REDO, nextRedoPhase(RedoPhase.REDO, RedoAction.CoverAnswer))
    }

    @Test fun `阶段编解码往返一致且未知串回到遮住答案那一侧`() {
        for (phase in RedoPhase.entries) {
            assertEquals(phase, decodeRedoPhase(encodeRedoPhase(phase)))
        }
        // 存档里读到没见过的串（旧版本残留 / 手改）时默认遮着：宁可让用户多点一次按钮
        assertEquals(RedoPhase.REDO, decodeRedoPhase(null))
        assertEquals(RedoPhase.REDO, decodeRedoPhase(""))
        assertEquals(RedoPhase.REDO, decodeRedoPhase("WHATEVER"))
        assertEquals(RedoPhase.REDO, decodeRedoPhase("redo"))
    }

    // ── 正文切分 ─────────────────────────────────────────────────────────

    @Test fun `刷题正文在正确答案那一行切开`() {
        val split = splitForRedo(practiceContent)
        assertTrue(split.stem.contains("题干：下列函数在 x = 0 处连续的是"))
        assertTrue("选项是题面的一部分，重做时看得见", split.stem.contains("B. f(x) = |x| / x"))
        assertFalse("切点之前不许漏出答案", split.stem.contains("正确答案"))
        assertTrue(split.answerKey.startsWith("正确答案："))
        assertTrue(split.answerKey.contains("解析：B 在 x = 0 处没有定义"))
    }

    @Test fun `切开两段合起来不丢原文的字`() {
        // 展开态要"保留原有全部信息"，靠的是两段是原文的连续前后块，不是重写一遍
        val split = splitForRedo(practiceContent)
        val left = practiceContent.filterNot { it.isWhitespace() }.length
        val right = (split.stem + split.answerKey).filterNot { it.isWhitespace() }.length
        assertEquals(left, right)
    }

    @Test fun `半角冒号与方头括号写法也算答案标记`() {
        assertTrue(splitForRedo("题干：略\n答案: B").answerKey.startsWith("答案:"))
        assertTrue(splitForRedo("题干：略\n【答案】B").answerKey.startsWith("【答案】"))
        assertTrue(splitForRedo("题干：略\n答案解析：见课本第三章").answerKey.startsWith("答案解析："))
        // 只有解析没有答案（问答题常见）同样要遮住
        assertTrue(splitForRedo("题干：略\n解析：先定正负").answerKey.startsWith("解析："))
    }

    @Test fun `题干里出现答案两个字不误伤`() {
        // 这行以「请判断」开头，只是句中带"答案"，不是答案标记行；
        // 「解：」同理 —— 那是题干/OCR 里的解题过程开头，不是答案段
        val content = "请判断下列哪个是正确答案\n解：设 x = 0 代入原式"
        val split = splitForRedo(content)
        assertEquals("", split.answerKey)
        assertEquals(content, split.stem)
    }

    @Test fun `标记必须落在行首才算答案段`() {
        // 同一行中间的「答案：」是题面叙述（如"填空处的答案：见解析"），从它切会把题干腰斩
        val split = splitForRedo("题目要求：把括号里的答案：补全后再检查")
        assertEquals("", split.answerKey)
    }

    @Test fun `整段正文以标记开头时题干为空`() {
        val split = splitForRedo("解析：这道题考的是定义域")
        assertEquals("", split.stem)
        assertEquals("解析：这道题考的是定义域", split.answerKey)
    }

    @Test fun `没有标记的正文整段都算题干`() {
        val split = splitForRedo("把 note 里这段手写内容当作题干")
        assertEquals("把 note 里这段手写内容当作题干", split.stem)
        assertEquals("", split.answerKey)
    }

    // ── 备注是不是机器标记 ───────────────────────────────────────────────

    @Test fun `刷题收录的 qid 标记不算可遮的解析`() {
        assertTrue(isMachineNote("qid:12"))
        assertTrue(isMachineNote("  qid:34  "))
        assertTrue(isMachineNote("QID:5"))
        assertFalse(isMachineNote("第二步把定义域漏了"))
        assertFalse(isMachineNote(""))
    }

    // ── 门：哪一段被遮、展开后露出什么 ───────────────────────────────────

    @Test fun `默认进入详情页时答案与解析是遮住的`() {
        val g = gate()
        assertEquals(RedoPhase.REDO, g.phase)
        assertEquals(ConcealedSection.ANSWER_KEY, g.concealed)
        assertTrue("重做阶段确有内容没显示", g.isCovered)
        assertTrue(g.hasReveal)
        assertFalse(g.answerKeyVisible)
        assertTrue("题干始终可见", g.stemText.contains("题干："))
        assertTrue("qid 标记不是解析，不遮", g.noteVisible)
    }

    @Test fun `展开之后答案与解析可见且不算遮住`() {
        val g = gate(phase = RedoPhase.CHECK)
        assertTrue(g.answerKeyVisible)
        assertFalse("展开态没有任何东西被藏起来", g.isCovered)
        assertTrue(g.noteVisible)
    }

    @Test fun `没有标记的正文退回遮备注`() {
        // 拍照录入把用户手写内容放进 content、note 留空；这里给一条自己写的错因备注：
        // 正文读不出答案段，就把备注当作"解析"遮起来，仍是先看题、后对内容
        val g = gate(content = "题干：手抄的题目", note = "我在第二步漏了定义域")
        assertEquals(ConcealedSection.NOTE, g.concealed)
        assertTrue(g.isCovered)
        assertFalse(g.noteVisible)
        assertTrue(g.answerKeyVisible) // 压根没有答案段，谈不上遮
        assertEquals("题干：手抄的题目", g.stemText)
    }

    @Test fun `无标记且备注是机器标记或空时没有任何可遮内容`() {
        for (note in listOf("", "   ", "qid:9")) {
            val g = gate(content = "题干：手抄的题目", note = note)
            assertEquals(ConcealedSection.NONE, g.concealed)
            assertFalse("没有可遮的东西就不许摆出已遮住的架势", g.hasReveal)
            assertFalse(g.isCovered)
            assertTrue(g.noteVisible)
        }
    }

    @Test fun `答案段优先于备注段被遮`() {
        // 两处都可能带解析时只遮一处：答案与解析是标准答案，优先级高于用户自己的备注
        val g = gate(content = practiceContent, note = "第二步漏了定义域")
        assertEquals(ConcealedSection.ANSWER_KEY, g.concealed)
        assertTrue("备注跟着一起展开才有对照价值", g.noteVisible)
    }

    @Test fun `遮住哪一段的说明只在真遮住的时段给`() {
        assertEquals("答案与解析已遮住", coveredLabel(ConcealedSection.ANSWER_KEY))
        assertEquals("备注已遮住", coveredLabel(ConcealedSection.NOTE))
        assertNull("无可遮内容时必须给不出这句", coveredLabel(ConcealedSection.NONE))
    }

    // ── 文案守卫 ─────────────────────────────────────────────────────────

    @Test fun `折叠态提示先重做再对答案`() {
        val hint = redoCoverHint()
        assertTrue(hint.contains("重做"))
        val redoAt = hint.indexOf("重做")
        val checkAt = hint.indexOf("对答案")
        assertTrue("顺序不能反：先重做，后对答案", checkAt >= 0 && redoAt < checkAt)
        assertTrue(hint.contains("先"))
        assertTrue(hint.contains("再"))
    }

    @Test fun `自我解释提示问的是错因而不是空话`() {
        val prompt = selfExplainPrompt()
        assertTrue(prompt.contains("错在哪一步"))
        assertTrue(prompt.contains("误导"))
        assertTrue(prompt.contains("线索"))
        // 三问齐备（漏一问就退化成"再读一遍解析"），且是问句
        assertEquals(3, prompt.count { it == '？' })
    }

    @Test fun `自我解释提示不许宣称任何数字效应或施压措辞`() {
        val prompt = selfExplainPrompt()
        assertFalse(prompt.any { it.isDigit() })
        assertFalse(prompt.contains("%"))
        for (word in listOf("必须", "否则", "不然", "警告", "完了")) {
            assertFalse("措辞不许带威胁或绝对化：$word", prompt.contains(word))
        }
    }

    @Test fun `边界说明不许暗示按重做记录排期`() {
        val boundary = redoHistoryBoundary()
        assertTrue(boundary.contains("没有记录"))
        for (phrase in listOf("已按", "自动", "智能", "已为你")) {
            assertFalse("本轮没有逐次重做历史，不许出现暗示：$phrase", boundary.contains(phrase))
        }
        assertFalse(boundary.any { it.isDigit() })
    }

    @Test fun `按钮字样说的是做完这件事本身`() {
        assertEquals("我重做了一遍", redoConfirmLabel())
        assertEquals("重新遮住答案", redoCoverAgainLabel())
        assertEquals("答案与解析", answerSectionTitle())
    }
}
