package com.studykit.util.importer

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.text.PDFTextStripper

/**
 * PDF 文字层提取（pdfbox-android，Apache-2.0）。
 *
 * 只做“有文字层的 PDF”这一件事：按页面坐标排序取文字，尽量保持阅读顺序。
 * 扫描件（整页是图片）这里会得到空白，调用方再回退到端侧 OCR —— 两条路分开，
 * 是为了让可复制文字的 PDF 拿到精确原文，而不是一律过一遍 OCR。
 *
 * 失败语义：加密需要密码 → [PdfPasswordRequiredException]；其余损坏按 IOException 抛给调用方，
 * 由导入页翻成一句用户能看懂的话。
 */
object PdfTextExtractor {

    /**
     * 供夹具与调用方判断“这份 PDF 到底有没有文字层”。
     *
     * 判定不是简单的字符总数：扫描件常被网盘/扫描 App 加一行“第 1 页 共 2 页”的文字水印，
     * 按总数会误判成“有文字层”，用户拿到的只有页码。这里要求至少 20 个字母/数字，
     * 且至少 3 行“像正文”的内容（每行 ≥4 个字母/数字）。只装一两道题的小 PDF 仍能通过；
     * 只剩页脚/水印的扫描件会落到 OCR。
     */
    fun hasTextLayer(text: String): Boolean {
        // 先剔掉页脚/水印里最常见的“第 1 页 共 2 页”“Page 1 of 2”“- 3 -”这类行；
        // 它们也算字母数字，但没有任何题目信息。
        val bodyLines = text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filterNot { PAGE_FURNITURE.matches(it) }
            .toList()
        val lineCounts = bodyLines.map { line -> line.count { it.isLetterOrDigit() } }
        val alnum = lineCounts.sum()
        if (alnum < 20) return false
        // 1–2 行的 PDF 也可能是真题目（比如一份只有两道题的练习）；不能只按行数一票否决，
        // 否则会掉进 OCR，既慢又把精确原文弄脏。这里要求：
        //  - 至少 2 行像正文（每行 ≥4 个字母/数字）；
        //  - 最长一行 ≥10 个字母/数字（页脚/水印通常是短句）。
        // 只装一两道题的小 PDF 能通过；只剩“扫描全能王/第 1 页”的扫描件仍会走 OCR。
        return lineCounts.count { it >= 4 } >= 2 && (lineCounts.maxOrNull() ?: 0) >= 10
    }

    private val PAGE_FURNITURE = Regex(
        """^(?:第\s*\d{1,4}\s*页.*|page\s*\d{1,4}(?:\s*(?:/|of)\s*\d{1,4})?|\d{1,4}(?:\s*/\s*\d{1,4})?|-\s*\d{1,4}\s*-)$""",
        RegexOption.IGNORE_CASE,
    )

    fun extract(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        return withDocument { PDDocument.load(bytes) }
    }

    /**
     * 文件版：导入页把 SAF 流落到 `cacheDir` 后走这里，避免再持有一份 40MB 的 ByteArray
     * （`ByteArrayOutputStream.toByteArray()` 会复制，峰值直接翻倍）。
     */
    fun extract(file: java.io.File): String {
        if (!file.isFile || file.length() == 0L) return ""
        return withDocument { PDDocument.load(file) }
    }

    private fun withDocument(loader: () -> PDDocument): String {
        val document = try {
            loader()
        } catch (e: InvalidPasswordException) {
            throw PdfPasswordRequiredException(e)
        }
        return document.use { doc ->
            val stripper = PDFTextStripper().apply {
                sortByPosition = true
                lineSeparator = "\n"
                paragraphStart = ""
                paragraphEnd = "\n"
            }
            normalize(stripper.getText(doc))
        }
    }

    /**
     * PDFTextStripper 会输出 `\f` 作页分隔、`\u00A0` 作不换行空格；直接塞进输入框会留下
     * 看不见的字符，行解析与去重都被它们搅乱。统一成普通空白并压掉连续空行。
     */
    fun normalize(raw: String): String = raw
        .replace('\u000C', '\n')
        .replace('\r', '\n')
        .replace('\u00A0', ' ')
        .replace('\u3000', ' ')
        .replace(Regex("[ \\t]+\\n"), "\n")
        // 一行一题：不要把分页/段落空行留在编辑框里，用户要的是连续的行。
        .replace(Regex("\\n{2,}"), "\n")
        .trim()
}

/** PDF 打开了，但需要用户密码。导入页据此给出“先解密再导出”的具体指引 */
class PdfPasswordRequiredException(cause: Throwable) :
    Exception("PDF 已加密，需要密码才能读取", cause)
