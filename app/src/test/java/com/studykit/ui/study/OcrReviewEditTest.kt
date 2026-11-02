package com.studykit.ui.study

import com.studykit.data.entity.Question
import com.studykit.data.question.QuestionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 校对页编辑的纯逻辑：答案按题型解析、非法答案不落库、保存后清 needs_review。
 * 这些规则直接决定「用户改过的题会不会带着错误答案回到复习」，所以用字面量钉住。
 */
class OcrReviewEditTest {

    private fun question(
        type: QuestionType = QuestionType.SINGLE,
        options: List<String> = listOf("甲", "乙", "丙"),
        answerIndex: Int = 0,
    ): Question = Question(
        uuid = "u1",
        subject = "数学",
        stem = "旧题干",
        optionsJson = org.json.JSONArray(options).toString(),
        answerIndex = answerIndex,
        explanation = "",
        questionType = type.storageValue,
        needsReview = true,
        ocrConfidence = 0.61f,
    )

    @Test
    fun singleChoiceEditWithLetterAnswerClearsReview() {
        val q = question()
        val updated = applyReviewEdit(q, "新题干", listOf("甲", "乙", "丙"), "B", "解析")
        assertNotNull(updated)
        assertEquals("新题干", updated!!.stem)
        assertEquals(1, updated.answerIndex)
        assertEquals(QuestionType.SINGLE, updated.typeEnum)
        assertFalse(updated.needsReview)
        assertEquals("解析", updated.explanation)
        assertEquals(0.61f, updated.ocrConfidence, 1e-6f)
    }

    @Test
    fun choosingMultipleLettersSwitchesToMultiple() {
        val q = question()
        val updated = applyReviewEdit(q, "多选", listOf("甲", "乙", "丙"), "AC", "")
        assertNotNull(updated)
        assertEquals(QuestionType.MULTIPLE, updated!!.typeEnum)
        assertEquals(setOf(0, 2), updated.correctIndices())
    }

    @Test
    fun invalidChoiceAnswerIsRejected() {
        assertNull(applyReviewEdit(question(), "题干", listOf("甲", "乙"), "Z", ""))
        assertNull(applyReviewEdit(question(), "题干", listOf("甲", "乙"), "", ""))
        assertNull(applyReviewEdit(question(), "  ", listOf("甲", "乙"), "A", ""))
    }

    @Test
    fun trueFalseEditParsesChineseAnswer() {
        val q = question(type = QuestionType.TRUE_FALSE, options = listOf("正确", "错误"))
        val updated = applyReviewEdit(q, "判断题", emptyList(), "错误", "")
        assertNotNull(updated)
        assertEquals(QuestionType.TRUE_FALSE, updated!!.typeEnum)
        assertEquals(1, updated.answerIndex)
    }

    @Test
    fun fillEditSplitsAcceptedAnswers() {
        val q = question(type = QuestionType.FILL, options = emptyList())
        val updated = applyReviewEdit(q, "填空", emptyList(), "5/6;0.833", "")
        assertNotNull(updated)
        assertEquals(QuestionType.FILL, updated!!.typeEnum)
        assertEquals(listOf("5/6", "0.833"), updated.acceptedAnswers())
    }

    @Test
    fun shortAnswerEditKeepsReference() {
        val q = question(type = QuestionType.SHORT_ANSWER, options = emptyList())
        val updated = applyReviewEdit(q, "简答", emptyList(), "先求导，再代入", "")
        assertNotNull(updated)
        assertEquals(QuestionType.SHORT_ANSWER, updated!!.typeEnum)
        assertEquals("先求导，再代入", updated.referenceAnswer())
    }
}
