package com.studykit.util.importer

/**
 * 笔记行解析（spec §5.1）：`书名 | 摘录 | 感想`，感想可省。
 *
 * 分列刻意是「第一个竖线之前 = 书名，最后一个竖线之后 = 感想，中间整段 = 摘录」而不是
 * `split('|')`：摘录原文里出现竖线（表格、公式、分隔号）很常见，切碎会把内容弄脏。
 * 只有两段时最后一段是摘录、感想为空 —— 与「三段」用同一个 `drop/last` 组合表达。
 *
 * 失败原因分两档，好让「待修正」区给出准确提示：根本没有分隔符/只有一段是 `TOO_FEW_COLUMNS`，
 * 结构齐全但书名或摘录为空白是 `EMPTY_FIELDS`。
 */
object NoteLineParser {

    private const val SEPARATOR = '|'

    fun parseAll(text: String): ImportPlan =
        TextCleaner.splitLines(text).map { parseLine(lineNo = it.line, raw = it.text) }.toPlan()

    fun parseLine(lineNo: Int, raw: String): LineResult {
        if (raw.isBlank()) return LineResult.Blank
        val first = raw.indexOf(SEPARATOR)
        val last = raw.lastIndexOf(SEPARATOR)
        // 只认「有没有分隔符」；`|摘录|感想` 结构齐全、只是书名为空，那是 EMPTY_FIELDS 而不是列数不足
        if (first < 0) return bad(lineNo, raw, RejectReason.TOO_FEW_COLUMNS)
        val book = raw.substring(0, first).trim()
        if (book.isEmpty()) return bad(lineNo, raw, RejectReason.EMPTY_FIELDS)
        // 只两段时（first == last）最后一段就是摘录、感想为空；三段时中间整段是摘录
        val excerpt = if (first == last) raw.substring(first + 1) else raw.substring(first + 1, last)
        val thought = if (first == last) "" else raw.substring(last + 1)
        if (excerpt.isBlank()) return bad(lineNo, raw, RejectReason.EMPTY_FIELDS)
        return LineResult.Ok(
            ImportItem.Excerpt(
                sourceLine = lineNo,
                raw = raw,
                book = book,
                excerpt = excerpt.trim(),
                thought = thought.trim(),
            ),
        )
    }

    private fun bad(lineNo: Int, raw: String, reason: RejectReason): LineResult.Bad =
        LineResult.Bad(RejectedLine(sourceLine = lineNo, raw = raw, reason = reason))
}
