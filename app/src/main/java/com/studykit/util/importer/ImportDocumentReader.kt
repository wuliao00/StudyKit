package com.studykit.util.importer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.provider.OpenableColumns
import com.studykit.util.PpOcrOnnxEngine
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext

/** 题库文件导入的读取结果：成功给文本（可带一句提示），失败给能直接显示的人话 */
sealed interface DocumentReadResult {
    data class Text(val value: String, val note: String? = null) : DocumentReadResult
    data class Failed(val message: String) : DocumentReadResult
}

/**
 * 文件 → 文本的唯一入口。支持 `.txt/.md/.csv`、`.docx`、`.pdf`；`.doc/.wps` 旧二进制格式明确拒绝，
 * 并告诉用户怎么转。
 *
 * PDF 分两层：
 * 1. **文字层优先**：pdfbox 取出的原文精确、顺序稳定，`|` 分列符也原样保留；
 * 2. **扫描件回退**：没有文字层时用 [PdfRenderer] 逐页渲染，交给已有的 PP-OCRv6 离线识别，
 *    最多 [MAX_OCR_PAGES] 页。OCR 结果只负责把字送进编辑框，用户仍要核对竖线分列。
 *
 * 所有读取都在 IO 线程且带字节上限：SAF 给的是任意文件，不能让一个 2GB 的 PDF 把进程拖死。
 */
object ImportDocumentReader {

    /** 单个文件上限 40MB。题库文本远小于这个数，PDF 扫描件也够用 */
    const val MAX_BYTES: Long = 40L * 1024 * 1024

    /** 扫描版 PDF 最多 OCR 20 页：再多就不是“导入”，而是把手机当扫描仪跑一下午 */
    const val MAX_OCR_PAGES: Int = 20

    private const val DOCX_MIME =
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    private const val DOCX_MIME_FALLBACK = "application/octet-stream"
    private const val RENDER_WIDTH_PX = 1_600

    suspend fun read(context: Context, uri: Uri): DocumentReadResult = withContext(Dispatchers.IO) {
        val name = displayName(context, uri)
        val extension = name.substringAfterLast('.', "").lowercase()
        val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull().orEmpty()

        when {
            extension in setOf("doc", "wps") -> DocumentReadResult.Failed(
                "暂不支持旧版 .doc/.wps。请用 Word/WPS“另存为” .docx 或 PDF 后再试。",
            )

            extension == "docx" || extension == "docm" || mime == DOCX_MIME ->
                readDocx(context, uri)

            extension == "pdf" || mime == "application/pdf" ->
                readPdf(context, uri)

            mime.startsWith("text/") ||
                extension in setOf("txt", "md", "markdown", "csv", "log", "text") ->
                readPlain(context, uri)

            mime == DOCX_MIME_FALLBACK && extension.isNotEmpty() -> DocumentReadResult.Failed(
                "认不出这个文件类型（.$extension）。支持 .txt / .md / .csv / .docx / .pdf。",
            )

            else -> DocumentReadResult.Failed(
                "不支持的文件类型${if (extension.isBlank()) "" else "（.$extension）"}。" +
                    "请选择 .txt / .md / .csv / .docx / .pdf。",
            )
        }
    }

    private fun readPlain(context: Context, uri: Uri): DocumentReadResult {
        val bytes = try {
            readCapped(context, uri)
        } catch (e: DocumentTooLargeException) {
            return tooLarge()
        } catch (e: IOException) {
            return DocumentReadResult.Failed("文件读不出来：${e.localizedMessage ?: "未知原因"}")
        }
        val text = DocumentTextExtractor.decodePlainText(bytes)
        return if (text.isBlank()) {
            DocumentReadResult.Failed("这个文件里没有文字")
        } else {
            DocumentReadResult.Text(text)
        }
    }

    private fun readDocx(context: Context, uri: Uri): DocumentReadResult {
        val text = try {
            declaredSize(context, uri)?.let { if (it > MAX_BYTES) throw DocumentTooLargeException() }
            val input = context.contentResolver.openInputStream(uri)
                ?: throw IOException("无法打开文件")
            input.use { stream ->
                // 即便 Provider 不报 SIZE，也在流上再设一道 40MB 闸；否则一个塞满图片的
                // docx 会让界面一直停在“正在提取文件文字…”。
                DocumentTextExtractor.extractDocx(CappedInputStream(stream))
            }
        } catch (e: DocumentTooLargeException) {
            return tooLarge()
        } catch (e: Exception) {
            return DocumentReadResult.Failed("Word 文件读不出来，可能已损坏：${e.localizedMessage ?: "未知原因"}")
        }
        return if (text.isBlank()) {
            DocumentReadResult.Failed(
                "这份 Word 里没提到文字。如果是扫描图片或旧版 .doc，请转成 PDF 或直接拍照录入。",
            )
        } else {
            DocumentReadResult.Text(text)
        }
    }

    private suspend fun readPdf(context: Context, uri: Uri): DocumentReadResult {
        val temp = File.createTempFile("studykit_import_", ".pdf", context.cacheDir)
        return try {
            copyCapped(context, uri, temp)
            // pdfbox-android 的标准字体度量与 CMap 放在 APK 的 assets 里，不 init 的话
            // 连 Helvetica 的 AFM 都找不到（真机上表现为一句 "resource ... not found"）。
            ensurePdfBoxReady(context)

            val textLayer = try {
                PdfTextExtractor.extract(temp)
            } catch (e: PdfPasswordRequiredException) {
                return DocumentReadResult.Failed(
                    "这份 PDF 有密码。请先用阅读器输入密码，另存为无密码 PDF 后再导入。",
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                null
            }

            if (!textLayer.isNullOrBlank() && PdfTextExtractor.hasTextLayer(textLayer)) {
                return DocumentReadResult.Text(textLayer)
            }

            // 没有文字层（或解析器读不动）：按页渲染后走端侧 OCR
            val ocr = try {
                ocrPdf(context, temp)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                return DocumentReadResult.Failed(
                    "这份 PDF 没有可提取的文字，OCR 也失败了（${e.localizedMessage ?: "未知原因"}）。" +
                        "可以改用“拍照录入题目”。",
                )
            }

            when {
                ocr.text.isBlank() -> DocumentReadResult.Failed(
                    "这份 PDF 既没有文字层，OCR 也没认出内容。可以改用“拍照录入题目”。",
                )

                else -> DocumentReadResult.Text(
                    value = ocr.text,
                    note = buildString {
                        append("扫描版 PDF：已用离线 OCR 识别 ")
                        append(ocr.pages)
                        append(" 页")
                        if (ocr.totalPages > ocr.pages) {
                            append("（共 ")
                            append(ocr.totalPages)
                            append(" 页，只识别前 ")
                            append(MAX_OCR_PAGES)
                            append(" 页）")
                        }
                        append("，建议核对后再导入")
                    },
                )
            }
        } catch (e: DocumentTooLargeException) {
            tooLarge()
        } catch (e: IOException) {
            DocumentReadResult.Failed("PDF 读不出来：${e.localizedMessage ?: "未知原因"}")
        } finally {
            temp.delete()
        }
    }

    private data class OcrPages(val text: String, val pages: Int, val totalPages: Int)

    /** 逐页渲染时只保留单页 bitmap；OCR 完成后立即 recycle，不在内存里堆整份 PDF */
    private suspend fun ocrPdf(context: Context, file: File): OcrPages {
        android.os.ParcelFileDescriptor.open(
            file,
            android.os.ParcelFileDescriptor.MODE_READ_ONLY,
        ).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                val total = renderer.pageCount
                val limit = minOf(total, MAX_OCR_PAGES)
                val out = StringBuilder()
                PpOcrOnnxEngine.ensureLoaded(context)
                for (index in 0 until limit) {
                    currentCoroutineContext().ensureActive()
                    renderer.openPage(index).use { page ->
                        val scale = RENDER_WIDTH_PX.toFloat() / page.width.coerceAtLeast(1)
                        val width = RENDER_WIDTH_PX
                        val height = (page.height * scale).toInt().coerceIn(1, 8_000)
                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        try {
                            bitmap.eraseColor(Color.WHITE)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            out.append(
                                PpOcrOnnxEngine.recognize(bitmap)
                                    .joinToString("\n") { it.text },
                            ).append('\n')
                        } finally {
                            bitmap.recycle()
                        }
                    }
                }
                return OcrPages(out.toString().trim(), limit, total)
            }
        }
    }

    @Volatile
    private var pdfBoxReady = false

    /** 资源加载器只需要绑定一次 AssetManager；幂等，失败也不阻断后续的 OCR 回退 */
    @Synchronized
    private fun ensurePdfBoxReady(context: Context) {
        if (pdfBoxReady) return
        runCatching { com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(context.applicationContext) }
        pdfBoxReady = true
    }

    /**
     * 把 SAF 流直接落到缓存文件，边写边数：40MB 上限管住，峰值内存不再出现
     * `ByteArrayOutputStream` 的“一份缓冲 + 一份副本”。
     */
    private fun copyCapped(context: Context, uri: Uri, target: File) {
        declaredSize(context, uri)?.let { if (it > MAX_BYTES) throw DocumentTooLargeException() }
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IOException("无法打开文件")
        input.use { stream ->
            target.outputStream().use { out ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_BYTES) throw DocumentTooLargeException()
                    out.write(buffer, 0, read)
                }
            }
        }
    }

    private fun readCapped(context: Context, uri: Uri): ByteArray {
        declaredSize(context, uri)?.let { if (it > MAX_BYTES) throw DocumentTooLargeException() }
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IOException("无法打开文件")
        input.use { stream ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                total += read
                if (total > MAX_BYTES) throw DocumentTooLargeException()
                out.write(buffer, 0, read)
            }
            return out.toByteArray()
        }
    }

    private fun declaredSize(context: Context, uri: Uri): Long? = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.SIZE),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
        }
    }.getOrNull()

    private fun displayName(context: Context, uri: Uri): String = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment.orEmpty()

    private fun tooLarge() = DocumentReadResult.Failed(
        "文件超过 ${MAX_BYTES / 1024 / 1024}MB。请拆成几份，或只保留题目部分再导入。",
    )

    private class DocumentTooLargeException : IOException("file too large")

    /** 给 docx 的流式读取加一道字节闸：Provider 不报 SIZE 时也不能无限读 */
    private class CappedInputStream(private val delegate: InputStream) : InputStream() {
        private var total = 0L

        override fun read(): Int {
            val value = delegate.read()
            if (value >= 0) count(1)
            return value
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val read = delegate.read(b, off, len)
            if (read > 0) count(read)
            return read
        }

        private fun count(bytes: Int) {
            total += bytes
            if (total > MAX_BYTES) throw DocumentTooLargeException()
        }

        override fun close() = delegate.close()
    }
}
