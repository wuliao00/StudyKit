package com.studykit.util.importer

import com.studykit.data.question.ExamStage
import com.studykit.data.question.QuestionType

/**
 * 题目行解析（v3.3：阶段 × 题型）。
 *
 * ```
 * [阶段·学科·题型] 题干 | 选项A | 选项B … | 答案 [ | 解析 ]
 * [单选] 题干 | 选项A | 选项B … | 答案 [ | 解析 ]        // 旧格式，阶段/学科留空
 * [初中·数学·多选] 题干 | A | B | C | D | AC
 * [初中·数学·判断] 题干 | 正确 [ | 解析 ]
 * [高中·英语·填空] 题干 | 答案1;答案2 [ | 解析 ]
 * [考研·数学一·计算] 题干 | 最终答案1;最终答案2 [ | 解析 ]
 * [考研·政治·简答] 题干 | 参考答案 | 评分要点 [ | 解析 ]
 * [考公·申论·主观] 材料/题干 | 参考答案 | 评分要点 [ | 解析 ]
 * ```
 *
 * 前缀方括号里用 `·` 或 `/` 分段：
 * - 1 段 = 题型（阶段未标注、学科留空）；
 * - 2 段 = 阶段 + 题型；
 * - 3 段 = 阶段 + 学科 + 题型。
 * 认不出来的阶段/题型进 `UNKNOWN_PREFIX`，绝不把方括号内容当题干。
 */
object QuestionLineParser {

    private const val MIN_OPTIONS = 2

    private val MULTI_SEPARATORS: Set<Char> = setOf(',', '，', '、', ';', '；')
    private val PREFIX = Regex("^\\s*[\\[【［]\\s*([^\\]】］]+?)\\s*[\\]】］]\\s*")

    private data class ParsedPrefix(
        val type: QuestionType,
        val stage: ExamStage,
        val subject: String,
    )

    private data class MultiAnswer(
        val indices: Set<Int>,
        val explanation: String,
        val answerAtEnd: Boolean,
    )

    fun parseAll(text: String): ImportPlan =
        TextCleaner.splitLines(text).map { parseLine(lineNo = it.line, raw = it.text) }.toPlan()

    fun parseLine(lineNo: Int, raw: String): LineResult {
        if (raw.isBlank()) return LineResult.Blank
        val match = PREFIX.find(raw)
        val prefix = if (match == null) {
            ParsedPrefix(QuestionType.SINGLE, ExamStage.UNSPECIFIED, "")
        } else {
            parsePrefix(match.groupValues[1]) ?: return bad(lineNo, raw, RejectReason.UNKNOWN_PREFIX)
        }
        val body = if (match == null) raw else raw.substring(match.range.last + 1).trimStart()
        return when (prefix.type) {
            QuestionType.SINGLE -> parseSingle(lineNo, raw, body, prefix)
            QuestionType.MULTIPLE -> parseMultiple(lineNo, raw, body, prefix)
            QuestionType.TRUE_FALSE -> parseTrueFalse(lineNo, raw, body, prefix)
            QuestionType.FILL, QuestionType.CALCULATION -> parseFillOrCalc(lineNo, raw, body, prefix)
            QuestionType.SHORT_ANSWER,
            QuestionType.ESSAY,
            QuestionType.CASE_ANALYSIS,
            -> parseSelfRubric(lineNo, raw, body, prefix)
        }
    }

    private fun parsePrefix(content: String): ParsedPrefix? {
        val segments = content.split('·', '/', '｜', '•')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (segments.isEmpty()) return null
        val type = QuestionType.fromAlias(segments.last()) ?: return null
        val stage = when {
            segments.size >= 2 -> ExamStage.fromAlias(segments[0]) ?: return null
            else -> ExamStage.UNSPECIFIED
        }
        if (segments.size > 3) return null
        val subject = if (segments.size == 3) segments[1] else ""
        return ParsedPrefix(type = type, stage = stage, subject = subject)
    }

    private fun parseSingle(
        lineNo: Int,
        raw: String,
        body: String,
        prefix: ParsedPrefix,
    ): LineResult {
        val fields = body.split('|').map { it.trim() }
        if (fields.count { it.isNotEmpty() } < MIN_OPTIONS + 2) {
            return bad(lineNo, raw, RejectReason.TOO_FEW_COLUMNS)
        }
        val stem = fields.first()
        if (stem.isEmpty()) return bad(lineNo, raw, RejectReason.EMPTY_FIELDS)
        val tail = fields.drop(1)
        val resolved = resolveSingleAnswer(tail)
            ?: return bad(lineNo, raw, RejectReason.ANSWER_NOT_FOUND)
        val (index, explanation) = resolved
        val options = tail.dropLast(if (explanation.isEmpty()) 1 else 2)
        return LineResult.Ok(
            ImportItem.Question(
                sourceLine = lineNo,
                raw = raw,
                stem = stem,
                options = options,
                answerIndex = index,
                explanation = explanation,
                type = QuestionType.SINGLE,
                answerIndices = listOf(index),
                acceptedAnswers = emptyList(),
                stage = prefix.stage,
                subject = prefix.subject,
            ),
        )
    }

    private fun parseMultiple(
        lineNo: Int,
        raw: String,
        body: String,
        prefix: ParsedPrefix,
    ): LineResult {
        val fields = body.split('|').map { it.trim() }
        if (fields.count { it.isNotEmpty() } < MIN_OPTIONS + 2) {
            return bad(lineNo, raw, RejectReason.TOO_FEW_COLUMNS)
        }
        val stem = fields.first()
        if (stem.isEmpty()) return bad(lineNo, raw, RejectReason.EMPTY_FIELDS)
        val tail = fields.drop(1)
        val resolved = resolveMultiAnswer(tail)
            ?: return bad(lineNo, raw, RejectReason.ANSWER_NOT_FOUND)
        val options = if (resolved.answerAtEnd) tail.dropLast(1) else tail.dropLast(2)
        if (options.size < MIN_OPTIONS) return bad(lineNo, raw, RejectReason.TOO_FEW_COLUMNS)
        val indices = resolved.indices.sorted()
        return LineResult.Ok(
            ImportItem.Question(
                sourceLine = lineNo,
                raw = raw,
                stem = stem,
                options = options,
                answerIndex = indices.first(),
                explanation = resolved.explanation,
                type = QuestionType.MULTIPLE,
                answerIndices = indices,
                acceptedAnswers = emptyList(),
                stage = prefix.stage,
                subject = prefix.subject,
            ),
        )
    }

    private fun parseTrueFalse(
        lineNo: Int,
        raw: String,
        body: String,
        prefix: ParsedPrefix,
    ): LineResult {
        val fields = body.split('|').map { it.trim() }
        if (fields.size < 2) return bad(lineNo, raw, RejectReason.TOO_FEW_COLUMNS)
        val stem = fields[0]
        if (stem.isEmpty()) return bad(lineNo, raw, RejectReason.EMPTY_FIELDS)
        val tail = fields.drop(1)
        val last = parseBoolean(tail.last())?.let { it to "" }
        val second = if (last == null && tail.size >= 2) {
            parseBoolean(tail[tail.lastIndex - 1])?.let { it to tail.last() }
        } else {
            null
        }
        val resolved = last ?: second ?: return bad(lineNo, raw, RejectReason.ANSWER_NOT_FOUND)
        val (value, explanation) = resolved
        return LineResult.Ok(
            ImportItem.Question(
                sourceLine = lineNo,
                raw = raw,
                stem = stem,
                options = listOf("正确", "错误"),
                answerIndex = if (value) 0 else 1,
                explanation = explanation,
                type = QuestionType.TRUE_FALSE,
                answerIndices = listOf(if (value) 0 else 1),
                acceptedAnswers = emptyList(),
                stage = prefix.stage,
                subject = prefix.subject,
            ),
        )
    }

    private fun parseFillOrCalc(
        lineNo: Int,
        raw: String,
        body: String,
        prefix: ParsedPrefix,
    ): LineResult {
        val fields = body.split('|').map { it.trim() }
        if (fields.size < 2) return bad(lineNo, raw, RejectReason.TOO_FEW_COLUMNS)
        val stem = fields[0]
        if (stem.isEmpty()) return bad(lineNo, raw, RejectReason.EMPTY_FIELDS)
        val answers = fields[1]
            .split(';', '；')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        if (answers.isEmpty()) return bad(lineNo, raw, RejectReason.ANSWER_NOT_FOUND)
        val explanation = if (fields.size >= 3) fields.drop(2).joinToString("|").trim() else ""
        return LineResult.Ok(
            ImportItem.Question(
                sourceLine = lineNo,
                raw = raw,
                stem = stem,
                options = emptyList(),
                answerIndex = 0,
                explanation = explanation,
                type = prefix.type,
                answerIndices = emptyList(),
                acceptedAnswers = answers,
                stage = prefix.stage,
                subject = prefix.subject,
            ),
        )
    }

    private fun parseSelfRubric(
        lineNo: Int,
        raw: String,
        body: String,
        prefix: ParsedPrefix,
    ): LineResult {
        val fields = body.split('|').map { it.trim() }
        if (fields.size < 2) return bad(lineNo, raw, RejectReason.TOO_FEW_COLUMNS)
        val stem = fields[0]
        val reference = fields[1]
        if (stem.isEmpty() || reference.isEmpty()) return bad(lineNo, raw, RejectReason.EMPTY_FIELDS)
        val rubric = if (fields.size >= 3) fields.drop(2).joinToString("|").trim() else ""
        return LineResult.Ok(
            ImportItem.Question(
                sourceLine = lineNo,
                raw = raw,
                stem = stem,
                options = emptyList(),
                answerIndex = 0,
                explanation = rubric,
                type = prefix.type,
                answerIndices = emptyList(),
                acceptedAnswers = listOf(reference),
                stage = prefix.stage,
                subject = prefix.subject,
            ),
        )
    }

    private fun resolveSingleAnswer(tail: List<String>): Pair<Int, String>? {
        val last = tail.last()
        byLetter(last, tail)?.let { return it to "" }
        byText(last, tail)?.let { return it to "" }
        if (tail.size >= MIN_OPTIONS + 2) {
            val secondLast = tail[tail.lastIndex - 1]
            byLetter(secondLast, tail)?.let { return it to last }
            byText(secondLast, tail)?.let { return it to last }
        }
        return null
    }

    private fun resolveMultiAnswer(tail: List<String>): MultiAnswer? {
        parseMultiAnswer(tail.last(), tail.dropLast(1).size)?.let {
            return MultiAnswer(it, explanation = "", answerAtEnd = true)
        }
        if (tail.size >= MIN_OPTIONS + 2) {
            parseMultiAnswer(tail[tail.lastIndex - 1], tail.dropLast(2).size)?.let {
                return MultiAnswer(it, explanation = tail.last(), answerAtEnd = false)
            }
        }
        return null
    }

    private fun parseMultiAnswer(token: String, optionCount: Int): Set<Int>? {
        val compact = token.trim().filterNot { it.isWhitespace() || it in MULTI_SEPARATORS }
        if (compact.length < 2) return null
        val indices = LinkedHashSet<Int>()
        compact.forEach { ch ->
            val upper = ch.uppercaseChar()
            if (upper !in 'A'..'Z') return null
            val index = upper - 'A'
            if (index !in 0 until optionCount) return null
            indices.add(index)
        }
        return indices.takeIf { it.size >= 2 }
    }

    private fun parseBoolean(token: String): Boolean? = when (token.trim().lowercase()) {
        "正确", "对", "是", "√", "t", "true" -> true
        "错误", "错", "否", "×", "f", "false" -> false
        else -> null
    }

    private fun byLetter(token: String, tail: List<String>): Int? {
        val letter = token.trim().uppercase().singleOrNull() ?: return null
        if (letter !in 'A'..'Z') return null
        val index = letter - 'A'
        val options = optionsOf(tail)
        return if (index in options.indices) index else null
    }

    private fun byText(token: String, tail: List<String>): Int? {
        val options = optionsOf(tail)
        return options.indexOfFirst { it == token }.takeIf { it >= 0 }
    }

    private fun optionsOf(tail: List<String>): List<String> = tail.dropLast(1)

    private fun bad(lineNo: Int, raw: String, reason: RejectReason): LineResult.Bad =
        LineResult.Bad(RejectedLine(sourceLine = lineNo, raw = raw, reason = reason))
}
