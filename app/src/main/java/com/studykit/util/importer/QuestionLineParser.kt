package com.studykit.util.importer

/**
 * 题目行解析（spec §5.1）：`题干 | 选项A | 选项B … | 答案 [ | 解析 ]`。
 *
 * 答案写法允许两种：单个字母（`A`–`Z`，大小写均可，按下标定），或某条选项的原文（按内容匹配）。
 * 判定顺序刻意是「先试最后一个字段，再试倒数第二个 + 最后一个当解析」——因为带解析列时
 * 最后一个字段是中文说明，不是答案；而选项原文本身可能长得像字母（如选项就是 `A`），
 * 所以字母优先、原文兜底。两条都不成立就判 `ANSWER_NOT_FOUND` 进待修正区，不做猜测。
 */
object QuestionLineParser {

    private const val MIN_OPTIONS = 2

    fun parseAll(text: String): ImportPlan =
        TextCleaner.splitLines(text).map { parseLine(lineNo = it.line, raw = it.text) }.toPlan()

    fun parseLine(lineNo: Int, raw: String): LineResult {
        if (raw.isBlank()) return LineResult.Blank
        val fields = raw.split('|').map { it.trim() }
        // 至少：题干 + MIN_OPTIONS 个选项 + 答案
        if (fields.count { it.isNotEmpty() } < MIN_OPTIONS + 2) {
            return bad(lineNo, raw, RejectReason.TOO_FEW_COLUMNS)
        }
        val stem = fields.first()
        val tail = fields.drop(1)
        resolveAnswer(tail)?.let { (index, explanation) ->
            return LineResult.Ok(
                ImportItem.Question(
                    sourceLine = lineNo,
                    raw = raw,
                    stem = stem,
                    options = tail.dropLast(if (explanation.isEmpty()) 1 else 2),
                    answerIndex = index,
                    explanation = explanation,
                ),
            )
        }
        return bad(lineNo, raw, RejectReason.ANSWER_NOT_FOUND)
    }

    /** 返回「答案下标 + 解析文本」；解析文本在答案不是最后一个字段时非空 */
    private fun resolveAnswer(tail: List<String>): Pair<Int, String>? {
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

    private fun byLetter(token: String, tail: List<String>): Int? {
        val letter = token.trim().uppercase().singleOrNull() ?: return null
        if (!letter.isInRange()) return null
        val index = letter - 'A'
        val options = optionsOf(tail)
        return if (index in options.indices) index else null
    }

    private fun byText(token: String, tail: List<String>): Int? {
        val options = optionsOf(tail)
        return options.indexOfFirst { it == token }.takeIf { it >= 0 }
    }

    /** 候选选项集合：最后一个字段先当答案候选排除，带解析列时由 [resolveAnswer] 再排除一个 */
    private fun optionsOf(tail: List<String>): List<String> = tail.dropLast(1)

    private fun Char.isInRange(): Boolean = this in 'A'..'Z'

    private fun bad(lineNo: Int, raw: String, reason: RejectReason): LineResult.Bad =
        LineResult.Bad(RejectedLine(sourceLine = lineNo, raw = raw, reason = reason))
}
