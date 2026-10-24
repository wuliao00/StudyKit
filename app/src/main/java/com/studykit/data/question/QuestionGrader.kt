package com.studykit.data.question

import com.studykit.data.entity.Question
import org.json.JSONArray

/** 一次作答的用户输入；与旧 UI 的单选 `selected: Int` 一一对应，同时容纳多选/填空/主观自评 */
sealed interface QuizResponse {
    data class Single(val index: Int) : QuizResponse
    data class Multiple(val indices: Set<Int>) : QuizResponse
    data class Fill(val text: String) : QuizResponse

    /** 主观题（简答/论述/材料）：没有可自动判的答案，note 只是用户草稿，不参与判分 */
    data class SelfAssess(val note: String = "") : QuizResponse

    /**
     * 落 `practice_records.selected` 的兼容值（该列是 INTEGER，不能改 schema）：
     * 单选/多选取**最小**选中下标（多选只留一个代表位），其它 -1。
     */
    val firstIndex: Int
        get() = when (this) {
            is Single -> index
            is Multiple -> indices.minOrNull() ?: -1
            is Fill -> -1
            is SelfAssess -> -1
        }
}

enum class OptionGrade { CORRECT, WRONG, MISSED }

/**
 * 判分结果。UI 按 [optionStates] 着色、按 [needsSelfJudge] 决定是否显示自评按钮，
 * 多选的部分正确用 [partialCorrect] / [partialTotal] 展示，而不是把「部分对」冒充成通过。
 *
 * [gradingMode] 由题型派生；[selfJudged] 表示这一次最终结果来自用户自评，而不是自动判分。
 * ViewModel 据此决定要不要写 review_log（自评不写，避免污染校准流）。
 */
data class GradeResult(
    val correct: Boolean,
    val needsSelfJudge: Boolean = false,
    val optionStates: Map<Int, OptionGrade>,
    val userAnswerText: String,
    val correctAnswerText: String,
    val partialCorrect: Int = 0,
    val partialTotal: Int = 0,
    val gradingMode: GradingMode = GradingMode.AUTO_KEYED,
    val selfJudged: Boolean = false,
)

/**
 * 纯函数判分器：不碰数据库、不碰 Android，同一输入永远同一输出。
 *
 * 口径：
 * - 单选/判断：单下标精确匹配；判断的答案文本用选项原文（正确/错误），不用 A/B。
 * - 多选：集合完全相等且非空才算对；部分正确只用于反馈。
 * - 填空/计算：规范化后与多个可接受答案逐字比较，命中任一即对；全不命中时默认错，
 *   但标 `needsSelfJudge=true` 让人工复核。
 * - 简答/主观/材料：绝不自动判，永远 `needsSelfJudge=true`、`correct=false`；
 *   参考答案只展示，不参与字符串匹配。
 */
object QuestionGrader {

    fun grade(question: Question, response: QuizResponse): GradeResult = when (question.typeEnum) {
        QuestionType.SINGLE -> gradeSingle(question, response, textualAnswer = false)
        QuestionType.TRUE_FALSE -> gradeSingle(question, response, textualAnswer = true)
        QuestionType.MULTIPLE -> gradeMultiple(question, response)
        QuestionType.FILL, QuestionType.CALCULATION -> gradeNormalized(question, response)
        QuestionType.SHORT_ANSWER,
        QuestionType.ESSAY,
        QuestionType.CASE_ANALYSIS,
        -> gradeSelfRubric(question, response)
    }

    private fun gradeSingle(
        question: Question,
        response: QuizResponse,
        textualAnswer: Boolean,
    ): GradeResult {
        val selected = (response as? QuizResponse.Single)?.index ?: -1
        val correct = selected >= 0 && selected == question.answerIndex
        val states = if (selected < 0) {
            emptyMap()
        } else {
            mapOf(selected to if (correct) OptionGrade.CORRECT else OptionGrade.WRONG)
        }
        return GradeResult(
            correct = correct,
            optionStates = states,
            userAnswerText = if (textualAnswer) optionText(question, selected) else letterOf(selected),
            correctAnswerText = if (textualAnswer) {
                optionText(question, question.answerIndex)
            } else {
                letterOf(question.answerIndex)
            },
            gradingMode = GradingMode.AUTO_KEYED,
        )
    }

    private fun gradeMultiple(question: Question, response: QuizResponse): GradeResult {
        val selected = (response as? QuizResponse.Multiple)?.indices.orEmpty()
            .filter { it >= 0 }
            .toSet()
        val correctSet = question.correctIndices()
        val states = buildMap {
            selected.forEach { index ->
                put(index, if (index in correctSet) OptionGrade.CORRECT else OptionGrade.WRONG)
            }
            correctSet.forEach { index ->
                if (index !in selected) put(index, OptionGrade.MISSED)
            }
        }
        val partialCorrect = selected.count { it in correctSet }
        return GradeResult(
            correct = selected.isNotEmpty() && selected == correctSet,
            optionStates = states,
            userAnswerText = lettersOf(selected),
            correctAnswerText = lettersOf(correctSet),
            partialCorrect = partialCorrect,
            partialTotal = correctSet.size,
            gradingMode = GradingMode.AUTO_KEYED,
        )
    }

    private fun gradeNormalized(question: Question, response: QuizResponse): GradeResult {
        val text = (response as? QuizResponse.Fill)?.text.orEmpty()
        val accepted = question.acceptedAnswers()
        val normalizedUser = normalizeFillAnswer(text)
        val matched = normalizedUser.isNotEmpty() && accepted.any { candidate ->
            val normalizedCandidate = normalizeFillAnswer(candidate)
            normalizedCandidate.isNotEmpty() && normalizedCandidate == normalizedUser
        }
        return GradeResult(
            // 可接受答案为空时也不算自动命中：数据不完整就交给用户自评，不擅自判错
            correct = matched,
            needsSelfJudge = !matched,
            optionStates = emptyMap(),
            userAnswerText = text,
            correctAnswerText = accepted.joinToString(" / "),
            gradingMode = GradingMode.AUTO_NORMALIZED,
        )
    }

    private fun gradeSelfRubric(question: Question, response: QuizResponse): GradeResult {
        val note = when (response) {
            is QuizResponse.SelfAssess -> response.note
            is QuizResponse.Fill -> response.text
            else -> ""
        }
        return GradeResult(
            correct = false,
            needsSelfJudge = true,
            optionStates = emptyMap(),
            userAnswerText = note,
            correctAnswerText = question.referenceAnswer(),
            gradingMode = GradingMode.SELF_RUBRIC,
        )
    }

    /**
     * 填空/计算答案规范化（顺序固定，别调换）：
     * 1. 全角数字/字母/常见标点转半角，全角空格转普通空格；
     * 2. 连续空白折叠成一个空格，英文转小写；
     * 3. 去掉末尾的句号/问号/感叹号/分号（可连续多个，含全角）。
     *
     * 空串规范化后仍是空串 —— 调用方必须把「空作答」当作未命中，绝不能让空答案互相匹配。
     * 计算题只支持最终答案的字符串相等；不做符号等价/CAS，也不做数值容差。
     */
    fun normalizeFillAnswer(text: String): String {
        var value = toHalfWidth(text)
            .replace(WHITESPACE, " ")
            .trim()
            .lowercase()
        while (true) {
            val stripped = value.trimEnd { it in TRAILING_PUNCTUATION }
            if (stripped == value) break
            value = stripped.trim()
        }
        return value.trim()
    }

    private fun optionText(question: Question, index: Int): String {
        val options = try {
            val array = JSONArray(question.optionsJson)
            (0 until array.length()).map { array.optString(it) }
        } catch (_: Exception) {
            emptyList()
        }
        return options.getOrNull(index) ?: when (index) {
            0 -> "正确"
            1 -> "错误"
            else -> ""
        }
    }

    private val WHITESPACE = Regex("\\s+")

    /** 句号/问号/感叹号/分号；半角与全角都列上（全角本应已被转掉，留着防御漏网字符） */
    private val TRAILING_PUNCTUATION: Set<Char> = setOf('.', '?', '!', ';', '。', '？', '！', '；')

    private fun toHalfWidth(text: String): String = buildString(text.length) {
        text.forEach { ch ->
            when (ch) {
                '\u3000' -> append(' ')
                '。' -> append('.')
                '、' -> append(',')
                '“', '”' -> append('"')
                '‘', '’' -> append('\'')
                in '\uFF01'..'\uFF5E' -> append((ch.code - 0xFEE0).toChar())
                else -> append(ch)
            }
        }
    }

    private fun letterOf(index: Int): String =
        if (index < 0) "" else ('A' + index).toString()

    private fun lettersOf(indices: Set<Int>): String =
        indices.sorted().joinToString(",") { letterOf(it) }
}
