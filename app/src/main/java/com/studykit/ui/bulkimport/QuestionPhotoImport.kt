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
import com.studykit.ui.mistake.MistakeCaptureHandoff
import com.studykit.ui.theme.AppTheme
import com.studykit.util.MistakeImageStore
import com.studykit.util.OcrResult
import com.studykit.util.OcrTextExtractor
import com.studykit.util.importer.ImportPlan
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 拍照录入题目的**状态**（`rememberQuestionPhotoImport` 的返回值）。
 *
 * 三个成员都是"读一次"的口径函数而不是值：菜单文案要随识别进度变（`识别中…`）、权限被拒要出指引，
 * 而这些变化本身就驱动重组 —— 返回持值的对象会把首帧的 `false` 冻结在 `remember` 里。
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
    /** 解析出了至少一条四选一题目：照旧进批量导入的预览屏。 */
    data class Preview(val plan: ImportPlan) : PhotoImportOutcome

    /** 没有四选一条目、但文本非空：把照片与文本转进错题本。 */
    data class Fallback(val text: String) : PhotoImportOutcome

    /** 文本是空白：连错题本都填不出内容，只剩重拍提示。 */
    data object Empty : PhotoImportOutcome
}

/**
 * 拍照 OCR 之后的**纯决策**（不碰 Compose、不碰文件），单测直接调它：
 * 四选一解析出条目 ⇒ [PhotoImportOutcome.Preview]；条目为空但还有字 ⇒
 * [PhotoImportOutcome.Fallback]（一页教科书正文永远走这里）；连字都没有 ⇒
 * [PhotoImportOutcome.Empty]。判定只此一处，UI 只负责按结果分派。
 */
internal fun decidePhotoImport(ocrText: String): PhotoImportOutcome {
    val plan = ImportKind.QUESTION.parse(ocrText)
    return when {
        plan.items.isNotEmpty() -> PhotoImportOutcome.Preview(plan)
        ocrText.isNotBlank() -> PhotoImportOutcome.Fallback(ocrText)
        else -> PhotoImportOutcome.Empty
    }
}

/**
 * 拍照录入题目：相机 → [OcrTextExtractor] → [ImportKind.QUESTION] 的解析器 → 既有的预览 / 结果两屏。
 *
 * 刻意与单词库那条「截图取词」汇成同一条管线（都收口在 [ImportViewModel.loadPlan] + `onPreview`）：
 * 批量导入的解析、去重、待修正区、结果页账目全都复用，**不 fork 第二条入库路**。
 * 差别只有一处 —— 取图入口是相机而不是相册。
 *
 * 相机那一段逐字沿用错题拍照已有的范式（`MistakeListScreen`）：
 *  - `RequestPermission(CAMERA)` → `TakePicture(uri)`，落点由 [MistakeImageStore.createCameraTarget] 造；
 *  - 暂存的是**绝对路径字符串**（`rememberSaveable`）而不是 `File`：相机是另一个进程，拍照期间本进程
 *    可能被回收，`remember` 里的 File 会随之消失，回投拿到 null 就静默丢图（终审 C3 同一件事）；
 *  - 权限被拒后展示指引（saveable：转屏不该把指引抹掉只剩一个没反应的按钮）。
 *
 * OCR 出来的照片默认是**一次性的**：识别成四选一、进预览之后才删；解析不出四选一时**不删**
 * （转给错题本当图片，之后由错题本录入页在保存或放弃时清理）。
 * 相机临时目录原本就有 `capture_*.jpg` 堆积问题，四选一与空结果两条路仍只在磁盘上多停留几秒。
 *
 * **解析不出四选一不是死路**：题库只收「题干 | 选项… | 答案」，书页永远过不了这一关；
 * 此时照片**不删**，连同 OCR 文本经 [MistakeCaptureHandoff] 交给错题本录入页
 * （`onFallbackToMistake` 负责导航）。错题本不需要选项与答案，图片 + 自由文本正好落得下。
 */
@Composable
fun rememberQuestionPhotoImport(
    importViewModel: ImportViewModel,
    onPreview: () -> Unit,
    onFallbackToMistake: () -> Unit,
): QuestionPhotoImport {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingPhotoPath by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var showRationale by rememberSaveable { mutableStateOf(false) }
    var showSourceChooser by remember { mutableStateOf(false) }

    /** 拍照与相册两条路的**共同下游**：OCR → 判断四选一/转错题本 → 预览。只此一份。 */
    fun handlePickedPhoto(file: File) {
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) { OcrTextExtractor.recognize(context, file) }
            when (result) {
                is OcrResult.Text -> {
                    busy = false
                    when (val outcome = decidePhotoImport(result.value)) {
                        is PhotoImportOutcome.Preview -> {
                            // 四选一：图进预览屏之后就没用了，删掉
                            file.delete()
                            importViewModel.loadPlan(outcome.plan)
                            onPreview()
                        }

                        is PhotoImportOutcome.Fallback -> {
                            // 不删图：这张照片就是错题本的图片。文本可能有上千字，走应用内单槽
                            // 而不是导航参数（Binder 事务上限，见 MistakeCaptureHandoff）。
                            MistakeCaptureHandoff.stash(file, outcome.text)
                            Toast.makeText(
                                context,
                                "这页不是四选一格式，已转到错题本，可在那儿保存",
                                Toast.LENGTH_LONG,
                            ).show()
                            onFallbackToMistake()
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
                    busy = false
                    file.delete()
                    Toast.makeText(context, result.reason, Toast.LENGTH_SHORT).show()
                }
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
        handlePickedPhoto(file)
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
                handlePickedPhoto(file)
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

    return QuestionPhotoImport(
        start = {
            // 不再直接拉相机：先让用户选“现拍”还是“相册里已有的题”。
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
