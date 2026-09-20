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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.studykit.ui.components.AppButton
import com.studykit.ui.components.AppMultilineTextField
import com.studykit.ui.components.EmptyState
import com.studykit.ui.components.StatTile
import com.studykit.ui.theme.AppTheme
import com.studykit.util.importer.ImportPlan
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
) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    var raw by rememberSaveable { mutableStateOf("") }
    var plan by remember { mutableStateOf<ImportPlan?>(null) }
    var parsing by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by viewModel.state.collectAsStateWithLifecycle()

    // 读文件不在组合期做；读完只写 raw，解析与 parsing/plan 的翻转统一交给下面那个 LaunchedEffect。
    // 这里刻意不顺手清 plan / 置 parsing：万一文件内容与框里已有文本一模一样，
    // raw 不变 ⇒ 效果不再触发，那两个赋值就会把界面永久卡在「解析中…」上。
    val openDocument = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        stream.bufferedReader(Charsets.UTF_8).readText()
                    }.orEmpty()
                }.getOrDefault("")
            }
            if (text.isBlank()) {
                Toast.makeText(context, "这个文件是空的或读不出来", Toast.LENGTH_SHORT).show()
            } else {
                raw = text
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
            TextButton(onClick = { openDocument.launch(arrayOf("text/*", "text/csv", "text/plain", "*/*")) }) {
                Text(text = "选文件", style = texts.aux.copy(color = colors.accentInk))
            }
        }
        Spacer(Modifier.height(AppTheme.space.md))
        AppMultilineTextField(
            value = raw,
            onValueChange = { raw = it },
            label = "一行一条",
            placeholder = kind.placeholder,
            minLines = 8,
        )
        Spacer(Modifier.height(AppTheme.space.md))
        when {
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
                        viewModel.loadPlan(current)
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
}
