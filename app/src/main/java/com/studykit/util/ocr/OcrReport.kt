package com.studykit.util.ocr

import kotlin.math.abs
import kotlin.math.roundToInt

/** 文本框（原图像素坐标）。v3.8 起随 OCR 行一起带出来，供版面切题/公式定位使用。 */
data class OcrBox(val x0: Int, val y0: Int, val x1: Int, val y1: Int) {
    val width: Int get() = (x1 - x0).coerceAtLeast(0)
    val height: Int get() = (y1 - y0).coerceAtLeast(0)
    val centerX: Float get() = (x0 + x1) / 2f
    val centerY: Float get() = (y0 + y1) / 2f
}

/** 一行 OCR 结果 + 该行的识别置信度（CTC 贪心帧的 max 概率均值，口径见 PpOcrOnnxEngine）。 */
data class OcrLine(val text: String, val score: Float, val box: OcrBox? = null)

/**
 * 一次 OCR 的完整体检报告（v3.7）。
 *
 * 旧门面只把文本拼起来交出去，置信度与「这张图能不能看清」全丢了；拍照入库时用户看到的
 * 只是一段可能错字的文本。这里把三样东西一起带出去，交给导入预览做路由：
 * - [lines]：逐行文本 + 置信度（行号 1 基，与导入解析器的行号一一对应）；
 * - [quality]：拍照质量体检（可为空：调用方没要求体检时）；
 * - [deskewDegrees]：实际估计到的倾斜角（未达到校正阈值时是 0）；
 * - [retriedLines]：低置信度二次识别实际重试的行数，用于解释耗时与质量。
 *
 * [lowConfidenceLineNumbers] 是 v3.7 的「人工确认队列」第一道闸：导入预览会把这些行
 * **默认剔除**，用户核对后可以勾回；这样错误内容不会悄无声息地进 FSRS 复习。
 */
data class OcrReport(
    val lines: List<OcrLine>,
    val quality: PhotoQuality?,
    val deskewDegrees: Float,
    val retriedLines: Int,
    /** v3.8：送入识别的工作图尺寸（摆正后），版面切题用；0 = 调用方没填 */
    val pageWidth: Int = 0,
    val pageHeight: Int = 0,
    /** v3.9：检测到纸张四角并做了透视校正（截图/白底图通常为 false） */
    val perspectiveCorrected: Boolean = false,
    /** v3.11：检测到纸张上下边界弯曲并做了纵向去弯曲 */
    val dewarped: Boolean = false,
    /** v3.11：检测到的弯曲量（最大边界残差 / 页高），未去弯曲时为 0 */
    val curvatureRatio: Float = 0f,
) {
    /** 按字符数加权的平均置信度：长行更能代表整页质量，短行（题号/选项字母）不拉偏均值。 */
    val meanScore: Float
        get() {
            var weight = 0
            var sum = 0f
            for (line in lines) {
                val len = line.text.length.coerceAtLeast(1)
                weight += len
                sum += line.score * len
            }
            return if (weight == 0) 0f else sum / weight
        }

    /** 低于 [LOW_LINE_SCORE] 的行号（1 基）。空结果时为空集。 */
    val lowConfidenceLineNumbers: Set<Int>
        get() {
            val out = LinkedHashSet<Int>()
            lines.forEachIndexed { index, line ->
                if (line.score < LOW_LINE_SCORE) out.add(index + 1)
            }
            return out
        }

    /** 这一批结果是否建议人工确认：有低置信度行、整体偏低，或照片本身有质量问题。 */
    val needsReview: Boolean
        get() = lowConfidenceLineNumbers.isNotEmpty() ||
            meanScore < BATCH_SCORE ||
            quality?.needsRetake == true

    /**
     * 预览页顶部那句提示（没有问题时返回 null，不占屏幕）。
     * 只写事实、不替用户做决定：质量、摆正、置信度、重试次数各占一段，用 · 连接。
     */
    fun previewNote(): String? {
        val parts = ArrayList<String>(4)
        quality?.takeIf { it.needsRetake }?.let { parts.add("照片" + it.issueText) }
        if (abs(deskewDegrees) >= Deskew.MIN_APPLY_DEGREES) {
            parts.add("已自动摆正 " + formatOneDecimal(deskewDegrees) + "°")
        }
        val lowCount = lowConfidenceLineNumbers.size
        if (lowCount > 0 || meanScore < BATCH_SCORE) {
            val percent = (meanScore * 100f).roundToInt().coerceIn(0, 100)
            parts.add(
                if (lowCount > 0) "识别置信度 " + percent + "%（" + lowCount + " 行偏低）"
                else "识别置信度 " + percent + "%",
            )
        }
        if (retriedLines > 0) parts.add("二次识别 " + retriedLines + " 行")
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    companion object {
        /** 单行低于它就算低置信度，导入预览默认剔除。 */
        const val LOW_LINE_SCORE = 0.72f

        /** 整批加权均值低于它，即使没有单行跌破阈值也提示校对（例如整页都偏糊）。 */
        const val BATCH_SCORE = 0.85f

        private fun formatOneDecimal(value: Float): String {
            val ten = (value * 10f).roundToInt()
            return (ten / 10).toString() + "." + abs(ten % 10).toString()
        }
    }
}
