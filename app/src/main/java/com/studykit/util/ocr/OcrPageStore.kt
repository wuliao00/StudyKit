package com.studykit.util.ocr

import android.content.Context
import android.graphics.Bitmap
import com.studykit.util.ocr.OcrBox
import java.io.File
import java.util.UUID

/**
 * OCR 来源页/公式裁片的本地仓库（v3.8）。
 *
 * 入库后题目只保存相对路径；校对页按路径回看整页原图，用户才能判断公式/图形占位是否可信。
 * 目录在 filesDir/ocr_pages，卸载应用才会清掉；不做联网、不上传。
 *
 * 安全：对外只接受本仓库写出的相对路径；解析时拒绝绝对路径与 .. 越界，
 * 防止脏数据把任意文件带进界面。
 */
object OcrPageStore {

    const val DIR = "ocr_pages"

    /** 把来源图片复制进仓库，返回相对路径（ocr_pages/<uuid>.<ext>）。失败返回 null。 */
    fun save(context: Context, source: File): String? {
        if (!source.exists() || source.length() == 0L) return null
        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        val ext = source.extension.lowercase().takeIf { it in setOf("jpg", "jpeg", "png", "webp") } ?: "jpg"
        val target = File(dir, UUID.randomUUID().toString() + "." + ext)
        return runCatching {
            source.copyTo(target, overwrite = false)
            DIR + "/" + target.name
        }.getOrNull()
    }

    /** 相对路径 -> 可读文件；非法/不存在返回 null。 */
    fun resolve(context: Context, relative: String): File? {
        if (relative.isBlank()) return null
        if (relative.startsWith("/") || relative.contains("..")) return null
        val root = File(context.filesDir, DIR)
        val file = File(context.filesDir, relative)
        val canonical = runCatching { file.canonicalFile }.getOrNull() ?: return null
        val rootCanonical = runCatching { root.canonicalFile }.getOrNull() ?: return null
        if (!canonical.path.startsWith(rootCanonical.path + File.separator)) return null
        return canonical.takeIf { it.isFile }
    }

    fun delete(context: Context, relative: String) {
        resolve(context, relative)?.delete()
    }

    /**
     * 从已校正/摆正的工作图裁一小块并存进仓库（v3.9 公式/图形占位）。
     * [box] 是工作图像素坐标；裁失败返回 null。输出 PNG，最长边 1200，避免大图。
     */
    fun saveCrop(
        context: Context,
        page: Bitmap,
        box: OcrBox,
        pageKey: String,
        suffix: String,
    ): String? {
        val x0 = box.x0.coerceIn(0, page.width - 1)
        val y0 = box.y0.coerceIn(0, page.height - 1)
        val x1 = box.x1.coerceIn(x0 + 1, page.width)
        val y1 = box.y1.coerceIn(y0 + 1, page.height)
        if (x1 - x0 < 8 || y1 - y0 < 8) return null
        return runCatching {
            val dir = File(context.filesDir, DIR).apply { mkdirs() }
            val base = if (pageKey.isNotBlank()) pageKey else UUID.randomUUID().toString()
            val target = File(dir, base + "_" + suffix + ".png")
            val crop = Bitmap.createBitmap(page, x0, y0, x1 - x0, y1 - y0)
            try {
                target.outputStream().use { out ->
                    crop.compress(Bitmap.CompressFormat.PNG, 90, out)
                }
            } finally {
                crop.recycle()
            }
            DIR + "/" + target.name
        }.getOrNull()
    }

    /** 页面级图形区域裁片：按区域列表截取，最多 4 块。 */
    fun saveRegionCrops(
        context: Context,
        page: Bitmap,
        regions: List<OcrBox>,
        pageKey: String,
    ): List<String> = regions.take(4).mapIndexedNotNull { index, box ->
        saveCrop(context, page, box, pageKey, "figure" + index)
    }

}
