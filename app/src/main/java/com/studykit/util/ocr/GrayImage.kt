package com.studykit.util.ocr

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * OCR 预处理共用的灰度缩略图（亮度 0..255 的 Float）。
 *
 * [PhotoQualityAnalyzer] 与 [Deskew] 需要的都是"亮度分布 + 边缘"，不需要原图分辨率：
 * 两处共用这一份缩放/取亮度，算符与阈值才有统一量纲，也不会各自漏掉一次 recycle。
 * 采样用带滤波的 createScaledBitmap：OCR 的输入本来就要再缩一次，这里先缩到 640/480 长边，
 * 顺便把高分辨率噪点与 JPEG 压缩伪影压掉，否则拉普拉斯方差会虚高、把糊图判成清晰。
 *
 * 亮度按 BT.601（0.299R + 0.587G + 0.114B），与 Android 的灰度直觉一致；
 * 不转 ColorMatrix 是为了避免再分配一张 ARGB_8888（长边 640 也就 1.5MB，但能省则省）。
 */
internal class GrayImage(val width: Int, val height: Int, val pixels: FloatArray) {
    fun at(x: Int, y: Int): Float = pixels[y * width + x]
}

internal fun grayImageOf(bitmap: Bitmap, maxSide: Int): GrayImage {
    require(maxSide > 0) { "maxSide must be positive" }
    val longSide = max(bitmap.width, bitmap.height)
    val scale = if (longSide > maxSide) maxSide.toFloat() / longSide else 1f
    val w = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
    val h = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
    val scaled = if (w != bitmap.width || h != bitmap.height) {
        Bitmap.createScaledBitmap(bitmap, w, h, true)
    } else {
        bitmap
    }
    try {
        val argb = IntArray(w * h)
        scaled.getPixels(argb, 0, w, 0, 0, w, h)
        val gray = FloatArray(w * h)
        for (i in gray.indices) {
            val c = argb[i]
            gray[i] = 0.299f * ((c shr 16) and 0xFF) +
                0.587f * ((c shr 8) and 0xFF) +
                0.114f * (c and 0xFF)
        }
        return GrayImage(w, h, gray)
    } finally {
        if (scaled !== bitmap) scaled.recycle()
    }
}
