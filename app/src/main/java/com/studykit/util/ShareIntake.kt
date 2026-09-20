package com.studykit.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import java.io.File
import java.util.UUID

/**
 * 系统分享进来的图片 → 本地临时文件。
 *
 * 必须**先把 content:// 拷进自己的目录**再交给下游：分享方授予的 URI 权限只活到本次投递，
 * 而错题录入页要显示、压缩、还可能 OCR 识别，拖到几分钟后 URI 就失效了。
 * 落到 [MistakeImageStore.cameraTempDir] 与拍照流程同一条路，下游零分支。
 *
 * 取 EXTRA_STREAM 走 `IntentCompat`：裸的 `getParcelableExtra(String)` 在 API 33 起被弃用，
 * 而本仓 minSdk 26 不能直接换成三参重载。
 */
object ShareIntake {

    /** 单张分享；多张时取第一张（其余由 [extractMany] 处理） */
    fun extract(context: Context, intent: Intent): File? = extractMany(context, intent).firstOrNull()

    fun extractMany(context: Context, intent: Intent): List<File> {
        val uris: List<Uri?> = when (intent.action) {
            Intent.ACTION_SEND ->
                listOf(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                    ?: arrayListOf()
            else -> return emptyList()
        }
        return uris.filterNotNull().mapNotNull { uri -> copyToTemp(context, uri) }
    }

    /** internal：Task 13 的「截图取词」复用同一份拷贝逻辑，不写第二遍 */
    internal fun copyToTemp(context: Context, uri: Uri): File? = runCatching {
        val target = File(MistakeImageStore.cameraTempDir(context), "shared-${UUID.randomUUID()}.jpg")
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: return null
        if (target.length() == 0L) {
            target.delete()
            return null
        }
        target
    }.getOrNull()
}
