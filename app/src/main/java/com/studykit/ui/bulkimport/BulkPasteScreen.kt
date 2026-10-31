package com.studykit.ui.bulkimport

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.studykit.ui.components.AppButton
import com.studykit.ui.components.AppMultilineTextField
import com.studykit.ui.components.ConfirmDialog
import com.studykit.ui.components.EmptyState
import com.studykit.ui.components.StatTile
import com.studykit.ui.theme.AppTheme
import com.studykit.util.importer.DocumentReadResult
import com.studykit.util.importer.ImportDocumentReader
import com.studykit.util.importer.ImportPlan
import com.studykit.util.importer.TextCleaner
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 智能录入页：一个大输入框，边打边解析。文件导入走同一个解析器，所以这里只多一个 SAF 按钮。
 *
 * 解析在 `Dispatchers.Default` 上跑并做 250ms 防抖：300 行的粘贴如果每敲一个字就重解析，
 * 输入框会明显掉帧（M1 的帧率承诺不允许这种回归）。`LaunchedEffect(raw)` 会在 raw 再变时
 * 取消上一轮，所以不会把旧结果写到新文本上。
 */
@Composable
fun BulkPasteScreen(
    viewModel: ImportViewModel,
    kind: ImportKind,
    onBack: () -> Unit,
    onPreview: () -> Unit,
    /** v3.6：OCR 模型没下载时的"去设置"出口；不传就只显示文字（老调用点不受影响） */
    onOpenSettings: (() -> Unit)? = null,
) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    val raw by viewModel.draft(kind).collectAsStateWithLifecycle()
    var plan by remember { mutableStateOf<ImportPlan?>(null) }
    var parsing by remember { mutableStateOf(false) }
    var readingFile by remember { mutableStateOf(false) }
    var fileError by remember { mutableStateOf<String?>(null) }
    var pendingReplace by remember { mutableStateOf<String?>(null) }
    var pendingNote by remember { mutableStateOf<String?>(null) }
    var pendingSourceNote by remember { mutableStateOf<String?>(null) }
    // v3.5.1：文件来源的提示（公式取不到等），要一路带到预览页
    var sourceNote by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by viewModel.state.collectAsStateWithLifecycle()

    // 输入框已有内容时，选文件/示例题库不直接覆盖：先确认，避免一键抹掉刚整理好的几百行。
    fun acceptText(text: String, note: String?) {
        // 文件/示例导入的空行只留一个换行：题库是一行一题，空行只会让编辑框看起来是一串回车。
        val normalized = TextCleaner.collapseBlankLines(text)
        if (raw.isBlank()) {
            viewModel.setDraft(kind, normalized)
            sourceNote = note
            note?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
        } else {
            pendingReplace = normalized
            pendingNote = note
            pendingSourceNote = note
        }
    }

    // 读文件不在组合期做；读完只写 raw，解析与 parsing/plan 的翻转统一交给下面那个 LaunchedEffect。
    // 这里刻意不顺手清 plan / 置 parsing：万一文件内容与框里已有文本一模一样，
    // raw 不变 ⇒ 效果不再触发，那两个赋值就会把界面永久卡在「解析中…」上。
    val openDocument = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            readingFile = true
            fileError = null
            try {
                when (val result = ImportDocumentReader.read(context, uri)) {
                    is DocumentReadResult.Text -> acceptText(result.value, result.note)

                    is DocumentReadResult.Failed -> fileError = result.message
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: OutOfMemoryError) {
                // 40MB 的 PDF 在低内存机上仍可能触顶；Error 不会被 Exception 接住，
                // 不在这里兜的话表现是闪退而不是一句提示。
                fileError = "文件太大，内存不足。请拆分文件，或只保留题目部分再导入。"
            } catch (e: Exception) {
                // 读文件/OCR 里没被包住的那一层（比如厂商 ContentProvider 抛 SecurityException）
                // 也要落到一句人话上，不能让协程悄悄死掉、按钮一直转。
                fileError = "文件读取失败：${e.localizedMessage ?: e.javaClass.simpleName}"
            } finally {
                readingFile = false
            }
        }
    }

    // 示例题库是 APK 内置资源，不走 SAF：用户点一下就能看到阶段×题型的完整写法，
    // 再照着改成自己的题。内容为原创，不包含任何受版权保护的真题。
    fun loadSampleBank() {
        scope.launch {
            readingFile = true
            try {
                val text = withContext(Dispatchers.IO) {
                    runCatching {
                        context.assets.open("question_banks/classic-starter.txt")
                            .bufferedReader(Charsets.UTF_8)
                            .use { it.readText() }
                    }.getOrDefault("")
                }
                if (text.isBlank()) {
                    fileError = "示例题库没读到，请重装应用后再试"
                } else {
                    acceptText(text, null)
                }
            } finally {
                readingFile = false
            }
        }
    }

    LaunchedEffect(raw) {
        if (raw.isBlank()) {
            plan = null
            parsing = false
            return@LaunchedEffect
        }
        delay(250)
        parsing = true
        plan = withContext(Dispatchers.Default) { kind.parse(raw) }
        parsing = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = AppTheme.space.pageH),
    ) {
        Spacer(Modifier.height(AppTheme.space.sm))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = colors.accentInk,
                )
            }
            Spacer(Modifier.width(AppTheme.space.xs))
            Text(text = kind.title, style = texts.pageTitle)
            Spacer(Modifier.weight(1f))
            TextButton(
                onClick = { openDocument.launch(arrayOf("*/*")) },
                enabled = !readingFile,
            ) {
                Text(text = "选文件", style = texts.aux.copy(color = colors.accentInk))
            }
        }
        // v3.5.1：文件的「取不到公式/图片」提示放在输入框上方。
        // 这类文件往往 0 条可导入（正文没有竖线分列），用户会一直停在编辑器这一屏，
        // 提示放在下面的按钮区就永远看不见了。
        sourceNote?.takeIf { it.isNotBlank() }?.let { note ->
            Spacer(Modifier.height(AppTheme.space.sm))
            Text(text = note, style = texts.caption.copy(color = colors.warningInk))
        }
        Spacer(Modifier.height(AppTheme.space.md))
        AppMultilineTextField(
            value = raw,
            onValueChange = {
                // 手改了文本，来源提示就不再成立（比如用户自己把公式补上了）
                sourceNote = null
                viewModel.setDraft(kind, it)
            },
            label = "一行一条",
            placeholder = kind.placeholder,
            minLines = 8,
        )
        if (kind == ImportKind.QUESTION) {
            // 独立一行，不与标题和「选文件」挤在一条：320dp/1.15 倍字体下，
            // 标题 + 两个 TextButton 会把最后一个按钮压成 0 宽。
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "示例题库：38 道原创题，覆盖 6 个阶段 × 8 种题型",
                    style = texts.caption,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { loadSampleBank() }, enabled = !readingFile) {
                    Text(text = "导入示例", style = texts.aux.copy(color = colors.accentInk))
                }
            }
        }
        Spacer(Modifier.height(AppTheme.space.xs))
        Text(
            text = if (kind == ImportKind.QUESTION) {
                "支持 .txt / .md / .docx / .pdf；扫描版 PDF 会走离线 OCR。" +
                    "「示例题库」为原创题，可自由修改；导入他人题库前请确认授权。"
            } else {
                "支持 .txt / .md / .csv；中文旧编码（GBK/GB18030）会自动识别。"
            },
            style = texts.caption,
        )
        fileError?.let { message ->
            Spacer(Modifier.height(AppTheme.space.sm))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = message,
                    style = texts.caption.copy(color = colors.warningInk),
                    modifier = Modifier.weight(1f),
                )
                // v3.6：精简包没下模型时，OCR 只会报一句"未安装"。这里给一个真能点的出口 ——
                // 用户不可能自己猜到设置里还有一张 OCR 卡片。
                if (onOpenSettings != null && message.contains("未安装")) {
                    TextButton(
                        onClick = {
                            fileError = null
                            onOpenSettings()
                        },
                    ) {
                        Text(text = "去设置下载", style = texts.aux.copy(color = colors.accentInk))
                    }
                }
                TextButton(onClick = { fileError = null }) {
                    Text(text = "知道了", style = texts.aux.copy(color = colors.accentInk))
                }
            }
        }
        // v3.5.1：文件的"取不到公式/图片"提示常驻在输入框上方（见上面那块），这里不再重复。
        Spacer(Modifier.height(AppTheme.space.md))
        when {
            readingFile -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(color = colors.accent, strokeWidth = 2.dp)
                Spacer(Modifier.width(AppTheme.space.sm))
                Text(text = "正在提取文件文字…", style = texts.caption)
            }
            parsing -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(color = colors.accent, strokeWidth = 2.dp)
                Spacer(Modifier.width(AppTheme.space.sm))
                Text(text = "解析中…", style = texts.caption)
            }
            raw.isBlank() -> EmptyState(title = "贴一行试试", caption = kind.hint, icon = kind.icon)
            else -> plan?.let { current ->
                Row(horizontalArrangement = Arrangement.spacedBy(AppTheme.space.sm)) {
                    StatTile(value = "${current.items.size}", label = "可导入", modifier = Modifier.weight(1f))
                    StatTile(value = "${current.rejected.size}", label = "待修正", modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.height(AppTheme.space.lg))
                AppButton(
                    text = "下一步 · 预览 ${current.items.size} 条",
                    enabled = current.items.isNotEmpty(),
                    onClick = {
                        viewModel.loadPlan(current, sourceNote = sourceNote)
                        onPreview()
                    },
                )
            }
        }
        if (state.phase == ImportPhase.DONE) {
            // 从结果页返回时状态还没 reset，给一句实话，避免看起来像没导成功
            Spacer(Modifier.height(AppTheme.space.sm))
            Text(text = "这一批已导入 ${state.outcome?.inserted ?: 0} 条", style = texts.caption)
        }
        Spacer(Modifier.height(AppTheme.space.xl))
    }

    pendingReplace?.let { replacement ->
        ConfirmDialog(
            title = "替换输入框内容？",
            body = "输入框里已有 ${raw.lineSequence().count { it.isNotBlank() }} 行，" +
                "替换后无法撤销。",
            confirmLabel = "替换",
            danger = false,
            onConfirm = {
                viewModel.setDraft(kind, replacement)
                pendingReplace = null
                sourceNote = pendingSourceNote
                pendingSourceNote = null
                pendingNote?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
                pendingNote = null
            },
            onDismiss = {
                pendingReplace = null
                pendingNote = null
                pendingSourceNote = null
            },
        )
    }
}
