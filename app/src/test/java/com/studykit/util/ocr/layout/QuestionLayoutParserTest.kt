package com.studykit.util.ocr.layout

import com.studykit.util.ocr.OcrBox
import com.studykit.util.ocr.OcrLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    @Test
    fun formulaLinesCarryTheirBoxesForCrops() {
        val lines = listOf(
            OcrLine(
                text = "1. 已知函数 f(x)=x^2+1，求 f(2) 的值。",
                score = 0.9f,
                box = OcrBox(40, 30, 700, 80),
            ),
            OcrLine(text = "A. 4", score = 0.9f, box = OcrBox(60, 90, 120, 120)),
            OcrLine(text = "B. 5", score = 0.9f, box = OcrBox(140, 90, 200, 120)),
            OcrLine(text = "答案：B", score = 0.9f, box = OcrBox(60, 130, 220, 160)),
        )
        val result = QuestionLayoutParser.parse(lines, pageWidth = 800, pageHeight = 1000)
        assertEquals(1, result.questions.size)
        val q = result.questions.single()
        assertTrue("formula hint missing: " + q.formulaHints, q.formulaHints.isNotEmpty())
        assertTrue("formula box missing", q.formulaBoxes.isNotEmpty())
        assertEquals(40, q.formulaBoxes.single().x0)
    }

    @Test
    fun answerKeyAtBottomFillsMissingAnswers() {
        val lines = listOf(
            line("1. 下列词语中，没有错别字的是（ ）", 20),
            line("A. 再接再厉", 60),
            line("B. 走头无路", 96),
            line("C. 迫不急待", 132),
            line("2. 计算 1+1 的值（ ）", 180),
            line("A. 1", 220),
            line("B. 2", 256),
            line("答案：1.A 2.B", 320),
        )
        val result = QuestionLayoutParser.parse(lines, 800, 1000)
        assertEquals(2, result.questions.size)
        assertEquals("A", result.questions[0].answerText)
        assertEquals("B", result.questions[1].answerText)
        assertEquals(1, result.questions[0].questionNumber)
        assertTrue(result.notes.any { it.contains("答案区补全 2") })
        assertFalse("答案行不能续进题干", result.questions[1].stem.contains("答案"))
    }

    @Test
    fun answerKeyRangeIsExpanded() {
        val lines = listOf(
            line("1. 第一题（ ）", 20),
            line("A. 甲", 60),
            line("B. 乙", 96),
            line("2. 第二题（ ）", 140),
            line("A. 甲", 180),
            line("B. 乙", 216),
            line("3. 第三题（ ）", 260),
            line("A. 甲", 300),
            line("B. 乙", 336),
            line("参考答案 1-3 ABC", 400),
        )
        val result = QuestionLayoutParser.parse(lines, 800, 1000)
        assertEquals(3, result.questions.size)
        assertEquals(listOf("A", "B", "C"), result.questions.map { it.answerText })
    }

    @Test
    fun answerKeyDoesNotStealInlineOptions() {
        // 内联选项行含数字，不能被误判成答案区
        val lines = listOf(
            line("1. 已知 f(2)（ ）", 20),
            line("A. 4    B. 5    C. 6    D. 9", 60),
            line("2. 已知 g(3)（ ）", 110),
            line("A. 1    B. 2    C. 3    D. 4", 150),
            line("答案：1.B 2.C", 220),
        )
        val result = QuestionLayoutParser.parse(lines, 800, 1000)
        assertEquals(2, result.questions.size)
        assertEquals(listOf("4", "5", "6", "9"), result.questions[0].options)
        assertEquals("B", result.questions[0].answerText)
    }

    @Test
    fun sectionHeadersEndPreviousQuestionAndAreNotStem() {
        val lines = listOf(
            line("一、选择题（每题 3 分，共 30 分）", 20),
            line("1. 第一题（ ）", 60),
            line("A. 甲", 100),
            line("B. 乙", 136),
            line("答案：A", 172),
            line("二、填空题（每题 3 分）", 220),
            line("2. 第二题（ ）", 260),
            line("A. 甲", 300),
            line("B. 乙", 336),
            line("答案：B", 372),
        )
        val result = QuestionLayoutParser.parse(lines, 800, 1000)
        assertEquals(2, result.questions.size)
        assertFalse("节标题不能进题干: " + result.questions[0].stem, result.questions[0].stem.contains("一、"))
        assertFalse("节标题不能进题干: " + result.questions[1].stem, result.questions[1].stem.contains("二、"))
        assertEquals("A", result.questions[0].answerText)
        assertEquals("B", result.questions[1].answerText)
    }

    @Test
    fun scoreLinesAreNotAppendedToStem() {
        val lines = listOf(
            line("1. 已知函数 f(2) 的值（ ）", 20),
            line("（本题 5 分）", 60),
            line("A. 4", 100),
            line("B. 5", 136),
            line("答案：B", 172),
        )
        val result = QuestionLayoutParser.parse(lines, 800, 1000)
        val q = result.questions.single()
        assertFalse("分值行不能进题干: " + q.stem, q.stem.contains("分"))
        assertEquals("B", q.answerText)
    }

    @Test
    fun ordinarySentenceStartingWithNumberedWordsIsNotSectionHeader() {
        val lines = listOf(
            line("1. 下列说法正确的是（ ）", 20),
            line("A. 甲", 60),
            line("B. 乙", 96),
            line("答案：A", 132),
            line("考生须知：请在答题卡上作答。", 180),
            line("2. 第二题（ ）", 220),
            line("A. 甲", 260),
            line("B. 乙", 296),
            line("答案：B", 332),
        )
        val result = QuestionLayoutParser.parse(lines, 800, 1000)
        assertEquals(2, result.questions.size)
        assertEquals("下列说法正确的是（ ）", result.questions[0].stem)
        assertFalse(result.questions[0].stem.contains("考生须知"))
    }

}
