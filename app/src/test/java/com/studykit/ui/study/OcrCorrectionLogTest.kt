package com.studykit.ui.study

import com.studykit.data.entity.Question
import com.studykit.data.question.QuestionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** v3.9 纠错日志纯逻辑：只有内容真变了才记；答案摘要按题型取。 */
class OcrCorrectionLogTest {

    private fun question(
        id: Long = 9,
        stem: String = "已知函数 f(x)=x^2+1",
        options: List<String> = listOf("3", "4", "5", "6"),
        answerIndex: Int = 2,
        type: QuestionType = QuestionType.SINGLE,
        confidence: Float = 0.55f,
    ): Question = Question(
        id = id,
        uuid = "u-" + id,
        subject = "数学",
        stem = stem,
        optionsJson = org.json.JSONArray(options).toString(),
        answerIndex = answerIndex,
        explanation = "",
        questionType = type.storageValue,
        needsReview = true,
        ocrConfidence = confidence,
        ocrPagePath = "ocr_pages/a.jpg",
    )

    @Test
    fun unchangedConfirmProducesNoCorrection() {
        val q = question()
        assertNull(buildCorrection(q, q.copy(needsReview = false), 1L))
    }

    @Test
    fun changedAnswerIsRecorded() {
        val before = question()
        val after = before.copy(answerIndex = 1, needsReview = false)
        val row = buildCorrection(before, after, 100L)
        assertNotNull(row)
        assertEquals(9L, row!!.questionId)
        assertEquals("C", row.beforeAnswer)
        assertEquals("B", row.afterAnswer)
        assertEquals(0.55f, row.ocrConfidence, 1e-6f)
        assertEquals("ocr_pages/a.jpg", row.sourcePage)
        assertEquals(100L, row.createdAt)
    }

    @Test
    fun changedStemIsRecordedEvenIfAnswerSame() {
        val before = question()
        val after = before.copy(stem = "已知函数 f(x)=x^2+2", needsReview = false)
        val row = buildCorrection(before, after, 1L)!!
        assertEquals("已知函数 f(x)=x^2+1", row.beforeStem)
        assertEquals("已知函数 f(x)=x^2+2", row.afterStem)
    }

    @Test
    fun fillAnswerSummaryJoinsAcceptedAnswers() {
        val q = question(type = QuestionType.FILL, options = emptyList(), answerIndex = 0)
            .copy(answerTextJson = "[" + "\"5/6\"" + "," + "\"0.833\"" + "]")
        assertEquals("5/6;0.833", answerSummary(q))
    }
}
