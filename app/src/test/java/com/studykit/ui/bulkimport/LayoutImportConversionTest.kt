package com.studykit.ui.bulkimport

import com.studykit.data.question.QuestionType
import com.studykit.util.ocr.layout.QuestionLayoutParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 规则切题 -> 导入条目的转换红线：
 * 答案必须能解析成合法题型；缺答案的不收；低置信度/公式提示落 needsReview；
 * 来源页路径与页尺寸写进 ocrLayoutJson，供校对页回看。
 */
class LayoutImportConversionTest {

    private fun layoutQuestion(
        stem: String = "1. 1+1=?",
        options: List<String> = listOf("1", "2", "3"),
        answer: String = "B",
        confidence: Float = 0.93f,
        formulaHints: List<String> = emptyList(),
        sourceLine: Int = 1,
    ) = QuestionLayoutParser.LayoutQuestion(
        stem = stem,
        options = options,
        answerText = answer,
        analysis = "解析",
        sourceLine = sourceLine,
        endLine = sourceLine + 2,
        confidence = confidence,
        formulaHints = formulaHints,
        warnings = emptyList(),
    )

    @Test
    fun choiceQuestionBecomesSingleChoice() {
        val result = buildLayoutPlan(
            QuestionLayoutParser.LayoutResult(listOf(layoutQuestion()), 0, emptyList()),
            pagePath = "ocr_pages/a.jpg",
            pageWidth = 800,
            pageHeight = 1000,
        )
        assertEquals(1, result.plan.items.size)
        val item = result.plan.items.single() as com.studykit.util.importer.ImportItem.Question
        assertEquals(QuestionType.SINGLE, item.type)
        assertEquals(1, item.answerIndex)
        assertEquals(0.93f, item.ocrConfidence, 1e-6f)
        assertFalse(item.needsReview)
        assertTrue(item.ocrPagePath.contains("ocr_pages/a.jpg"))
        assertTrue(item.ocrLayoutJson.contains("pagePath"))
        assertTrue(result.lowConfidenceLines.isEmpty())
    }

    @Test
    fun multiLetterAnswerBecomesMultipleChoice() {
        val q = layoutQuestion(answer = "AC")
        val item = buildLayoutPlan(
            QuestionLayoutParser.LayoutResult(listOf(q), 0, emptyList()),
            "ocr_pages/b.jpg", 800, 1000,
        ).plan.items.single() as com.studykit.util.importer.ImportItem.Question
        assertEquals(QuestionType.MULTIPLE, item.type)
        assertEquals(listOf(0, 2), item.answerIndices)
    }

    @Test
    fun fillAnswerWithoutOptionsBecomesFill() {
        val q = layoutQuestion(stem = "水的化学式是？", options = emptyList(), answer = "H2O")
        val item = buildLayoutPlan(
            QuestionLayoutParser.LayoutResult(listOf(q), 0, emptyList()),
            "", 0, 0,
        ).plan.items.single() as com.studykit.util.importer.ImportItem.Question
        assertEquals(QuestionType.FILL, item.type)
        assertEquals(listOf("H2O"), item.acceptedAnswers)
    }

    @Test
    fun trueFalseAnswerWithoutOptionsBecomesTrueFalse() {
        val q = layoutQuestion(stem = "力改变运动状态。", options = emptyList(), answer = "正确")
        val item = buildLayoutPlan(
            QuestionLayoutParser.LayoutResult(listOf(q), 0, emptyList()),
            "", 0, 0,
        ).plan.items.single() as com.studykit.util.importer.ImportItem.Question
        assertEquals(QuestionType.TRUE_FALSE, item.type)
        assertEquals(0, item.answerIndex)
    }

    @Test
    fun missingOrInvalidAnswerIsSkipped() {
        val bad = layoutQuestion(answer = "")
        val result = buildLayoutPlan(
            QuestionLayoutParser.LayoutResult(listOf(bad), 1, listOf("缺答案")),
            "", 0, 0,
        )
        assertTrue(result.plan.items.isEmpty())
        assertEquals(1, result.plan.rejected.size + 1)
    }

    @Test
    fun lowConfidenceAndFormulaHintForceReview() {
        val q = layoutQuestion(
            confidence = 0.55f,
            formulaHints = listOf("f(x)=x^2+1"),
            sourceLine = 4,
        )
        val result = buildLayoutPlan(
            QuestionLayoutParser.LayoutResult(listOf(q), 0, emptyList()),
            "ocr_pages/c.jpg", 700, 900,
        )
        val item = result.plan.items.single() as com.studykit.util.importer.ImportItem.Question
        assertTrue(item.needsReview)
        assertEquals(setOf(4), result.lowConfidenceLines)
        assertTrue(item.ocrLayoutJson.contains("f(x)"))
    }

    @Test
    fun layoutNoteMentionsQuestionCountAndSkippedAnswers() {
        val result = buildLayoutPlan(
            QuestionLayoutParser.LayoutResult(listOf(layoutQuestion()), 2, listOf("有 2 道题没找到答案，未自动导入")),
            "", 0, 0,
        )
        val note = buildLayoutNote(report = null, result = result)
        assertNotNull(note)
        assertTrue(note!!.contains("智能切题 1 道"))
        assertTrue(note.contains("没找到答案"))
    }
}
