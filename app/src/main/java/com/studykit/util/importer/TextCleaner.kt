package com.studykit.util.importer

/**
 * 导入文本的入口清洗。刻意做成 `object` + 纯函数：粘贴、SAF 文件、OCR 结果、
 * 在线词表四条来源都从这里进，行号语义（1 基、与原文一一对应）只在这里定义一次。
 */
object TextCleaner {

    private const val BOM = '\uFEFF'

    /** 部分字典混入带重音的拉丁字母（kajweb/dict README 明确提示），归一后便于检索与去重 */
    private val ACCENT_FOLDINGS = listOf(
        "é" to "e", "ê" to "e", "è" to "e", "ë" to "e",
        "à" to "a", "â" to "a", "ç" to "c",
        "î" to "i", "ï" to "i", "ô" to "o",
        "ù" to "u", "û" to "u", "ü" to "u", "ÿ" to "y",
    )

    /**
     * 切行并编号：保留原始行序（空行也占一个号），因为「待修正」区要能把用户指回他刚贴的那一行。
     * 处理 BOM、CRLF/CR、每行首尾空白；不动行内的制表符与多空格（那是分列信号）。
     */
    fun splitLines(text: String): List<NumberedLine> {
        val normalized = text.removePrefix(BOM.toString()).replace("\r\n", "\n").replace('\r', '\n')
        return normalized.split('\n').mapIndexed { index, raw ->
            NumberedLine(line = index + 1, text = raw.trim())
        }
    }

    fun normalizeAccents(value: String): String {
        var result = value
        ACCENT_FOLDINGS.forEach { (from, to) -> result = result.replace(from, to) }
        return result
    }
}

/** 一行原文及其 1 基行号 */
data class NumberedLine(val line: Int, val text: String)
