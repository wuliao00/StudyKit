package com.studykit.util.ocr

import android.content.Context
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
}
