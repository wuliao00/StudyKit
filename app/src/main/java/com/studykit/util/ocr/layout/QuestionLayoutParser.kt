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
        /** v3.9：疑似公式行的框（工作图像素坐标），用于裁片保留原图 */
        val formulaBoxes: List<OcrBox> = emptyList(),
        /** v3.10：页面上的题号（用于答案区按题号回填答案，可为空） */
        val questionNumber: Int? = null,
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

    /** v3.13：节标题（一、选择题 / 第 X 部分 / 选择题（每题 3 分）/ 注意事项）不参与题干 */
    private val SECTION_HEADER = Regex(
        "^\\s*(?:[一二三四五六七八九十]+\\s*[、.．]|" +
            "第\\s*[一二三四五六七八九十\\dⅠⅡⅢⅣⅤⅥⅦⅧⅨⅩIVX]+\\s*(?:部分|大题)|" +
            "第\\s*[一二三四五六七八九十\\dⅠⅡⅢⅣⅤⅥⅦⅧⅨⅩIVX]+\\s*卷(?=\\s*[（(：:]|\\s|$)|" +
            "[ⅠⅡⅢⅣⅤ]+\\s*卷(?=\\s*[（(：:]|\\s|$)|" +
            "卷\\s*[ⅠⅡⅢⅣⅤ]+(?=\\s|[（(：:]|$)|" +
            "(?:选择题|填空题|判断题|解答题|计算题|简答题|材料分析题|综合题)\\s*[（(]|" +
            "(?i:section|part)\\s+[A-Z0-9]+|" +
            "注意事项|考试说明|考生须知).*$",
    )

    /** v3.13：分值行（本题 5 分 / （共 10 分）/ 分值：12 分）不续进题干 */
    private val SCORE_LINE = Regex(
        "^\\s*[（(]?\\s*(?:本题|每题|共|满分)?\\s*\\d+(?:\\.\\d+)?\\s*分\\s*[）)]?\\s*$|" +
            "^\\s*分值\\s*[:：].*$",
    )

    /** v3.10：答案区「1-5 ABCDA」区间；要求字母数与区间长度一致，防止把普通连字符误判 */
    private val ANSWER_KEY_RANGE = Regex("(\\d{1,3})\\s*[-~—至]\\s*(\\d{1,3})\\s*[:：.、]?\\s*([A-H]{2,15})")

    /** v3.10：答案区「题号+答案」；支持 A-H/正确/错误/对/错/√/× */
    private val ANSWER_KEY_PAIR = Regex(
        "(\\d{1,3})\\s*[.、:：)]\\s*([A-H]{1,4}|正确|错误|对|错|√|×|true|false)",
    )

    fun parse(lines: List<OcrLine>, pageWidth: Int, pageHeight: Int): LayoutResult {
        val usable = lines.filter { it.text.isNotBlank() }
        if (usable.isEmpty()) return LayoutResult(emptyList(), 0, listOf("没有可用的文本行"))

        val filtered = usable.filterNot { line ->
            PAGE_NUMBER.matches(line.text.trim()) &&
                line.box != null &&
                (line.box.centerY < pageHeight * 0.06f || line.box.centerY > pageHeight * 0.94f)
        }
        val ordered = readingOrder(filtered, pageWidth)
        val (answerKey, answerKeyIndexes) = extractAnswerKey(ordered)
        val questions = ArrayList<LayoutQuestion>()
        val missing = ArrayList<MutableQuestion>()
        var skippedMissing = 0
        var answeredFromKey = 0
        var current: MutableQuestion? = null

        fun flush() {
            val q = current ?: return
            val result = finish(q)
            if (result == null) {
                // 有选项却没答案：先留着，等整页答案区解析后再回填；仍没有才算缺答案
                if (q.options.size >= 2 && q.answerText.isBlank()) missing.add(q)
            } else {
                questions.add(result)
            }
            current = null
        }

        for ((index, line) in ordered.withIndex()) {
            if (index in answerKeyIndexes) continue
            val text = line.text.trim()
            if (text.isEmpty()) continue
            // v3.13：节标题直接结束上一题并跳过；分值行也不许续进题干/选项
            if (SECTION_HEADER.matches(text)) {
                flush()
                continue
            }
            if (SCORE_LINE.matches(text)) {
                current?.lineScores?.add(line.score)
                continue
            }
            val start = QUESTION_START.matchEntire(text) ?: QUESTION_CN.matchEntire(text)
            if (start != null) {
                flush()
                val stemText = start.groupValues[2].trim()
                val inline = splitInlineOptions(stemText)
                current = MutableQuestion(lineNo = index + 1, lineScore = line.score, box = line.box)
                    .also { it.number = start.groupValues[1].toIntOrNull() }
                if (inline != null) {
                    current!!.stemParts.add(inline.first)
                    current!!.options.addAll(inline.second)
                    current!!.lineScores.add(line.score)
                    val hints = formulaHintsFor(listOf(inline.first) + inline.second)
                    current!!.formulaHints.addAll(hints)
                    if (hints.isNotEmpty() && line.box != null) current!!.formulaBoxes.add(line.box)
                } else {
                    if (stemText.isNotEmpty()) current!!.stemParts.add(stemText)
                    current!!.lineScores.add(line.score)
                    val hints = formulaHintsFor(listOf(stemText))
                    current!!.formulaHints.addAll(hints)
                    if (hints.isNotEmpty() && line.box != null) current!!.formulaBoxes.add(line.box)
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
                        val hints = formulaHintsFor(listOf(inline.first) + inline.second)
                        q.formulaHints.addAll(hints)
                        if (hints.isNotEmpty() && line.box != null) q.formulaBoxes.add(line.box)
                    } else {
                        q.options.add(rest)
                        val hints = formulaHintsFor(listOf(rest))
                        q.formulaHints.addAll(hints)
                        if (hints.isNotEmpty() && line.box != null) q.formulaBoxes.add(line.box)
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
                        val hints = formulaHintsFor(listOf(text))
                        q.formulaHints.addAll(hints)
                        if (hints.isNotEmpty() && line.box != null) q.formulaBoxes.add(line.box)
                    }
                }
            }
        }
        flush()

        // v3.10：答案区按题号回填——真实试卷常把答案集中在页尾/页脚
        for (q in missing) {
            val answer = q.number?.let { answerKey[it] }
            if (answer.isNullOrBlank()) {
                skippedMissing++
                continue
            }
            q.answerText = answer
            val result = finish(q)
            if (result != null) {
                questions.add(result)
                answeredFromKey++
            } else {
                skippedMissing++
            }
        }
        questions.sortBy { it.sourceLine }

        val notes = mutableListOf<String>()
        if (questions.isEmpty()) notes.add("没有切出带答案的题目")
        if (answeredFromKey > 0) notes.add("从答案区补全 " + answeredFromKey + " 道题的答案")
        if (skippedMissing > 0) notes.add("有 " + skippedMissing + " 道题没找到答案，未自动导入")
        return LayoutResult(questions, skippedMissing, notes)
    }

    /**
     * 找页尾答案区：一行里有 >=3 组「题号+答案」，或带「答案/参考答案」标题且 >=2 组。
     * 命中后从该行到页尾都算答案区，不再参与题干/选项状态机。
     *
     * 返回 (题号 -> 答案文本, 答案区行下标集合)。题号重复时保留第一次出现的答案。
     */
    private fun extractAnswerKey(lines: List<OcrLine>): Pair<Map<Int, String>, Set<Int>> {
        var start = -1
        for ((index, line) in lines.withIndex()) {
            val text = line.text.trim()
            if (text.isEmpty()) continue
            val pairs = countAnswerPairs(text)
            val header = text.contains("答案") || text.contains("参考答案")
            if (pairs >= 3 || (header && pairs >= 2)) {
                start = index
                break
            }
        }
        if (start < 0) return emptyMap<Int, String>() to emptySet()
        val result = LinkedHashMap<Int, String>()
        val indexes = HashSet<Int>()
        for (i in start until lines.size) {
            indexes.add(i)
            parseAnswerPairs(lines[i].text, result)
        }
        return result to indexes
    }

    private fun countAnswerPairs(text: String): Int {
        var count = 0
        ANSWER_KEY_RANGE.findAll(text).forEach { count += (it.groupValues[2].toIntOrNull() ?: 0) - (it.groupValues[1].toIntOrNull() ?: 0) + 1 }
        val rest = ANSWER_KEY_RANGE.replace(text, " ")
        count += ANSWER_KEY_PAIR.findAll(rest).count()
        return count
    }

    private fun parseAnswerPairs(text: String, out: MutableMap<Int, String>) {
        var rest = text
        ANSWER_KEY_RANGE.findAll(text).forEach { m ->
            val startNo = m.groupValues[1].toIntOrNull() ?: return@forEach
            val endNo = m.groupValues[2].toIntOrNull() ?: return@forEach
            val letters = m.groupValues[3]
            if (endNo < startNo || endNo - startNo + 1 != letters.length) return@forEach
            for (i in letters.indices) {
                out.putIfAbsent(startNo + i, letters[i].toString())
            }
        }
        if (ANSWER_KEY_RANGE.containsMatchIn(text)) {
            rest = ANSWER_KEY_RANGE.replace(text, " ")
        }
        ANSWER_KEY_PAIR.findAll(rest).forEach { m ->
            val no = m.groupValues[1].toIntOrNull() ?: return@forEach
            val answer = m.groupValues[2].trim()
            if (answer.isNotEmpty()) out.putIfAbsent(no, answer)
        }
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
            formulaBoxes = q.formulaBoxes.distinct(),
            questionNumber = q.number,
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
        val formulaBoxes = ArrayList<OcrBox>()
        var answerText = ""
        var analysis = ""
        var endLine = lineNo
        var number: Int? = null
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
