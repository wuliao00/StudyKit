package com.studykit.util.ocr.geometry

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.studykit.util.ocr.OcrBox
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 图形区域检测：在文字之外画一个明显图形，应该被找出来；纯文字页（文本框覆盖了所有文字）
 * 不应报图形。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FigureDetectorTest {

    private fun blankPage(): Pair<Bitmap, Canvas> {
        val bitmap = Bitmap.createBitmap(420, 560, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        return bitmap to canvas
    }

    @Test
    fun detectsGeometryOutsideTextLines() {
        val (bitmap, canvas) = blankPage()
        val paint = Paint().apply {
            color = Color.BLACK
            strokeWidth = 4f
            style = Paint.Style.STROKE
            isAntiAlias = true
        }
        val textBoxes = ArrayList<OcrBox>()
        val textPaint = Paint().apply { color = Color.BLACK; textSize = 20f; isAntiAlias = true }
        var y = 40f
        repeat(5) {
            canvas.drawText("Sample text line number " + it, 30f, y, textPaint)
            textBoxes.add(OcrBox(26, (y - 22).toInt(), 390, (y + 6).toInt()))
            y += 46f
        }
        // 下半页画三角形 + 圆
        val path = android.graphics.Path()
        path.moveTo(160f, 380f)
        path.lineTo(280f, 380f)
        path.lineTo(220f, 500f)
        path.close()
        canvas.drawPath(path, paint)
        canvas.drawCircle(330f, 450f, 40f, paint)

        val regions = FigureDetector.detect(bitmap, textBoxes)
        assertTrue("should detect at least one figure region: " + regions, regions.isNotEmpty())
        val hit = regions.any { it.y0 > 300 && it.x0 < 360 && it.y1 > 360 }
        assertTrue("figure region should cover lower page: " + regions, hit)
    }

    @Test
    fun plainTextPageHasNoFigure() {
        val (bitmap, canvas) = blankPage()
        val textPaint = Paint().apply { color = Color.BLACK; textSize = 20f; isAntiAlias = true }
        val textBoxes = ArrayList<OcrBox>()
        var y = 50f
        repeat(8) {
            canvas.drawText("Plain paragraph line " + it, 30f, y, textPaint)
            textBoxes.add(OcrBox(24, (y - 22).toInt(), 395, (y + 6).toInt()))
            y += 46f
        }
        val regions = FigureDetector.detect(bitmap, textBoxes)
        assertTrue("plain text should not yield figures: " + regions, regions.isEmpty())
    }
}
