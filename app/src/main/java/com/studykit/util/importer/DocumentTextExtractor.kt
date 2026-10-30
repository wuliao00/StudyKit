package com.studykit.util.importer

import java.io.BufferedInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.zip.ZipInputStream

/**
 * 纯文本 / DOCX 的文字提取（无 Android 依赖，JVM 单测直接覆盖）。
 *
 * PDF 单独放在 [PdfTextExtractor]：它要碰 PDF 解析库，与这里的 zip/编码逻辑分开，
 * 坏 PDF 的异常也不会把 DOCX 的路带塌。
 *
 * 两条刻意的边界：
 * - **不猜结构**。提取结果原样喂给 [QuestionLineParser]；DOCX 只把段落/换行还原，
 *   不会替用户补竖线，也不会把 Word 的自动编号当成题干的一部分。
 * - **有上限**。导入是用户选的文件，不是可信输入：DOCX 解压后最多读 [MAX_DOCX_XML_CHARS]，
 *   纯文本最多 [MAX_PLAIN_TEXT_CHARS]，避免一个精心构造的压缩炸弹把内存吃光。
 */
/**
 * DOCX 提取结果：文字 + 三类"取不到的内容"的计数。
 *
 * 计数存在的意义不是统计，而是让导入页说人话：用户拿到一份公式全是 MathType 对象的卷子，
 * 看到题干里一串「⟦公式⟧」和一个明确提示，比看到缺字少词的句子更容易理解发生了什么。
 */
data class DocxText(
    val text: String,
    /** `<w:object>`（MathType / 公式 3.0）的处数：这类公式的文字从 XML 里取不到 */
    val equations: Int = 0,
    /** 行内图片（几何图、示意图）的处数：只留占位 */
    val inlineImages: Int = 0,
    /** Word 原生公式（OMML）的个数：文字能取到，但上下标会退化成普通字符 */
    val nativeFormulas: Int = 0,
)

object DocumentTextExtractor {

    /** 嵌入公式的占位符。选 ⟦⟧ 是因为它在题干里几乎不会自然出现，用户一眼能认出是"这里缺东西" */
    const val EQUATION_PLACEHOLDER: String = "⟦公式⟧"

    /** 行内图片的占位符 */
    const val IMAGE_PLACEHOLDER: String = "⟦图⟧"

    /** 纯文本上限（字符，不是字节）：约 2MB UTF-8 文本，远超任何一份手工题库 */
    const val MAX_PLAIN_TEXT_CHARS: Int = 2_000_000

    /** `word/document.xml` 上限：正常 A4 文档几十 KB，8MB 已经非常宽松 */
    const val MAX_DOCX_XML_CHARS: Int = 8_000_000

    private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    private val UTF16_LE_BOM = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
    private val UTF16_BE_BOM = byteArrayOf(0xFE.toByte(), 0xFF.toByte())
    private val GB18030: Charset = Charset.forName("GB18030")

    /**
     * txt/md/csv 解码。中文 Windows 导出的 txt 常见 GBK/GB18030，直接按 UTF-8 读会得到
     * 一整屏替换符（U+FFFD），用户会以为“文件坏了”。顺序：
     * BOM 优先 → 严格 UTF-8 → 严格 GB18030 → 最后才用 UTF-8 宽松解码兜底。
     */
    fun decodePlainText(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        when {
            bytes.startsWith(UTF8_BOM) ->
                return trimToLimit(String(bytes, 3, bytes.size - 3, StandardCharsets.UTF_8))

            bytes.startsWith(UTF16_LE_BOM) ->
                return trimToLimit(String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16LE))

            bytes.startsWith(UTF16_BE_BOM) ->
                return trimToLimit(String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16BE))
        }
        strictDecode(bytes, StandardCharsets.UTF_8)?.let { return trimToLimit(it) }
        strictDecode(bytes, GB18030)?.let { return trimToLimit(it) }
        return trimToLimit(String(bytes, StandardCharsets.UTF_8))
    }

    /**
     * 从 .docx（本质是 zip）里取 `word/document.xml`，还原段落与换行。
     * 找不到该条目、zip 损坏、内容为空都返回空串，由调用方给用户一句人话。
     */
    fun extractDocx(input: InputStream): String = extractDocxText(input).text

    /**
     * v3.5.1：除了文字，还把「取不到的内容」数出来。
     *
     * 现实里的试卷/讲义常用 **MathType / 公式 3.0 的嵌入对象** 存公式（`word/embeddings/oleObject*.bin`），
     * 那是二进制，纯文本提取只能拿到它旁边的汉字；结果就是题干里出现"已知函数，则的最小值为"这种
     * 少了半截的句子，用户以为导入坏了。这里给每处公式留一个 [EQUATION_PLACEHOLDER]，
     * 并把数量和图片数量交给导入页提示 —— 丢内容可以，但不能悄悄丢。
     */
    fun extractDocxText(input: InputStream): DocxText {
        val xml = ZipInputStream(BufferedInputStream(input)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name == "word/document.xml") {
                    return@use readLimited(zip, MAX_DOCX_XML_CHARS)
                }
                entry = zip.nextEntry
            }
            ""
        }
        val parsed = docxXmlToDocxText(xml)
        return parsed.copy(text = trimToLimit(parsed.text))
    }

    /**
     * WordprocessingML → 纯文本。
     *
     * 只认三类信号：`<w:t>` 里的文字、段落结束 `</w:p>`、`<w:br/>`/`<w:cr/>`/`<w:tab/>`。
     * 不引 XML 解析器是刻意的：Android 与宿主 JVM 的 `javax.xml` 实现并不一致，而这里要处理的
     * 只是 Word 自己生成的、严格转义过的 XML；顺序扫描比拉一棵树更省内存，也不会被命名空间
     * 前缀差异绊住。`<w:delText>`（修订删除）、批注、域代码不在 `<w:t>` 里，自然被跳过。
     */
    fun docxXmlToText(xml: String): String = docxXmlToDocxText(xml).text

    /**
     * WordprocessingML → 纯文本（带三类"取不到"的计数）。
     *
     * 认这些信号：`<w:t>` 文字、`<m:t>`（Word 原生公式里的文字）、段落结束 `</w:p>`、
     * `<w:br/>`/`<w:cr/>`/`<w:tab/>`、表格单元格（还原成 `|` 分列）。
     *
     * 对**取不到内容的三种东西**的处理（v3.5.1）：
     * - `<w:object>`（MathType / 公式 3.0 嵌入对象）→ 留 [EQUATION_PLACEHOLDER] 并计数；
     * - `<w:drawing>`/`<w:pict>` 里的图片 → 行内图留 [IMAGE_PLACEHOLDER] 并计数；
     *   `wp:anchor` 那种漂浮对象多半是水印/版式装饰，直接跳过不留痕；
     * - 带文本框的图形 → 不跳过，让里面的 `<w:t>` 正常读出来（有些讲义把题干放在文本框里）。
     *
     * 不引 XML 解析器仍是刻意的：Word 生成的 XML 严格转义，顺序扫描省内存、也不吃命名空间前缀。
     * `<w:delText>`（修订删除）、批注、域代码不在 `<w:t>` 里，自然被跳过。
     */
    fun docxXmlToDocxText(xml: String): DocxText {
        if (xml.isEmpty()) return DocxText("")
        val out = StringBuilder(minOf(xml.length, MAX_DOCX_XML_CHARS))
        var cursor = 0
        var tableDepth = 0
        var cellsInRow = 0
        var equations = 0
        var inlineImages = 0
        var nativeFormulas = 0
        while (cursor < xml.length) {
            val open = xml.indexOf('<', cursor)
            if (open < 0) break
            val close = xml.indexOf('>', open + 1)
            if (close < 0) break
            val tag = xml.substring(open + 1, close)
            val inTable = tableDepth > 0

            // 嵌入对象：整块跳过（里面是公式的预览图与 OLE 数据，没有可读文字），留一个占位
            if (isTag(tag, "w:object") && !tag.endsWith("/")) {
                equations++
                out.append(EQUATION_PLACEHOLDER)
                val end = xml.indexOf("</w:object>", close + 1)
                cursor = if (end < 0) xml.length else end + "</w:object>".length
                continue
            }

            // 图形：含文本框的不跳过（题干可能在文本框里），否则行内留占位、锚定的当装饰跳过
            if ((isTag(tag, "w:drawing") || isTag(tag, "w:pict")) && !tag.endsWith("/")) {
                val closeTag = if (tag.startsWith("w:drawing")) "</w:drawing>" else "</w:pict>"
                val end = xml.indexOf(closeTag, close + 1)
                val block = if (end < 0) "" else xml.substring(close + 1, end)
                if (!block.contains("txbxContent", ignoreCase = true)) {
                    if (!block.contains("<wp:anchor")) {
                        inlineImages++
                        out.append(IMAGE_PLACEHOLDER)
                    }
                    cursor = if (end < 0) xml.length else end + closeTag.length
                    continue
                }
            }

            // Word 原生公式（OMML）：整段交给 OmmlToLatex 按结构还原成 $…$，
            // 否则 `<m:t>` 顺序拼接会把 1/2 拼成 12、x² 拼成 x2 —— 那是把题算错，不只是不好看。
            if (isTag(tag, "m:oMath") && !tag.endsWith("/")) {
                val end = findCloseTag(xml, close + 1, "m:oMath")
                if (end < 0) break
                nativeFormulas++
                out.append(OmmlToLatex.renderMath(xml.substring(close + 1, end)))
                cursor = end + "</m:oMath>".length
                continue
            }

            when {
                // 兜底：公式标签之外的零散 m:t（例如 OMML 被拆开写）仍按普通文字取
                (tag == "m:t" || tag.startsWith("m:t ")) && !tag.endsWith("/") -> {
                    val end = xml.indexOf("</m:t>", close + 1)
                    if (end < 0) break
                    out.append(decodeXmlEntities(xml.substring(close + 1, end)))
                    cursor = end + "</m:t>".length
                    continue
                }

                (tag == "w:t" || tag.startsWith("w:t ")) && !tag.endsWith("/") -> {
                    val end = xml.indexOf("</w:t>", close + 1)
                    if (end < 0) break
                    out.append(decodeXmlEntities(xml.substring(close + 1, end)))
                    cursor = end + "</w:t>".length
                    continue
                }

                // 表格：一行一题的最自然还原是 `单元格|单元格|…`。
                // 只认顶层表格，单元格内的多段落/换行折成一个空格，避免把一行拆成多行。
                isTag(tag, "w:tbl") -> {
                    tableDepth++
                    cellsInRow = 0
                }

                isTag(tag, "/w:tbl") -> {
                    tableDepth = (tableDepth - 1).coerceAtLeast(0)
                    cellsInRow = 0
                    if (tableDepth == 0) {
                        trimTrailingSpaces(out)
                        out.append('\n')
                    }
                }

                isTag(tag, "w:tr") -> cellsInRow = 0
                isTag(tag, "/w:tr") -> if (inTable) {
                    trimTrailingSpaces(out)
                    out.append('\n')
                    cellsInRow = 0
                }

                isTag(tag, "w:tc") -> if (inTable) {
                    if (cellsInRow > 0) {
                        trimTrailingSpaces(out)
                        out.append('|')
                    }
                    cellsInRow++
                }

                isTag(tag, "w:tab") || tag == "w:tab/" -> out.append(if (inTable) ' ' else '\t')
                isTag(tag, "w:br") || isTag(tag, "w:cr") ->
                    out.append(if (inTable) ' ' else '\n')

                tag == "w:p/" -> out.append(if (inTable) ' ' else '\n')
            }
            // 精确匹配 </w:p>：`</w:pPr>` / `</w:pStyle>` 也以 "/w:p" 开头，
            // 用 startsWith 会让每个带段落属性的段落多插一个空行。
            if (tag == "/w:p") out.append(if (inTable) ' ' else '\n')
            cursor = close + 1
        }
        return DocxText(
            text = out.toString()
                .replace("\u0000", "")
                .replace(Regex("[ \\t]+\\n"), "\n")
                // 同一段里连着两个占位（题目里两个公式挨着）留一个，少一点噪音
                .replace(Regex("(⟦公式⟧)+"), EQUATION_PLACEHOLDER)
                // 导入契约是“一行一题”：Word 表格/段落之间不保留空行，否则用户看到的是一串回车。
                .replace(Regex("\\n{2,}"), "\n")
                .trim(),
            equations = equations,
            inlineImages = inlineImages,
            nativeFormulas = nativeFormulas,
        )
    }

    /** 找到与当前位置配对的结束标签；同名嵌套按深度配平，找不到返回 -1 */
    private fun findCloseTag(xml: String, from: Int, name: String): Int {
        var depth = 0
        var i = from
        while (i < xml.length) {
            val lt = xml.indexOf('<', i)
            if (lt < 0) return -1
            val gt = xml.indexOf('>', lt + 1)
            if (gt < 0) return -1
            val tag = xml.substring(lt + 1, gt)
            when {
                tag == "$name/" -> Unit
                tag == name || tag.startsWith("$name ") -> depth++
                tag == "/$name" -> {
                    if (depth == 0) return lt
                    depth--
                }
            }
            i = gt + 1
        }
        return -1
    }

    private fun isTag(tag: String, name: String): Boolean =
        tag == name || tag == "$name/" || tag.startsWith("$name ")

    /** 单元格内的段落/换行折成了空格；拼下一列或换行前把它们收掉，避免 `题干 | A` 这种空隙 */
    private fun trimTrailingSpaces(out: StringBuilder) {
        while (out.isNotEmpty() && (out.last() == ' ' || out.last() == '\t')) {
            out.setLength(out.length - 1)
        }
    }

    private fun decodeXmlEntities(value: String): String {
        if (!value.contains('&')) return value
        return value
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&amp;", "&")
            .let { NAMED_OR_NUMERIC_ENTITY.replace(it) { match -> numericEntity(match.value) } }
    }

    private fun numericEntity(token: String): String {
        val body = token.removePrefix("&#").removeSuffix(";")
        val codePoint = runCatching {
            if (body.startsWith("x") || body.startsWith("X")) body.substring(1).toInt(16)
            else body.toInt()
        }.getOrNull() ?: return token
        if (codePoint !in 0..0x10FFFF) return token
        return String(Character.toChars(codePoint))
    }

    private fun readLimited(input: InputStream, maxChars: Int): String {
        val reader: Reader = InputStreamReader(input, StandardCharsets.UTF_8)
        val buffer = CharArray(8 * 1024)
        val out = StringBuilder()
        while (out.length < maxChars) {
            val read = reader.read(buffer, 0, minOf(buffer.size, maxChars - out.length))
            if (read <= 0) break
            out.append(buffer, 0, read)
        }
        return out.toString()
    }

    private fun trimToLimit(value: String): String =
        if (value.length <= MAX_PLAIN_TEXT_CHARS) value else value.substring(0, MAX_PLAIN_TEXT_CHARS)

    private fun strictDecode(bytes: ByteArray, charset: Charset): String? = try {
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (size < prefix.size) return false
        return prefix.indices.all { this[it] == prefix[it] }
    }

    /** 命名实体在 Word 里只会出现这五个；剩下的数字实体单独再扫一遍 */
    private val NAMED_OR_NUMERIC_ENTITY = Regex("&#(?:x|X)?[0-9A-Fa-f]+;")
}
