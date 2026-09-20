package com.studykit.ui.study

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.studykit.data.entity.Word
import com.studykit.ui.bulkimport.ImportViewModel
import com.studykit.ui.components.AppButton
import com.studykit.ui.components.AppCard
import com.studykit.ui.components.AppPill
import com.studykit.ui.components.EmptyState
import com.studykit.ui.theme.AppTheme
import com.studykit.util.OcrResult
import com.studykit.util.OcrTextExtractor
import com.studykit.util.ShareIntake
import com.studykit.util.importer.WordLineParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 单词熟练度指示：一枚 [AppPill]（12dp 状态色点 + 柔底状态标签）。
 *
 * 取色：`新词=divider`、`学习中=accent`、`已掌握=success`，三个文案原样保留。
 * 药丸容器与色点同族但走 soft 档（`successSoft`/`accentSoft`，其余 `Transparent`），
 * 于是三态在「一眼扫过」时就能分辨 —— 只有色点时浅色卡上 `divider` 点几乎不可见、
 * `accent` 与 `success` 又只差一点色差，状态差异被抹平了。
 *
 * 药丸文字墨色取本族 ink：`已掌握 → successSoft 底 + successInk`（T15 墨水批次：浅色压深后
 * 压在这层柔底 4.96:1、夜间品牌色本身 5.65:1，两主题都达 AA）、`学习中 → accentSoft 底 + accentInk`
 * （T1 裁定：文本态 accent 仅 3.04:1）、`新词 → Transparent + secondaryText`。
 *
 * 色点仍是纯装饰（同一行已有等价文案），故不加 `semantics`/`contentDescription`，
 * 避免读屏把状态念两遍。圆角/内边距/字号这些几何参数收在 [AppPill] 里，本页只留取色。
 */
@Composable
private fun WordStatusIndicator(status: String) {
    val colors = AppTheme.colors
    val (label, dotColor) = when (status) {
        Word.STATUS_MASTERED -> "已掌握" to colors.success
        Word.STATUS_LEARNING -> "学习中" to colors.accent
        else -> "新词" to colors.divider
    }
    val (pillColor, pillInk) = when (status) {
        Word.STATUS_MASTERED -> colors.successSoft to colors.successInk
        Word.STATUS_LEARNING -> colors.accentSoft to colors.accentInk
        else -> Color.Transparent to colors.secondaryText
    }
    AppPill(
        container = pillColor,
        ink = pillInk,
        label = label,
        leadingDot = dotColor,
    )
}

/**
 * 单词列表页：开始学习主按钮 + 全部单词（单词 + 释义一行 + 熟练度色点与柔底药丸标签）。
 *
 * 颜色与文字样式统一取 `AppTheme`，间距/圆角取 `AppTheme.space` / `AppTheme.radius` 的 dp 常量。
 * 列表条目挂 `Modifier.animateItem()`（[androidx.compose.foundation.lazy.LazyItemScope]）：
 * 录入回来的新词淡入、背完/删除的条目淡出，其余条目位置用 spring 补间让路，
 * 不再出现「整列瞬间跳一格」。进出动画要求条目带 `key`，本页以 `word.id` 为键
 * （`items(words, key = { it.id })`，无 key 时 animateItem 不会生效）。
 */
@Composable
fun WordListScreen(
    viewModel: StudyViewModel,
    importViewModel: ImportViewModel,
    onBack: () -> Unit,
    onStartStudy: () -> Unit,
    onBulkImport: () -> Unit,
    onPreviewImport: () -> Unit,
) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    val words by viewModel.words.collectAsStateWithLifecycle()
    val home by viewModel.homeState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var ocrRunning by remember { mutableStateOf(false) }

    // 截图取词：选一张「一行一词」的截图 → OCR → WordLineParser → 复用批量导入的预览/结果两屏。
    // PickVisualMedia 在 Android 13+ 免权限，低版本系统自己回落到旧选择器，不必分支。
    val pickImage = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        ocrRunning = true
        scope.launch {
            val file = withContext(Dispatchers.IO) { ShareIntake.copyToTemp(context, uri) }
            if (file == null) {
                ocrRunning = false
                Toast.makeText(context, "这张图读不出来", Toast.LENGTH_SHORT).show()
                return@launch
            }
            when (val result = OcrTextExtractor.recognize(context, file)) {
                is OcrResult.Text -> {
                    val plan = WordLineParser.parseAll(result.value)
                    ocrRunning = false
                    if (plan.items.isEmpty()) {
                        Toast.makeText(context, "没认出词表，检查截图是否为一行一词", Toast.LENGTH_SHORT).show()
                    } else {
                        importViewModel.loadPlan(plan)
                        onPreviewImport()
                    }
                }
                is OcrResult.Failed -> {
                    ocrRunning = false
                    Toast.makeText(context, result.reason, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = AppTheme.space.pageH),
    ) {
        Spacer(Modifier.height(AppTheme.space.sm))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    // 图标落在页面底色上，按 T1 裁定走 accentInk（accent 仅 3.04:1）
                    tint = colors.accentInk,
                )
            }
            Spacer(Modifier.width(AppTheme.space.xs))
            Text(text = "单词库", style = texts.pageTitle)
            Spacer(Modifier.weight(1f))
            Text(
                text = "共 ${words.size} 个",
                style = texts.caption,
            )
            Spacer(Modifier.width(AppTheme.space.xs))
            // 与习惯页「+ 添加」同一形态：TextButton + Add 图标 + accentInk 文案（图标纯装饰，文案即语义）
            TextButton(onClick = onBulkImport) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = null,
                    tint = colors.accentInk,
                )
                Spacer(Modifier.width(AppTheme.space.xs))
                Text(
                    text = "批量导入",
                    style = texts.aux.copy(color = colors.accentInk, fontWeight = FontWeight.Medium),
                )
            }
        }

        Spacer(Modifier.height(AppTheme.space.md))
        AppButton(
            text = "开始学习（今日待复习 ${home.dueCount} 个）",
            onClick = onStartStudy,
        )
        Spacer(Modifier.height(AppTheme.space.md))
        AppButton(
            text = if (ocrRunning) "识别中…" else "截图取词",
            secondary = true,
            enabled = !ocrRunning,
            onClick = {
                pickImage.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                )
            },
        )
        Spacer(Modifier.height(AppTheme.space.md))

        if (words.isEmpty()) {
            Spacer(Modifier.height(AppTheme.space.xl * 2))
            EmptyState(
                title = "还没有单词",
                caption = "右上角「批量导入」可以一次贴几十行；逐条录就回学习首页点「录入」",
                icon = Icons.Outlined.Star,
            )
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(AppTheme.space.sm),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(words, key = { it.id }) { word ->
                    AppCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .animateItem(),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = word.word,
                                style = texts.cardTitle,
                            )
                            Spacer(Modifier.width(AppTheme.space.md))
                            Text(
                                text = word.meaning,
                                style = texts.caption,
                                maxLines = 1,
                                modifier = Modifier.weight(1f),
                            )
                            WordStatusIndicator(word.status)
                        }
                    }
                }
                item { Spacer(Modifier.height(AppTheme.space.md)) }
            }
        }
    }
}
