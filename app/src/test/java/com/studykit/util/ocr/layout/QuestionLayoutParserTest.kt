package com.studykit.util.ocr.layout

import com.studykit.util.ocr.OcrBox
import com.studykit.util.ocr.OcrLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 规则切题器的红线：
 * - 单行内联选项、逐行选项、多行题干、答案/解析；
 * - 双栏阅读顺序（先左后右，不被 y 坐标串行）；
 * - 缺答案的题不自动收，计数交给导入提示；
 * - 公式行能被挑出来做「建议对照原图」。
 *
 * 所有输入都是构造的 OcrLine + 简单框，不依赖 Android，也不跑 OCR。
 */
class QuestionLayoutParserTest {

    private fun line(text: String, y: Int, x: Int = 40, score: Float = 0.95f, width: Int = 360): OcrLine =
        OcrLine(
            text = text,
            score = score,
            box = OcrBox(x, y, x + width, y + 26),
        )

    @Test
    fun parsesInlineOptionsWithAnswerAndAnalysis() {
        val lines = listOf(
            line("1. 已知函数 f(x)=x^2+1，求 f(2) 的值。", 100),
            line("A. 4    B. 5    C. 6    D. 9", 140),
            line("答案：B", 180),
            line("解析：f(2)=2^2+1=5。", 220),
        )
        val result = QuestionLayoutParser.parse(lines, pageWidth = 800, pageHeight = 1000)
        assertEquals(1, result.questions.size)
        val q = result.questions.single()
        assertTrue("stem=" + q.stem, q.stem.contains("已知函数"))
        assertEquals(listOf("4", "5", "6", "9"), q.options)
        assertEquals("B", q.answerText)
        assertTrue("analysis=" + q.analysis, q.analysis.contains("f(2)"))
        assertTrue("formula should be hinted: " + q.formulaHints, q.formulaHints.isNotEmpty())
    }

    @Test
    fun parsesMultiLineQuestionWithOptionLines() {
        val lines = listOf(
            line("2. 下列关于力的说法正确的是", 100),
            line("A. 力是维持运动的原因", 140),
            line("B. 力可以改变物体运动状态", 180),
            line("C. 物体不受力时一定静止", 220),
            line("D. 力的大小可用弹簧测力计测量", 260),
            line("答案：B", 300),
        )
        val result = QuestionLayoutParser.parse(lines, 800, 1000)
        assertEquals(1, result.questions.size)
        val q = result.questions.single()
        assertEquals(4, q.options.size)
        assertEquals("B", q.answerText)
        assertTrue(q.options[1].contains("改变物体运动状态"))
    }

    @Test
    fun keepsMultipleChoiceLetterAnswerForLaterConversion() {
        val lines = listOf(
            line("3. 下列属于可再生能源的是", 100),
            line("A. 煤", 140),
            line("B. 风能", 180),
            line("C. 太阳能", 220),
            line("D. 石油", 260),
            line("答案：BC", 300),
        )
        val q = QuestionLayoutParser.parse(lines, 800, 1000).questions.single()
        assertEquals("BC", q.answerText)
    }

    @Test
    fun skipsQuestionWithoutAnswerAndCountsIt() {
        val lines = listOf(
            line("4. 这是一道没有答案的题", 100),
            line("A. 选项甲", 140),
            line("B. 选项乙", 180),
        )
        val result = QuestionLayoutParser.parse(lines, 800, 1000)
        assertTrue(result.questions.isEmpty())
        assertEquals(1, result.skippedMissingAnswer)
        assertTrue(result.notes.any { it.contains("没找到答案") })
    }

    @Test
    fun readsTwoColumnsLeftThenRight() {
        val left = listOf(
            line("1. 左栏第一题", 100, x = 30),
            line("A. 甲", 140, x = 30),
            line("B. 乙", 180, x = 30),
            line("答案：A", 220, x = 30),
        )
        val right = listOf(
            line("2. 右栏第二题", 100, x = 500),
            line("A. 丙", 140, x = 500),
            line("B. 丁", 180, x = 500),
            line("答案：B", 220, x = 500),
        )
        // 输入刻意按 y 行交错（左1/右1/左2/右2...），验证不是按原顺序硬吃
        val interleaved = listOf(left[0], right[0], left[1], right[1], left[2], right[2], left[3], right[3])
        val result = QuestionLayoutParser.parse(interleaved, pageWidth = 900, pageHeight = 1000)
        assertEquals(2, result.questions.size)
        assertTrue(result.questions[0].stem.contains("左栏"))
        assertTrue(result.questions[1].stem.contains("右栏"))
        assertEquals("A", result.questions[0].answerText)
        assertEquals("B", result.questions[1].answerText)
    }

    @Test
    fun joinsEnglishContinuationWithSpace() {
        val lines = listOf(
            line("5. Read the passage", 100),
            line("and answer the question.", 140),
            line("A. True", 180),
            line("B. False", 220),
            line("答案：A", 260),
        )
        val q = QuestionLayoutParser.parse(lines, 800, 1000).questions.single()
        assertEquals("Read the passage and answer the question.", q.stem)
    }

    @Test
    fun dropsPageNumberAtBottom() {
        val lines = listOf(
            line("6. 正常题目", 100),
            line("A. 甲", 140),
            line("B. 乙", 180),
            line("答案：A", 220),
            line("第 3 页", 980),
        )
        val result = QuestionLayoutParser.parse(lines, 800, 1000)
        assertEquals(1, result.questions.size)
    }

    @Test
    fun formulaDetectorSeparatesNaturalLanguageFromMath() {
        assertTrue(FormulaDetector.looksLikeFormula("f(x)=x^2+1，求 f(2)"))
        assertTrue(FormulaDetector.looksLikeFormula("1/2 + 1/3 = 5/6"))
        assertTrue(FormulaDetector.looksLikeFormula("H2O"))
        assertTrue(!FormulaDetector.looksLikeFormula("这是一段普通的语文句子，没有公式。"))
        assertTrue(!FormulaDetector.looksLikeFormula("Read the passage and answer the question."))
    }
}
