package com.studykit.ui.study

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.studykit.StudyKitApp
import com.studykit.data.entity.OcrCorrection
import com.studykit.data.entity.Question
import com.studykit.data.question.QuestionType
import com.studykit.util.ocr.OcrLayoutJson
import com.studykit.util.ocr.OcrPageStore
import java.io.File
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONArray

/**
 * 「待校对题目」页的 ViewModel（v3.8）。
 *
 * 只做四件事：列待校对、确认无误、删除、保存编辑。页面上的原图回看走 [OcrPageStore]，
 * 不把图片读进状态（大图会拖垮重组）。
 */
class OcrReviewViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as StudyKitApp).container
    private val repository = container.questionRepository

    val pending: StateFlow<List<Question>> = repository.observePendingReview()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val pendingCount: StateFlow<Int> = repository.observePendingReviewCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    fun pageFile(question: Question): File? =
        OcrPageStore.resolve(getApplication(), question.ocrPagePath)

    /** v3.9：公式/图形裁片文件（按 ocr_layout_json 里的相对路径解析），无效项自动跳过。 */
    fun cropFiles(question: Question): List<File> {
        val meta = OcrLayoutJson.parse(question.ocrLayoutJson)
        return (meta.formulaCrops + meta.figureCrops)
            .mapNotNull { OcrPageStore.resolve(getApplication(), it) }
    }

    fun confirmReviewed(id: Long) {
        viewModelScope.launch { repository.markReviewed(id) }
    }

    fun delete(question: Question) {
        viewModelScope.launch {
            repository.delete(question.id)
            OcrPageStore.delete(getApplication(), question.ocrPagePath)
        }
    }

    /**
     * 保存编辑并清掉 needsReview。答案解析规则与 [applyReviewEdit] 共用，
     * 返回 false 表示答案格式不合法（页面据此提示，不要静默吞掉）。
     */
    fun saveEdit(
        question: Question,
        stem: String,
        options: List<String>,
        answerText: String,
        explanation: String,
        onResult: (Boolean) -> Unit,
    ) {
        val updated = applyReviewEdit(question, stem, options, answerText, explanation)
        if (updated == null) {
            onResult(false)
            return
        }
        viewModelScope.launch {
            val ok = repository.update(updated)
            if (ok) {
                // v3.9：只记「真的改了内容」的编辑，确认无误/原样保存不产生噪音
                runCatching {
                    buildCorrection(question, updated, System.currentTimeMillis())
                        ?.let { container.ocrCorrectionRepository.record(it) }
                }
            }
            onResult(ok)
        }
    }
}

/**
 * 校对页编辑的**纯逻辑**：把表单值写回 Question，答案按题型解析。
 * 返回 null = 表单不合法（选择题答案不是合法字母、填空没答案等），调用方必须提示而不是落库。
 */
internal fun applyReviewEdit(
    question: Question,
    stem: String,
    options: List<String>,
    answerText: String,
    explanation: String,
): Question? {
    val cleanedStem = stem.trim()
    if (cleanedStem.isEmpty()) return null
    val cleanedOptions = options.map { it.trim() }.filter { it.isNotEmpty() }
    var type = question.typeEnum
    var indices = emptyList<Int>()
    var accepted = emptyList<String>()

    when (type) {
        QuestionType.SINGLE, QuestionType.MULTIPLE -> {
            if (cleanedOptions.size < 2) return null
            indices = answerText.uppercase()
                .filter { it in 'A'..'H' }
                .map { it - 'A' }
                .distinct()
                .filter { it in cleanedOptions.indices }
            if (indices.isEmpty()) return null
            if (indices.size > 1) type = QuestionType.MULTIPLE else type = QuestionType.SINGLE
        }
        QuestionType.TRUE_FALSE -> {
            val text = answerText.trim().lowercase()
            val value = when (text) {
                "正确", "对", "是", "√", "true", "t", "0" -> 0
                "错误", "错", "否", "×", "false", "f", "1" -> 1
                else -> return null
            }
            indices = listOf(value)
        }
        QuestionType.FILL, QuestionType.CALCULATION -> {
            accepted = answerText.split(';', '；', ',', '，', '、')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
            if (accepted.isEmpty()) return null
        }
        QuestionType.SHORT_ANSWER, QuestionType.ESSAY, QuestionType.CASE_ANALYSIS -> {
            val reference = answerText.trim()
            if (reference.isEmpty()) return null
            accepted = listOf(reference)
        }
    }

    val finalOptions = when (type) {
        QuestionType.TRUE_FALSE -> listOf("正确", "错误")
        else -> cleanedOptions
    }
    val answerIndex = indices.firstOrNull() ?: 0
    val indicesJson = if (type == QuestionType.MULTIPLE) JSONArray(indices.sorted()).toString() else "[]"
    val acceptedJson = JSONArray(accepted).toString()
    return question.copy(
        stem = cleanedStem,
        optionsJson = JSONArray(finalOptions).toString(),
        answerIndex = answerIndex,
        answerIndicesJson = indicesJson,
        answerTextJson = acceptedJson,
        explanation = explanation.trim(),
        questionType = type.storageValue,
        needsReview = false,
    )
}

/** 答案摘要：只用于纠错日志对比，不参与评分。 */
internal fun answerSummary(question: Question): String {
    val indices = question.correctIndices().sorted()
    if (indices.isNotEmpty()) {
        return indices.joinToString("") { ('A' + it).toString() }
    }
    val accepted = question.acceptedAnswers()
    if (accepted.isNotEmpty()) return accepted.joinToString(";")
    return question.referenceAnswer()
}

/**
 * v3.9 纠错记录：before/after 内容完全一致返回 null（用户只是确认，不算纠错）。
 * 纯函数，单测直接钉住「哪些字段变了才记」。
 */
internal fun buildCorrection(before: Question, after: Question, now: Long): OcrCorrection? {
    val changed = before.stem != after.stem ||
        before.optionsJson != after.optionsJson ||
        before.answerIndex != after.answerIndex ||
        before.answerIndicesJson != after.answerIndicesJson ||
        before.answerTextJson != after.answerTextJson ||
        before.explanation != after.explanation ||
        before.questionType != after.questionType
    if (!changed) return null
    return OcrCorrection(
        questionId = before.id,
        createdAt = now,
        beforeStem = before.stem,
        afterStem = after.stem,
        beforeAnswer = answerSummary(before),
        afterAnswer = answerSummary(after),
        ocrConfidence = before.ocrConfidence,
        sourcePage = before.ocrPagePath,
    )
}

