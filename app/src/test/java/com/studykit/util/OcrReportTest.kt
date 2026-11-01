package com.studykit.util

import com.studykit.util.ocr.OcrLine
import com.studykit.util.ocr.OcrReport
import com.studykit.util.ocr.PhotoIssue
import com.studykit.util.ocr.PhotoQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OCR 报告的置信度聚合与提示文案（纯逻辑，不依赖 Android/ONNX）：
 * 低置信度行号决定导入预览默认剔除哪几行，previewNote 决定预览页顶部那句话。
 * 这两件事直接决定"错误内容会不会静默进复习"，所以用字面量钉住。
 */
class OcrReportTest {

    private fun clean(deskew: Float = 0f, retried: Int = 0) = OcrReport(
        lines = listOf(OcrLine("第一行", 0.95f), OcrLine("第二行更长一些", 0.92f)),
        quality = null,
        deskewDegrees = deskew,
        retriedLines = retried,
    )

    @Test
    fun cleanReportHasNoNoteAndNoReview() {
        val report = clean()
        assertNull(report.previewNote())
        assertFalse(report.needsReview)
        assertTrue(report.meanScore > 0.9f)
    }

    @Test
    fun lowLineIsMarkedAndExcludedByNumber() {
        val report = OcrReport(
            lines = listOf(OcrLine("清晰的行", 0.96f), OcrLine("糊掉的一行", 0.5f)),
            quality = null,
            deskewDegrees = 0f,
            retriedLines = 1,
        )
        assertEquals(setOf(2), report.lowConfidenceLineNumbers)
        assertTrue(report.needsReview)
        val note = report.previewNote()
        assertNotNull(note)
        assertTrue("note should mention low lines: " + note, note!!.contains("1 行偏低"))
        assertTrue("note should mention retry: " + note, note.contains("二次识别 1 行"))
    }

    @Test
    fun weightedMeanFavorsLongLines() {
        val report = OcrReport(
            lines = listOf(OcrLine("短", 0.2f), OcrLine("这是一个很长很长的句子", 0.9f)),
            quality = null,
            deskewDegrees = 0f,
            retriedLines = 0,
        )
        // 短行权重 1、长行权重 10：均值应接近 0.9，而不是两者简单平均 0.55
        assertTrue("weighted mean should be > 0.8, got " + report.meanScore, report.meanScore > 0.8f)
    }

    @Test
    fun qualityIssueAppearsInNoteAndForcesReview() {
        val quality = PhotoQuality(
            sharpness = 8f,
            brightness = 120f,
            contrast = 60f,
            glareRatio = 0f,
            tiltDegrees = 0f,
            issues = listOf(PhotoIssue.BLURRY),
        )
        val report = OcrReport(
            lines = listOf(OcrLine("清晰但照片糊", 0.93f)),
            quality = quality,
            deskewDegrees = 0f,
            retriedLines = 0,
        )
        assertTrue(report.needsReview)
        val note = report.previewNote()
        assertNotNull(note)
        assertTrue("note should mention blur: " + note, note!!.contains("图像偏模糊"))
    }

    @Test
    fun deskewAngleIsShownWithOneDecimal() {
        val report = clean(deskew = 3.24f)
        val note = report.previewNote()
        assertNotNull(note)
        assertTrue("note should mention deskew: " + note, note!!.contains("已自动摆正 3.2°"))
    }
}
