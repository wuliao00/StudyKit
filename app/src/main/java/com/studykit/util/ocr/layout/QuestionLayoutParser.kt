package com.studykit.util.ocr.layout

import com.studykit.util.ocr.OcrLine
import com.studykit.util.ocr.OcrBox
import kotlin.math.max
import kotlin.math.min

/**
 * 规则版「版面切题」：不做模型，只靠 OCR 行框 + 正则，把书页/试卷页切成题目。
 *
 * v3.8 的目标不是把任意版面都切对，而是把**常见印刷题页**从「一坨文本」变成
 * 可编辑的结构（题干/选项/答案/解析）。切不准时宁可少收：没有答案的题不进库，
 * 由调用方退回题库编辑页；切出来的题会带 sourceLine 与置信度，供导入预览标注。
 *
 * 管线：
 * 1. 过滤页码/页眉页脚；
 * 2. 阅读顺序：检测双栏（左侧框起点与右侧有明显水平空档）→ 先左栏后右栏；单栏按行聚类；
 * 3. 逐行状态机：题号行开新题，选项行挂选项，答案/解析行收尾，其余续接上一段；
 * 4. 行内联选项（A. 4 B. 5 C. 6 D. 9）拆开；
 * 5. 产出 [LayoutQuestion]：题干、选项、答案文本、解析、行号区间、置信度、疑似公式行。
 *
 * 刻意不做：上下标/公式的 LaTeX 还原（需要公式模型）；图形/表格区域检测（保留整页原图由
 * 校对页回看）；手写体单独版面。
 */
object QuestionLayoutParser {

    data class LayoutQuestion(
        val stem: String,
        val options: List<String>,
        val answerText: String,
        val analysis: String,
        /** 题号所在 OCR 行（1 基），用于映射置信度与在原文里定位 */
        val sourceLine: Int,
        val endLine: Int,
        /** 题号到答案/解析最后一行的加权平均置信度 */
        val confidence: Float,
        /** 本题目区域内被判定为「疑似公式」的原始行，导入时写进 OCR 元数据 */
        val formulaHints: List<String>,
        val warnings: List<String>,
    )

    data class LayoutResult(
        val questions: List<LayoutQuestion>,
        /** 有题号但没找到答案的题数：这些不自动入库，提示用户去编辑页补 */
        val skippedMissingAnswer: Int,
        val notes: List<String>,
    )

    private val QUESTION_START = Regex("^\\s*(?:第\\s*)?(\\d{1,3})\\s*[.、．)）]\\s*(.*)$")
    private val QUESTION_CN = Regex("^\\s*第\\s*(\\d{1,3})\\s*题\\s*[.、．:：]?\\s*(.*)$")
    private val OPTION_START = Regex("^\\s*([A-H])\\s*[.、．:：)）]\\s*(.*)$")
    private val ANSWER_LINE = Regex("^\\s*(?:【\\s*答案\\s*】|答案|参考答案|正确答案)\\s*[:：]?\\s*(.*)$")
    private val ANALYSIS_LINE = Regex("^\\s*(?:【\\s*解析\\s*】|解析|答案解析|分析)\\s*[:：]?\\s*(.*)$")
    private val PAGE_NUMBER = Regex("^\\s*(?:第\\s*\\d+\\s*页|\\d{1,3})\\s*$")
    private val INLINE_OPTION = Regex("(?:^|\\s)([A-H])\\s*[.、．:：)）]\\s*")

    fun parse(lines: List<OcrLine>, pageWidth: Int, pageHeight: Int): LayoutResult {
        val usable = lines.filter { it.text.isNotBlank() }
        if (usable.isEmpty()) return LayoutResult(emptyList(), 0, listOf("没有可用的文本行"))

        val filtered = usable.filterNot { line ->
            PAGE_NUMBER.matches(line.text.trim()) &&
                line.box != null &&
                (line.box.centerY < pageHeight * 0.06f || line.box.centerY > pageHeight * 0.94f)
        }
        val ordered = readingOrder(filtered, pageWidth)
        val questions = ArrayList<LayoutQuestion>()
        var skippedMissing = 0
        var current: MutableQuestion? = null

        fun flush() {
            val q = current ?: return
            val result = finish(q)
            if (result == null) {
                // 有选项却没答案：这是「题切出来了但缺答案」，单独计数提示用户去编辑页补
                if (q.options.size >= 2 && q.answerText.isBlank()) skippedMissing++
            } else {
                questions.add(result)
            }
            current = null
        }

        for ((index, line) in ordered.withIndex()) {
            val text = line.text.trim()
            if (text.isEmpty()) continue
            val start = QUESTION_START.matchEntire(text) ?: QUESTION_CN.matchEntire(text)
            if (start != null) {
                flush()
                val stemText = start.groupValues[2].trim()
                val inline = splitInlineOptions(stemText)
                current = MutableQuestion(lineNo = index + 1, lineScore = line.score, box = line.box)
                if (inline != null) {
                    current!!.stemParts.add(inline.first)
                    current!!.options.addAll(inline.second)
                    current!!.lineScores.add(line.score)
                    current!!.formulaHints.addAll(formulaHintsFor(listOf(inline.first) + inline.second))
                } else {
                    if (stemText.isNotEmpty()) current!!.stemParts.add(stemText)
                    current!!.lineScores.add(line.score)
                    current!!.formulaHints.addAll(formulaHintsFor(listOf(stemText)))
                }
                continue
            }
            val q = current ?: continue
            q.endLine = index + 1
            q.lineScores.add(line.score)
            val option = OPTION_START.matchEntire(text)
            val answer = ANSWER_LINE.matchEntire(text)
            val analysis = ANALYSIS_LINE.matchEntire(text)
            when {
                answer != null -> {
                    q.answerText = answer.groupValues[1].trim()
                    q.lineScores.add(line.score)
                }
                analysis != null -> {
                    q.analysis = analysis.groupValues[1].trim()
                    q.lineScores.add(line.score)
                }
                option != null -> {
                    val label = option.groupValues[1]
                    val rest = option.groupValues[2].trim()
                    val inline = splitInlineOptions(rest)
                    if (inline != null) {
                        q.options.add(inline.first)
                        q.options.addAll(inline.second)
                        q.formulaHints.addAll(formulaHintsFor(listOf(inline.first) + inline.second))
                    } else {
                        q.options.add(rest)
                        q.formulaHints.addAll(formulaHintsFor(listOf(rest)))
                    }
                }
                else -> {
                    // 没对上题号/选项/答案的续行：有选项时续到最后一个选项，否则续到题干
                    if (q.options.isNotEmpty()) {
                        val last = q.options.removeAt(q.options.lastIndex)
                        q.options.add(smartJoin(last, text))
                    } else {
                        val last = q.stemParts.removeAt(q.stemParts.lastIndex)
                        q.stemParts.add(smartJoin(last, text))
                        q.formulaHints.addAll(formulaHintsFor(listOf(text)))
                    }
                }
            }
        }
        flush()

        val notes = mutableListOf<String>()
        if (questions.isEmpty()) notes.add("没有切出带答案的题目")
        if (skippedMissing > 0) notes.add("有 " + skippedMissing + " 道题没找到答案，未自动导入")
        return LayoutResult(questions, skippedMissing, notes)
    }

    private fun finish(q: MutableQuestion): LayoutQuestion? {
        val stem = q.stemParts.joinToString(" ").trim()
        if (stem.length < 2) return null
        val options = q.options.map { it.trim() }.filter { it.isNotEmpty() }
        val answer = q.answerText.trim()
        if (options.size < 2 && answer.isEmpty()) return null
        if (options.size >= 2 && answer.isEmpty()) return null
        val stemScore = if (q.lineScores.isEmpty()) 0f else q.lineScores.average().toFloat()
        val hints = q.formulaHints.distinct()
        val warnings = ArrayList<String>(2)
        if (hints.isNotEmpty()) warnings.add("含疑似公式/符号 " + hints.size + " 处，建议对照原图")
        if (answer.isEmpty()) warnings.add("缺答案")
        return LayoutQuestion(
            stem = stem,
            options = options,
            answerText = answer,
            analysis = q.analysis.trim(),
            sourceLine = q.lineNo,
            endLine = max(q.lineNo, q.endLine),
            confidence = stemScore,
            formulaHints = hints,
            warnings = warnings,
        )
    }

    private fun weightScore(parts: List<String>, scores: List<Float>): Float {
        if (scores.isEmpty()) return 0f
        val n = min(parts.size, scores.size)
        if (n == 0) return scores.average().toFloat()
        var weight = 0
        var sum = 0f
        for (i in 0 until n) {
            val w = parts[i].length.coerceAtLeast(1)
            weight += w
            sum += scores[i] * w
        }
        return if (weight == 0) 0f else sum / weight
    }

    private fun formulaHintsFor(texts: List<String>): List<String> =
        texts.filter { FormulaDetector.looksLikeFormula(it) }

    /**
     * 阅读顺序：
     * 1) 若存在一条明显的双栏分界（某条竖线左侧 >=3 行、右侧 >=3 行，且两栏框的水平空档
     *    超过页宽 12%），先左栏后右栏递归；
     * 2) 否则按「行聚类」排序：y 方向重叠超过较矮行 60% 的算同一行，行内按 x 升序。
     * 没有框（box=null）时保持 OCR 原顺序，原顺序已经是 det 的 sorted_boxes。
     */
    internal fun readingOrder(lines: List<OcrLine>, pageWidth: Int): List<OcrLine> {
        if (lines.size < 2 || pageWidth <= 0) return lines
        if (lines.any { it.box == null }) return lines
        val boxes = lines.map { it.box!! }
        val sortedByLeft = lines.indices.sortedBy { boxes[it].x0 }
        var splitIndex = -1
        var bestGap = 0
        for (k in 0 until sortedByLeft.size - 1) {
            val leftLines = sortedByLeft.subList(0, k + 1)
            val rightLines = sortedByLeft.subList(k + 1, sortedByLeft.size)
            if (leftLines.size < 3 || rightLines.size < 3) continue
            val leftRight = leftLines.maxOf { boxes[it].x1 }
            val rightLeft = rightLines.minOf { boxes[it].x0 }
            val gap = rightLeft - leftRight
            if (gap > pageWidth * 0.12f && gap > bestGap && rightLeft > pageWidth * 0.3f) {
                bestGap = gap
                splitIndex = k
            }
        }
        if (splitIndex >= 0) {
            val left = sortedByLeft.subList(0, splitIndex + 1).map { lines[it] }
            val right = sortedByLeft.subList(splitIndex + 1, sortedByLeft.size).map { lines[it] }
            return readingOrder(left, pageWidth) + readingOrder(right, pageWidth)
        }
        return sortSingleColumn(lines)
    }

    private fun sortSingleColumn(lines: List<OcrLine>): List<OcrLine> {
        if (lines.size < 2) return lines
        if (lines.any { it.box == null }) return lines
        val byTop = lines.sortedBy { it.box!!.y0 }
        val rows = ArrayList<MutableList<OcrLine>>()
        for (line in byTop) {
            val box = line.box!!
            val row = rows.lastOrNull()
            if (row == null) {
                rows.add(mutableListOf(line))
                continue
            }
            val lastBox = row.last().box!!
            val overlap = min(box.y1, lastBox.y1) - max(box.y0, lastBox.y0)
            val minHeight = min(box.height, lastBox.height).coerceAtLeast(1)
            if (overlap > minHeight * 0.6f) {
                row.add(line)
            } else {
                rows.add(mutableListOf(line))
            }
        }
        return rows.flatMap { row -> row.sortedBy { it.box!!.x0 } }
    }

    /**
     * 行内联选项拆分：A. 4 B. 5 C. 6 D. 9 → 题干为空、四个选项。
     * 为避免把普通英文里的 A./B. 误拆，要求标签从 A 开始连续出现至少 2 个。
     */
    internal fun splitInlineOptions(text: String): Pair<String, List<String>>? {
        val matches = INLINE_OPTION.findAll(text).toList()
        if (matches.size < 2) return null
        // 选项行续接（如 "B. 5 C. 6 D. 9"）时起点不一定是 A，只要求标签连续递增
        val labels = matches.mapNotNull { it.groups[1]?.value?.firstOrNull() }
        if (labels.isEmpty()) return null
        var expected = labels.first()
        for (label in labels) {
            if (label != expected) return null
            expected++
        }
        val stem = text.substring(0, matches.first().range.first).trim()
        val options = ArrayList<String>(matches.size)
        for (i in matches.indices) {
            val start = matches[i].range.last + 1
            val end = if (i + 1 < matches.size) matches[i + 1].range.first else text.length
            val value = text.substring(start, end).trim().trim(',', '，', ';', '；')
            options.add(value)
        }
        return stem to options
    }

    private fun smartJoin(left: String, right: String): String {
        if (left.isEmpty()) return right
        if (right.isEmpty()) return left
        val last = left.last()
        val first = right.first()
        val cjk = last.isCjkOrPunct() && first.isCjkOrPunct()
        return if (cjk) left + right else left + " " + right
    }

    private fun Char.isCjkOrPunct(): Boolean =
        this in '\u4e00'..'\u9fff' || this in '\u3000'..'\u303f' || this in '\uff00'..'\uffef'

    private class MutableQuestion(
        val lineNo: Int,
        val lineScore: Float,
        val box: OcrBox?,
    ) {
        val stemParts = ArrayList<String>()
        val options = ArrayList<String>()
        val lineScores = ArrayList<Float>()
        val formulaHints = ArrayList<String>()
        var answerText = ""
        var analysis = ""
        var endLine = lineNo
    }
}

/** 公式/符号行的启发式判定：不追求模型级准确率，只负责把「明显不是自然语言」的行挑出来提示校对。 */
object FormulaDetector {
    private val CJK = '\u4e00'..'\u9fff'
    private val MATH = setOf(
        '=', '+', '-', '−', '×', '÷', '±', '√', '∫', '∑', '∏', '∞', '≤', '≥', '≠', '≈',
        '≡', '∝', '∂', '∇', '^', '_', '{', '}', '[', ']', '/', '\\', 'π', 'θ', 'λ', 'μ',
    )

    fun looksLikeFormula(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.length < 3) return false
        val letters = trimmed.count { it.isLetter() }
        val cjk = trimmed.count { it in CJK }
        val digits = trimmed.count { it.isDigit() }
        val symbols = trimmed.count { it in MATH }
        if (symbols >= 2 && cjk <= trimmed.length * 0.5) return true
        if (symbols >= 1 && cjk == 0 && digits >= 2) return true
        if (cjk == 0 && digits >= 2 && letters >= 1 && trimmed.length <= 16) return true
        // 化学式/短公式记号，如 H2O、CO2、x^2 已被上面的符号分支覆盖；这里只收「无空格、字母开头、
        // 含数字、长度很短」的记号，避免把 "Unit 5" 这类带空格的普通英文误判。
        if (cjk == 0 && trimmed.length <= 12 && !trimmed.contains(' ') &&
            trimmed.first().isLetter() && digits >= 1 && letters >= 1
        ) {
            return true
        }
        return false
    }
}
