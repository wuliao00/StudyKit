package com.studykit.util

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import com.studykit.data.entity.Mistake
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 本地分享物料：学习海报 PNG + 错题本 PDF（task-7 C）。
 *
 * 两条纪律：
 * 1. **数据不出设备**：文件只写应用自己的 cacheDir，经 FileProvider 授权给系统分享面板，
 *    没有任何上传/网络代码；失败抛异常给调用方转成人话 Toast。
 * 2. **只用真实读数**：海报上的每个数字都由调用方从既有 Stats / 签到流里取，不在这里编。
 *
 * 配色沿用暖纸感（与 AppTheme 浅色侧同源）：页面底 #FAF8F2、卡面白、墨色 #1F1D1A、
 * 次要文字 #766F64、accentInk #00735F、goldInk #8A5A00（金色只给达成/强调）。
 */
object ShareExport {

    private const val AUTHORITY = "com.studykit.fileprovider"

    const val MIME_PNG = "image/png"
    const val MIME_PDF = "application/pdf"

    private const val PAPER = 0xFFFAF8F2.toInt()
    private const val CARD = 0xFFFFFFFF.toInt()
    private const val INK = 0xFF1F1D1A.toInt()
    private const val MUTED = 0xFF766F64.toInt()
    private const val ACCENT_INK = 0xFF00735F.toInt()
    private const val GOLD_INK = 0xFF8A5A00.toInt()
    private const val DIVIDER = 0xFFE7E2D8.toInt()

    /** 海报要展示的读数；全部由调用方从既有 StateFlow 里取，不在这里重算。 */
    data class PosterStats(
        val totalWords: Int,
        val plannedWords: Int,
        val reviewSamples: Int,
        val streakDays: Int,
        val signInDays: Int,
    )

    private val stampFormat = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    /** 生成一张 1080x1350 的暖纸色学习海报 PNG（带 StudyKit 水印）。 */
    fun writeStudyPoster(context: Context, stats: PosterStats, now: Long = System.currentTimeMillis()): File {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        val file = File(dir, "studykit-poster-" + stampFormat.format(Date(now)) + ".png")
        val width = 1080
        val height = 1350
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(PAPER)

            // 顶部品牌行 + 日期
            canvas.drawText("STUDYKIT · 学习海报", 72f, 142f, textPaint(34f, ACCENT_INK, true))
            canvas.drawText(dateFormat.format(Date(now)) + " · 本机生成", 72f, 190f, textPaint(26f, MUTED))

            // 主卡
            paintCard(canvas)
            canvas.drawText("单词总数", 120f, 372f, textPaint(30f, MUTED))
            canvas.drawText(stats.totalWords.toString(), 120f, 512f, textPaint(140f, ACCENT_INK, true))
            canvas.drawText("词", 140f + textPaint(140f, ACCENT_INK, true).measureText(stats.totalWords.toString()), 508f, textPaint(38f, MUTED))

            // 三列读数
            drawStatColumn(canvas, 120f, "有排期的词", stats.plannedWords.toString())
            drawStatColumn(canvas, 410f, "复习记录", stats.reviewSamples.toString())
            drawStatColumn(canvas, 700f, "连续签到", stats.streakDays.toString() + " 天")

            // 金色细线 + 真实累计
            val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = GOLD_INK
                strokeWidth = 3f
            }
            canvas.drawLine(120f, 860f, 960f, 860f, line)
            canvas.drawText(
                "累计签到 " + stats.signInDays + " 天 · 所有记录只在这台手机里",
                120f,
                924f,
                textPaint(30f, MUTED),
            )
            canvas.drawText("打开 StudyKit，把今天的复习做完", 120f, 992f, textPaint(30f, INK, true))

            // 水印 / 副标题
            canvas.drawText("StudyKit · 错题本·背单词·打卡", 72f, 1210f, textPaint(38f, ACCENT_INK, true))
            canvas.drawText("数据不出设备 · 分享前可自行裁掉", 72f, 1262f, textPaint(24f, MUTED))

            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        } finally {
            bitmap.recycle()
        }
        return file
    }

    private fun paintCard(canvas: Canvas) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = CARD }
        canvas.drawRoundRect(64f, 260f, 1016f, 1064f, 44f, 44f, paint)
        val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = DIVIDER
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        canvas.drawRoundRect(64f, 260f, 1016f, 1064f, 44f, 44f, edge)
    }

    private fun drawStatColumn(canvas: Canvas, x: Float, label: String, value: String) {
        canvas.drawText(label, x, 640f, textPaint(28f, MUTED))
        canvas.drawText(value, x, 716f, textPaint(58f, INK, true))
    }

    /**
     * 导出可打印的 A4 错题本 PDF（每题：学科/来源 + 标题 + 题干 + 备注，自动分页）。
     * 每页都带 StudyKit 水印与页码；空列表也允许导出（会得到只剩封面/页脚的 PDF）。
     */
    fun writeMistakesPdf(context: Context, mistakes: List<Mistake>, now: Long = System.currentTimeMillis()): File {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, "studykit-mistakes-" + stampFormat.format(Date(now)) + ".pdf")
        val document = PdfDocument()
        val pageWidth = 595
        val pageHeight = 842
        val margin = 40f
        val contentWidth = pageWidth - 2 * margin
        val ink = textPaint(10.5f, INK)
        val muted = textPaint(10f, MUTED)
        val accent = textPaint(11f, ACCENT_INK, true)
        val title = textPaint(13f, INK, true)
        val header = textPaint(20f, INK, true)
        val footer = textPaint(8.5f, MUTED)

        var pageNo = 0
        var page: PdfDocument.Page? = null
        var canvas: Canvas? = null
        var y = 0f

        fun newPage() {
            page?.let { document.finishPage(it) }
            pageNo += 1
            val started = document.startPage(
                PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNo).create(),
            )
            page = started
            val c = started.canvas
            canvas = c
            c.drawColor(Color.WHITE)
            c.drawText("StudyKit 错题本", margin, 56f, header)
            c.drawText(
                "导出于 " + dateFormat.format(Date(now)) + " · 共 " + mistakes.size + " 道",
                margin,
                76f,
                footer,
            )
            c.drawLine(margin, 90f, pageWidth - margin, 90f, Paint().apply { color = DIVIDER; strokeWidth = 0.8f })
            c.drawText("StudyKit · 错题本·背单词·打卡", margin, pageHeight - 24f, footer)
            val pageLabel = "第 " + pageNo + " 页"
            c.drawText(pageLabel, pageWidth - margin - footer.measureText(pageLabel), pageHeight - 24f, footer)
            y = 118f
        }

        newPage()
        mistakes.forEachIndexed { index, mistake ->
            if (canvas == null) newPage()
            val heightNeeded = 40f + wrappedHeight(mistake.content, ink, contentWidth) +
                wrappedHeight(mistake.note, muted, contentWidth)
            if (y + heightNeeded > pageHeight - 60f) newPage()
            val c = canvas ?: return@forEachIndexed
            c.drawText(
                (index + 1).toString() + ". " + mistake.subject + " · " + sourceLabel(mistake.source),
                margin,
                y,
                accent,
            )
            y += 18f
            c.drawText(mistake.title, margin, y, title)
            y += 20f
            y = drawWrapped(c, mistake.content, ink, margin, y, contentWidth, 15f) + 6f
            if (mistake.note.isNotBlank()) {
                y = drawWrapped(c, "备注：" + mistake.note, muted, margin, y, contentWidth, 14f) + 4f
            }
            y += 14f
        }
        page?.let { document.finishPage(it) }
        FileOutputStream(file).use { out ->
            document.writeTo(out)
        }
        document.close()
        return file
    }

    /** 系统分享面板发出文件（FileProvider 授权）。 */
    fun shareFile(context: Context, file: File, mime: String, chooserTitle: String) {
        val uri = FileProvider.getUriForFile(context, AUTHORITY, file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(intent, chooserTitle)
        if (context !is android.app.Activity) {
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }

    private fun sourceLabel(source: String): String = when (source) {
        Mistake.SOURCE_WORD -> "单词"
        Mistake.SOURCE_PRACTICE -> "刷题"
        Mistake.SOURCE_PHOTO -> "拍照"
        else -> source
    }

    private fun textPaint(size: Float, color: Int, bold: Boolean = false): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            this.color = color
            typeface = Typeface.create(Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
        }

    private fun wrappedHeight(text: String, paint: Paint, maxWidth: Float): Float {
        if (text.isEmpty()) return 0f
        var lines = 0
        for (paragraph in text.split('\n')) {
            val line = StringBuilder()
            for (ch in paragraph) {
                if (line.isNotEmpty() && paint.measureText(line.toString() + ch) > maxWidth) {
                    lines += 1
                    line.setLength(0)
                }
                line.append(ch)
            }
            lines += 1
        }
        return lines * lineHeightOf(paint)
    }

    private fun drawWrapped(
        canvas: Canvas,
        text: String,
        paint: Paint,
        x: Float,
        startY: Float,
        maxWidth: Float,
        lineHeight: Float,
    ): Float {
        if (text.isEmpty()) return startY
        var y = startY
        for (paragraph in text.split('\n')) {
            val line = StringBuilder()
            for (ch in paragraph) {
                if (line.isNotEmpty() && paint.measureText(line.toString() + ch) > maxWidth) {
                    canvas.drawText(line.toString(), x, y, paint)
                    y += lineHeight
                    line.setLength(0)
                }
                line.append(ch)
            }
            if (line.isNotEmpty()) {
                canvas.drawText(line.toString(), x, y, paint)
                y += lineHeight
            }
        }
        return y
    }

    private fun lineHeightOf(paint: Paint): Float = paint.textSize * 1.5f
}
