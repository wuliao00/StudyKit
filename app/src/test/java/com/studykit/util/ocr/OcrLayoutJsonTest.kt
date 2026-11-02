package com.studykit.util.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrLayoutJsonTest {

    @Test
    fun roundTripKeepsPageFormulaAndWarnings() {
        val meta = OcrLayoutJson.LayoutMeta(
            pagePath = "ocr_pages/a.jpg",
            pageWidth = 800,
            pageHeight = 1200,
            formulaHints = listOf("f(x)=x^2+1"),
            warnings = listOf("含疑似公式"),
            sourceLines = listOf(3, 4),
            formulaCrops = listOf("ocr_pages/a_formula0.png"),
            figureCrops = listOf("ocr_pages/a_figure0.png"),
        )
        val parsed = OcrLayoutJson.parse(OcrLayoutJson.encode(meta))
        assertEquals("ocr_pages/a.jpg", parsed.pagePath)
        assertEquals(800, parsed.pageWidth)
        assertEquals(1200, parsed.pageHeight)
        assertEquals(listOf("f(x)=x^2+1"), parsed.formulaHints)
        assertEquals(listOf("含疑似公式"), parsed.warnings)
        assertEquals(listOf(3, 4), parsed.sourceLines)
        assertEquals(listOf("ocr_pages/a_formula0.png"), parsed.formulaCrops)
        assertEquals(listOf("ocr_pages/a_figure0.png"), parsed.figureCrops)
    }

    @Test
    fun blankOrBrokenJsonFallsBackToEmpty() {
        assertTrue(OcrLayoutJson.parse("").formulaHints.isEmpty())
        assertTrue(OcrLayoutJson.parse("not-json").formulaHints.isEmpty())
        assertEquals("", OcrLayoutJson.parse("{}").pagePath)
    }
}
