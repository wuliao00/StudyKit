package com.studykit.ui.bulkimport

import android.Manifest
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.studykit.ui.theme.AppTheme
import com.studykit.util.MistakeImageStore
import com.studykit.util.ocr.OcrReport
import com.studykit.util.OcrResult
import com.studykit.util.OcrTextExtractor
import com.studykit.util.importer.ImportPlan
import com.studykit.util.importer.TextCleaner
import com.studykit.util.ocr.PhotoQuality
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 拍照录入题目的**状态**（rememberQuestionPhotoImport 的返回值）。
 *
 * 三个成员都是「读一次」的口径函数而不是值：菜单文案要随识别进度变（识别中…）、权限被拒要出指引，
 * 而这些变化本身就驱动重组 —— 返回持值的对象会把首帧的 false 冻结在 remember 里。
 */
class QuestionPhotoImport internal constructor(
    val start: () -> Unit,
    val isBusy: () -> Boolean,
    val isRationaleVisible: () -> Boolean,
)

/**
 * 一次拍照 OCR 文本的三条去向，见 [decidePhotoImport]。
 */
internal sealed interface PhotoImportOutcome {
    /** 解析出了至少一条题目（单选/多选/判断/填空/计算/简答/主观/材料都算）：进批量导入预览屏。 */
    data class Preview(val plan: ImportPlan) : PhotoImportOutcome

    /**
     * 文本非空，但没有识别出「题干 | 选项 | 答案」的分列。
     *
     * 不再自动切错题本：非选择题（填空/简答/主观/材料）本来就不该被四选一格式一票否决。
     * 文字原样放进题库编辑页，由用户补竖线或改写题型，再决定导入。
     */
    data class NeedsFormatting(val text: String) : PhotoImportOutcome

    /** 文本是空白：没有可编辑的内容，只剩重拍/重选提示。 */
    data object Empty : PhotoImportOutcome
}

/**
 * 拍照 OCR 之后的**纯决策**（不碰 Compose、不碰文件），单测直接调它：
 * 解析出条目（无论单选、填空还是简答）⇒ Preview；条目为空但还有字 ⇒ NeedsFormatting；
 * 连字都没有 ⇒ Empty。判定只此一处，UI 只负责按结果分派。
 */
internal fun decidePhotoImport(ocrText: String): PhotoImportOutcome {
    val plan = ImportKind.QUESTION.parse(ocrText)
    return when {
        plan.items.isNotEmpty() -> PhotoImportOutcome.Preview(plan)
        ocrText.isNotBlank() -> PhotoImportOutcome.NeedsFormatting(ocrText)
        else -> PhotoImportOutcome.Empty
    }
}

/**
 * v3.7：OCR 置信度路由的纯函数（单测直接钉，不依赖 Compose）。
 *
 * - [lowConfidenceLineNumbers] 交给导入预览做「默认剔除、可勾回」；
 * - [note] 是预览页顶部那句人话（照片质量/摆正/置信度）；
 * - [excludedCount] 是这批条目里实际会被剔除的条数（只统计真正解析成题目的行）。
 */
internal data class PhotoOcrRouting(
    val outcome: PhotoImportOutcome,
    val lowConfidenceLineNumbers: Set<Int>,
    val note: String?,
    val excludedCount: Int,
)

internal fun routePhotoOcr(ocrText: String, report: OcrReport?): PhotoOcrRouting {
    val outcome = decidePhotoImport(ocrText)
    val low = report?.lowConfidenceLineNumbers.orEmpty()
    val excluded = if (outcome is PhotoImportOutcome.Preview) {
        outcome.plan.items.count { it.sourceLine in low }
    } else {
        0
    }
    val base = report?.previewNote()
    val note = when {
        excluded > 0 -> {
            val tail = "已默认剔除 " + excluded + " 行低置信度内容，核对后可勾回"
            if (base.isNullOrBlank()) tail else base + " · " + tail
        }
        else -> base
    }
    return PhotoOcrRouting(outcome, low, note, excluded)
}

/** v3.7：质量门控命中时暂存的一张小图（只用于弹窗与重拍，不进 OCR）。 */
internal data class QualityGate(val file: File, val quality: PhotoQuality)

/**
 * 拍照录入题目：相机/相册 → 质量门控 → [OcrTextExtractor] → [ImportKind.QUESTION] 解析器 →
 * 既有的预览 / 结果两屏（v3.7 在相机与 OCR 之间插了一道质量门控）。
 *
 * 刻意与单词库那条「截图取词」汇成同一条管线（都收口在 ImportViewModel.loadPlan + onPreview）：
 * 批量导入的解析、去重、待修正区、结果页账目全都复用，不 fork 第二条入库路。
 * 差别只有两处 —— 取图入口是相机/相册，且拍照结果先过质量体检。
 *
 * 相机那一段逐字沿用错题拍照已有的范式：
 *  - RequestPermission(CAMERA) → TakePicture(uri)，落点由 [MistakeImageStore.createCameraTarget] 造；
 *  - 暂存的是**绝对路径字符串**（rememberSaveable）而不是 File：相机是另一个进程，拍照期间本进程
 *    可能被回收，remember 里的 File 会随之消失，回投拿到 null 就静默丢图；
 *  - 权限被拒后展示指引（saveable：转屏不该把指引抹掉只剩一个没反应的按钮）。
 *
 * OCR 出来的照片是**一次性的**：进入预览、进入编辑页或空结果后都会删；文字已经带走，
 * 题库不需要把原图一起存。质量门控弹窗期间文件保留，用户选「仍然识别」才继续，选「重拍」就删。
 */
@Composable
fun rememberQuestionPhotoImport(
    importViewModel: ImportViewModel,
    onPreview: () -> Unit,
    onNeedsFormatting: () -> Unit,
    onOpenSettings: (() -> Unit)? = null,
): QuestionPhotoImport {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingPhotoPath by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var showRationale by rememberSaveable { mutableStateOf(false) }
    var showSourceChooser by remember { mutableStateOf(false) }
    // 质量门控弹窗：命中才非空；File 不进 Bundle（转屏丢弹窗可接受，不会丢照片）
    var qualityGate by remember { mutableStateOf<QualityGate?>(null) }
    var showMissingModelDialog by remember { mutableStateOf(false) }

    /** 走 OCR 并把结果路由到预览 / 题库编辑页。质量门控确认后也回到这里。 */
    fun runOcr(file: File) {
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) { OcrTextExtractor.recognize(context, file) }
            busy = false
            when (result) {
                is OcrResult.Text -> {
                    val routing = routePhotoOcr(result.value, result.report)
                    when (val outcome = routing.outcome) {
                        is PhotoImportOutcome.Preview -> {
                            // 四选一：图进预览屏之后就没用了，删掉
                            file.delete()
                            importViewModel.loadPlan(
                                plan = outcome.plan,
                                ocrNote = routing.note,
                                lowConfidenceLines = routing.lowConfidenceLineNumbers,
                                autoExcludeLowConfidence = routing.excludedCount > 0,
                            )
                            onPreview()
                        }

                        is PhotoImportOutcome.NeedsFormatting -> {
                            file.delete()
                            importViewModel.setDraft(
                                ImportKind.QUESTION,
                                TextCleaner.collapseBlankLines(outcome.text),
                            )
                            val qualityHint = result.report?.quality
                                ?.takeIf { it.needsRetake }
                                ?.let { "（照片" + it.issueText + "）" }
                                .orEmpty()
                            Toast.makeText(
                                context,
                                "没识别出竖线分列" + qualityHint +
                                    "，文字已放进题库编辑页；补上「题干|选项|答案」再预览",
                                Toast.LENGTH_LONG,
                            ).show()
                            onNeedsFormatting()
                        }

                        PhotoImportOutcome.Empty -> {
                            file.delete()
                            Toast.makeText(
                                context,
                                "没认出题目，一行一题写「题干 | 选项 | 答案」再试一次",
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    }
                }

                is OcrResult.Failed -> {
                    file.delete()
                    if (onOpenSettings != null && result.reason.contains("未安装")) {
                        showMissingModelDialog = true
                    } else {
                        Toast.makeText(context, result.reason, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    /** 先体检、再 OCR：有问题就先弹重拍提示，没问题直接进 [runOcr]。 */
    fun inspectPhoto(file: File) {
        busy = true
        scope.launch {
            val quality = withContext(Dispatchers.IO) { OcrTextExtractor.analyzeQuality(file) }
            busy = false
            if (quality != null && quality.needsRetake) {
                qualityGate = QualityGate(file, quality)
            } else {
                runOcr(file)
            }
        }
    }

    val takePicture = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture(),
    ) { success ->
        val file = pendingPhotoPath?.let(::File)
        pendingPhotoPath = null
        if (!success || file == null || !file.exists() || file.length() == 0L) {
            file?.delete()
            // 主动取消（success=false）不打扰；拍了却没留下文件才说白（进程重建后被清、或写盘失败）
            if (success) {
                Toast.makeText(context, "没拿到照片，请重新拍照", Toast.LENGTH_SHORT).show()
            }
            return@rememberLauncherForActivityResult
        }
        inspectPhoto(file)
    }

    // 系统照片选择器：Android 13+ 是 Photo Picker，旧系统由 activity 库回退到 OPEN_DOCUMENT；
    // 两条路都只拿到一个临时读权限的 Uri，所以先复制进 cacheDir 再 OCR（Uri 授权不跨进程/不跨重启）。
    val pickFromGallery = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val file = withContext(Dispatchers.IO) { MistakeImageStore.importPicked(context, uri) }
            if (file == null) {
                busy = false
                Toast.makeText(context, "这张图片读不出来，换一张试试", Toast.LENGTH_SHORT).show()
            } else {
                inspectPhoto(file)
            }
        }
    }

    val requestPermission = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            showRationale = false
            val target = MistakeImageStore.createCameraTarget(context)
            if (target == null) {
                Toast.makeText(context, "相机暂时打不开，再点一次试试", Toast.LENGTH_SHORT).show()
            } else {
                pendingPhotoPath = target.first.absolutePath
                takePicture.launch(target.second)
            }
        } else {
            showRationale = true
        }
    }

    if (showSourceChooser) {
        AlertDialog(
            onDismissRequest = { showSourceChooser = false },
            title = { Text(text = "录入题目照片", style = AppTheme.texts.cardTitle) },
            text = {
                Text(
                    text = "可以直接拍一张，也可以从相册选已经拍好的题。" +
                        "识别后仍是先预览、再决定导入。",
                    style = AppTheme.texts.aux,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showSourceChooser = false
                        requestPermission.launch(Manifest.permission.CAMERA)
                    },
                ) {
                    Text(text = "拍照")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showSourceChooser = false
                        pickFromGallery.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                ) {
                    Text(text = "从相册选择")
                }
            },
        )
    }

    qualityGate?.let { gate ->
        AlertDialog(
            onDismissRequest = { qualityGate = null },
            title = { Text(text = "照片可能不够清楚", style = AppTheme.texts.cardTitle) },
            text = {
                Text(
                    text = "检测到：" + gate.quality.issueText +
                        "。识别结果可能需要逐行核对；也可以换一张更清楚的照片再试。",
                    style = AppTheme.texts.aux,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        qualityGate = null
                        runOcr(gate.file)
                    },
                ) {
                    Text(text = "仍然识别")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        qualityGate = null
                        gate.file.delete()
                        if (!busy) showSourceChooser = true
                    },
                ) {
                    Text(text = "重拍 / 换一张")
                }
            },
        )
    }

    if (showMissingModelDialog) {
        AlertDialog(
            onDismissRequest = { showMissingModelDialog = false },
            title = { Text(text = "OCR 模型还没下载", style = AppTheme.texts.cardTitle) },
            text = {
                Text(
                    text = "拍照识别要先在「设置 → OCR 模型」下载两个离线模型（约 30 MB，建议 Wi-Fi）。" +
                        "下载后全程本机识别，不上传图片。",
                    style = AppTheme.texts.aux,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showMissingModelDialog = false
                        onOpenSettings?.invoke()
                    },
                ) {
                    Text(text = "去设置")
                }
            },
            dismissButton = {
                TextButton(onClick = { showMissingModelDialog = false }) {
                    Text(text = "取消")
                }
            },
        )
    }

    return QuestionPhotoImport(
        start = {
            // 不再直接拉相机：先让用户选「现拍」还是「相册里已有的题」。
            if (!busy) showSourceChooser = true
        },
        isBusy = { busy },
        isRationaleVisible = { showRationale },
    )
}

/** 相机权限被拒后的指引（两个新屏共用这一份文案）。放在 Column 里：自带上方那段间距。 */
@Composable
fun QuestionPhotoRationale(modifier: Modifier = Modifier) {
    Spacer(modifier.height(AppTheme.space.sm))
    Text(
        text = "拍照需要相机权限（从相册选择不需要）。请在系统设置 → 应用 → StudyKit → 权限中" +
            "允许「相机」，或再次点开「＋录入」菜单选「拍照 / 相册录题」重新发起授权。",
        style = AppTheme.texts.caption.copy(color = AppTheme.colors.warningInk),
    )
}
