package com.studykit.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * 错题图片本地存储：
 * - 拍照原图压缩为长边 1600px / JPEG 80，存入 filesDir/mistake_images/{uuid}.jpg
 * - 同时生成 300px 缩略图，存入 filesDir/mistake_images/thumb/{uuid}.jpg
 * - Room 只存相对路径（如 "mistake_images/xxx.jpg"），读取时经 [resolve] 还原为文件
 */
object MistakeImageStore {

    private const val DIR_NAME = "mistake_images"
    private const val THUMB_DIR = "mistake_images/thumb"
    private const val MAX_LONG_EDGE = 1600
    private const val THUMB_LONG_EDGE = 300
    private const val JPEG_QUALITY = 80

    /**
     * 处理拍照临时文件：压缩 + 生成缩略图。
     * 成功返回相对路径（mistake_images/{uuid}.jpg），失败返回 null。
     */
    fun processCaptured(context: Context, source: File): String? {
        val bitmap = decodeDownsampled(source, MAX_LONG_EDGE) ?: return null
        val scaled = scaleToLongEdge(bitmap, MAX_LONG_EDGE)
        val uuid = UUID.randomUUID().toString()
        val dir = File(context.filesDir, DIR_NAME).apply { mkdirs() }
        val thumbDir = File(context.filesDir, THUMB_DIR).apply { mkdirs() }
        val fullFile = File(dir, "$uuid.jpg")
        val thumbFile = File(thumbDir, "$uuid.jpg")
        return try {
            FileOutputStream(fullFile).use { scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
            val thumb = scaleToLongEdge(scaled, THUMB_LONG_EDGE)
            FileOutputStream(thumbFile).use { thumb.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
            if (thumb !== scaled) thumb.recycle()
            if (scaled !== bitmap) scaled.recycle()
            bitmap.recycle()
            "$DIR_NAME/$uuid.jpg"
        } catch (e: Exception) {
            fullFile.delete()
            thumbFile.delete()
            null
        } finally {
            source.delete()
        }
    }

    /** 相对路径 → 应用内文件 */
    fun resolve(context: Context, relativePath: String): File = File(context.filesDir, relativePath)

    /** 缩略图文件（不存在则返回 null，由调用方回退到大图） */
    fun resolveThumb(context: Context, relativePath: String): File? {
        val name = relativePath.substringAfterLast('/')
        val thumb = File(context.filesDir, "$THUMB_DIR/$name")
        return if (thumb.exists()) thumb else null
    }

    /** 删除大图与缩略图 */
    fun delete(context: Context, relativePath: String) {
        resolve(context, relativePath).delete()
        resolveThumb(context, relativePath)?.delete()
    }

    /** 按目标长边先做采样解码，避免全尺寸解码爆内存 */
    private fun decodeDownsampled(file: File, targetLongEdge: Int): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                null
            } else {
                var sample = 1
                val longEdge = maxOf(bounds.outWidth, bounds.outHeight)
                while (longEdge / sample > targetLongEdge * 2) sample *= 2
                BitmapFactory.decodeFile(
                    file.absolutePath,
                    BitmapFactory.Options().apply { inSampleSize = sample },
                )
            }
        } catch (e: Exception) {
            null
        }
    }

    /** 精确缩放到目标长边；已小于等于目标则原样返回 */
    private fun scaleToLongEdge(src: Bitmap, targetLongEdge: Int): Bitmap {
        val longEdge = maxOf(src.width, src.height)
        if (longEdge <= targetLongEdge) return src
        val ratio = targetLongEdge.toFloat() / longEdge
        val w = (src.width * ratio).toInt().coerceAtLeast(1)
        val h = (src.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, w, h, true)
    }

    /** 供外部使用的相机临时目录（与 file_paths.xml 的 external-files-path camera/ 对应） */
    fun cameraTempDir(context: Context): File =
        File(context.getExternalFilesDir(null), "camera").apply { mkdirs() }
}
