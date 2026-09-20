package com.studykit.util.importer

/**
 * 单词行解析（spec §5.1）：`word \t 释义 [\t 例句]`，并容忍手贴内容的其它分隔符。
 *
 * 分列优先级（自上而下，先命中先用）：
 * 1. 制表符；
 * 2. 连续两个及以上空格；
 * 3. 半角或全角逗号；
 * 4. 兜底：只切第一个空格 —— 因为 `abandon v. 放弃；抛弃` 里剩下的空格属于释义内容，
 *    按空格全切会把释义炸成三截。
 *
 * 1–3 一律 `limit = 3`：释义本身常含逗号（"n. 效果；作用"），切过头就把正确内容弄丢了。
 */
object WordLineParser {

    private val MULTI_SPACE = Regex(" {2,}")

    /** 整段文本 → 解析计划（可用项 + 待修正项 + 空行数） */
    fun parseAll(text: String): ImportPlan =
        TextCleaner.splitLines(text).map { parseLine(lineNo = it.line, raw = it.text) }.toPlan()

    fun parseLine(lineNo: Int, raw: String): LineResult {
        if (raw.isBlank()) return LineResult.Blank
        val fields = splitFields(raw)
        val word = fields.getOrElse(0) { "" }.trim()
        val meaning = fields.getOrElse(1) { "" }.trim()
        val example = fields.getOrElse(2) { "" }.trim()
        if (word.isEmpty() || meaning.isEmpty()) {
            return LineResult.Bad(
                RejectedLine(sourceLine = lineNo, raw = raw, reason = RejectReason.TOO_FEW_COLUMNS),
            )
        }
        return LineResult.Ok(
            ImportItem.Word(
                sourceLine = lineNo,
                raw = raw,
                word = TextCleaner.normalizeAccents(word),
                meaning = meaning,
                example = example,
            ),
        )
    }

    private fun splitFields(raw: String): List<String> {
        if (raw.contains('\t')) {
            val byTab = raw.split('\t', limit = 3).map { it.trim() }
            if (meaningful(byTab) >= 2) return byTab
        }
        val bySpacing = MULTI_SPACE.split(raw, limit = 3).map { it.trim() }
        if (meaningful(bySpacing) >= 2) return bySpacing
        val byComma = raw.split(',', '，', limit = 3).map { it.trim() }
        if (meaningful(byComma) >= 2) return byComma
        // 兜底：第一个空格前是词头，其余整体作释义
        val trimmed = raw.trim()
        val cut = trimmed.indexOf(' ')
        return if (cut <= 0) listOf(trimmed) else listOf(trimmed.substring(0, cut), trimmed.substring(cut + 1).trim())
    }

    private fun meaningful(fields: List<String>): Int = fields.count { it.isNotEmpty() }
}
