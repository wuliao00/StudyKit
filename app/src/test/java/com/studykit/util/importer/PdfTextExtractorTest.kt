package com.studykit.util.importer

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 字体度量在 AAR 的 assets 里，Robolectric 提供合并后的资产表，与真机走同一条 init 路 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PdfTextExtractorTest {

    @Before
    fun setUp() {
        PDFBoxResourceLoader.init(ApplicationProvider.getApplicationContext<Context>())
    }

    @Test
    fun extract_readsTextLayerInReadingOrder() {
        val pdf = onePagePdf(
            listOf(
                "StudyKit question import",
                "line two has text",
                "line three has text",
            ),
        )

        val text = PdfTextExtractor.extract(pdf)

        assertTrue(text.contains("StudyKit question import"))
        assertTrue(text.contains("line two has text"))
        assertTrue(text.contains("line three has text"))
        assertTrue(PdfTextExtractor.hasTextLayer(text))
    }

    @Test
    fun extract_fileOverloadReadsSameContent() {
        val pdf = onePagePdf(listOf("file overload", "second line here", "third line here"))
        val file = kotlin.io.path.createTempFile(suffix = ".pdf").toFile()
        try {
            file.writeBytes(pdf)
            val text = PdfTextExtractor.extract(file)
            assertTrue(text.contains("file overload"))
            assertTrue(text.contains("third line here"))
        } finally {
            file.delete()
        }
    }

    @Test
    fun normalize_collapsesPageBreaksAndNonBreakingSpaces() {
        val raw = "第一行\u00A0  \n\u000C\n\n\n第二行\r\n"

        val text = PdfTextExtractor.normalize(raw)

        assertEquals("第一行\n第二行", text)
    }

    @Test
    fun hasTextLayer_blankOrSymbolOnly_isFalse() {
        assertFalse(PdfTextExtractor.hasTextLayer(""))
        assertFalse(PdfTextExtractor.hasTextLayer("  \n | | |  \n"))
        assertFalse("只有一长行也不算：可能只是水印", PdfTextExtractor.hasTextLayer("01234567890123456789"))
        assertTrue(
            PdfTextExtractor.hasTextLayer("0123456789\nabcdefghij\nklmnopqrst"),
        )
    }

    @Test
    fun hasTextLayer_pageFurnitureOnly_isFalse() {
        val watermark = """
            第 1 页 共 2 页
            扫描全能王
            第 2 页 共 2 页
            扫描全能王
        """.trimIndent()

        assertFalse("页码与水印不是题目正文", PdfTextExtractor.hasTextLayer(watermark))
    }

    private fun onePagePdf(lines: List<String>): ByteArray {
        val out = ByteArrayOutputStream()
        PDDocument().use { document ->
            val page = PDPage()
            document.addPage(page)
            PDPageContentStream(document, page).use { content ->
                content.beginText()
                content.setFont(PDType1Font.HELVETICA, 16f)
                content.newLineAtOffset(40f, 700f)
                lines.forEachIndexed { index, line ->
                    if (index > 0) content.newLineAtOffset(0f, -22f)
                    content.showText(line)
                }
                content.endText()
            }
            document.save(out)
        }
        return out.toByteArray()
    }
}
