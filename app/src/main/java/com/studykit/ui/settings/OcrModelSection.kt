package com.studykit.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.studykit.data.ocr.OcrModelInstaller
import com.studykit.data.ocr.OcrModelState
import com.studykit.data.ocr.OcrModelStatus
import com.studykit.data.ocr.OcrModels
import com.studykit.ui.components.AppButton
import com.studykit.ui.components.AppCard
import com.studykit.ui.theme.AppTheme
import kotlinx.coroutines.launch

/**
 * 设置页「OCR 模型」分区（v3.6）。
 *
 * 两个模型（检测 9.5 MB + 识别 20.3 MB）从 APK 里挪出来后，这里是它们唯一的入口：
 * 显示每个模型是"已内置 / 已下载 / 未下载 / 失败"，一键下载、一键删除。
 *
 * 三条刻意的取舍：
 * - **内置了就不催下载**：兼容包（模型仍在 assets）显示「已内置」，按钮置灰——
 *   同一次点击不该在本地已经能用的前提下去耗用户 30 MB 流量；
 * - **进度只认字节数**：安装器的状态在下载期间保持 MISSING，只推进 `downloadedBytes`，
 *   这里据此画进度条，不再引入第五种状态（见 `OcrModelInstaller` 的 KDoc）；
 * - **删除只删下载版**：内置模型删不掉，也不该删（那是 APK 的一部分）。
 */
@Composable
fun OcrModelSection(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    val installers = remember { OcrModels.installers(context) }
    val scope = rememberCoroutineScope()
    // 进页面时在 IO 线程核一次"文件是否真在"（装过 21MB 的用户首帧不能再算一遍哈希）。
    LaunchedEffect(installers) {
        installers.forEach { it.refresh() }
    }
    var running by remember { mutableStateOf(false) }
    var lastError by remember { mutableStateOf<String?>(null) }

    val statuses: List<OcrModelStatus> = installers.map { it.status.collectAsStateWithLifecycle().value }
    val anyDownloadable = statuses.any { it.state == OcrModelState.MISSING || it.state == OcrModelState.FAILED }
    val anyDownloaded = statuses.any { it.state == OcrModelState.READY_DOWNLOADED }

    AppCard(modifier = modifier.fillMaxWidth()) {
        Text(text = "OCR 模型（约 30 MB）", style = texts.cardTitle)
        Spacer(Modifier.height(AppTheme.space.xs))
        Text(
            text = "拍照 / 相册录题用的两个离线模型：不预装，下载后留在本机、识别过程不上传。" +
                "下载过一次就常驻，卸载应用才会清掉。整包约 30 MB，建议在 Wi-Fi 下下载。",
            style = texts.aux,
        )
        Spacer(Modifier.height(AppTheme.space.md))

        installers.forEachIndexed { index, installer ->
            ModelRow(
                label = if (index == 0) "文本检测" else "文本识别",
                status = statuses[index],
                colors = colors,
                texts = texts,
            )
            if (index == 0) Spacer(Modifier.height(AppTheme.space.sm))
        }

        lastError?.let { message ->
            Spacer(Modifier.height(AppTheme.space.sm))
            Text(text = message, style = texts.caption.copy(color = colors.warningInk))
        }

        Spacer(Modifier.height(AppTheme.space.md))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppTheme.space.sm),
        ) {
            AppButton(
                text = when {
                    running -> "下载中…"
                    else -> "下载模型"
                },
                enabled = !running && anyDownloadable,
                secondary = true,
                onClick = {
                    lastError = null
                    running = true
                    scope.launch {
                        try {
                            // 顺序下载：两个模型都在前台跑，并发只会让进度条来回跳，
                            // 而且总共 30MB，串行省不了一秒。
                            for (installer in installers) {
                                if (!needsDownload(installer)) continue
                                val ok = installer.install()
                                if (!ok) {
                                    lastError = installer.status.value.error ?: "下载失败"
                                    break
                                }
                            }
                        } finally {
                            // 任何异常（含 OOM）都要把按钮从「下载中…」放出来，否则界面永久卡住。
                            running = false
                        }
                    }
                },
            )
            if (anyDownloaded) {
                TextButton(
                    enabled = !running,
                    onClick = {
                        installers.forEach { it.delete() }
                        lastError = null
                    },
                ) {
                    Text(text = "删除已下载", style = texts.aux.copy(color = colors.accentInk))
                }
            }
        }
    }
}

private fun needsDownload(installer: OcrModelInstaller): Boolean {
    installer.installedFile()?.let { return false }
    return !installer.bundledAvailable()
}

@Composable
private fun ModelRow(
    label: String,
    status: OcrModelStatus,
    colors: com.studykit.ui.theme.AppColors,
    texts: com.studykit.ui.theme.AppTexts,
) {
    val total = status.totalBytes.takeIf { it > 0 } ?: 1L
    val progress = (status.downloadedBytes.toFloat() / total).coerceIn(0f, 1f)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text = label, style = texts.aux, modifier = Modifier.weight(1f))
        Text(
            text = statusText(status),
            style = texts.caption.copy(color = statusColor(status, colors)),
        )
    }
    if (status.downloadedBytes > 0 && status.state == OcrModelState.MISSING) {
        Spacer(Modifier.height(AppTheme.space.xs))
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth(),
            color = colors.accent,
            trackColor = colors.divider,
        )
    }
}

private fun statusText(status: OcrModelStatus): String = when (status.state) {
    OcrModelState.READY_BUNDLED -> "已内置"
    OcrModelState.READY_DOWNLOADED -> "已下载"
    OcrModelState.MISSING ->
        if (status.downloadedBytes > 0) {
            "${mb(status.downloadedBytes)} / ${mb(status.totalBytes)} MB"
        } else {
            "未下载 · ${mb(status.totalBytes)} MB"
        }
    OcrModelState.FAILED -> "失败：${status.error ?: "未知原因"}"
}

private fun statusColor(status: OcrModelStatus, colors: com.studykit.ui.theme.AppColors) = when (status.state) {
    OcrModelState.READY_BUNDLED, OcrModelState.READY_DOWNLOADED -> colors.successInk
    OcrModelState.FAILED -> colors.warningInk
    OcrModelState.MISSING -> colors.secondaryText
}

private fun mb(bytes: Long): String = String.format("%.1f", bytes / 1024.0 / 1024.0)
