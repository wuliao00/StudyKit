package com.studykit.ui.study

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.studykit.data.entity.Question
import com.studykit.data.question.QuestionType
import com.studykit.ui.components.AppCard
import com.studykit.ui.components.AppMultilineTextField
import com.studykit.ui.components.AppTextField
import com.studykit.ui.theme.AppTheme
import com.studykit.util.ocr.OcrLayoutJson
import java.io.File
import org.json.JSONArray

/**
 * v3.8「待校对题目」页：OCR 入库的低置信度/公式占位/结构化切题，先在这里人工过一遍，
 * 确认无误才放回正常复习池；有原图的题可以点开看整页照片。
 *
 * 三类动作：
 * - 确认无误：只清 needs_review，不动题目内容；
 * - 编辑：改题干/选项/答案/解析，保存后自动清标记（答案解析走 applyReviewEdit）；
 * - 删除：连来源页一起清掉（用户明确不要这道题）。
 */
@Composable
fun OcrReviewScreen(
    viewModel: OcrReviewViewModel,
    onBack: () -> Unit,
) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Question?>(null) }
    var previewPage by remember { mutableStateOf<File?>(null) }
    var deleting by remember { mutableStateOf<Question?>(null) }

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
                    tint = colors.accentInk,
                )
            }
            Spacer(Modifier.width(AppTheme.space.xs))
            Text(text = "待校对题目", style = texts.pageTitle)
            Spacer(Modifier.weight(1f))
            Text(
                text = pending.size.toString() + " 道",
                style = texts.caption,
                color = colors.secondaryText,
            )
        }
        Spacer(Modifier.height(AppTheme.space.md))
        if (pending.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "没有待校对题目。拍照/相册录题的低置信度内容会先落到这里。",
                    style = texts.aux,
                    color = colors.secondaryText,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(AppTheme.space.sm),
            ) {
                items(pending, key = { it.id }) { question ->
                    ReviewCard(
                        question = question,
                        pageFile = viewModel.pageFile(question),
                        cropFiles = viewModel.cropFiles(question),
                        onConfirm = { viewModel.confirmReviewed(question.id) },
                        onEdit = { editing = question },
                        onDelete = { deleting = question },
                        onOpenPage = { file -> previewPage = file },
                    )
                }
                item(key = "tail") { Spacer(Modifier.height(AppTheme.space.xl)) }
            }
        }
    }

    editing?.let { question ->
        OcrReviewEditDialog(
            question = question,
            onDismiss = { editing = null },
            onSave = { stem, options, answer, explanation, onResult ->
                viewModel.saveEdit(question, stem, options, answer, explanation) { ok ->
                    if (ok) editing = null
                    onResult(ok)
                }
            },
        )
    }

    previewPage?.let { file ->
        AlertDialog(
            onDismissRequest = { previewPage = null },
            title = { Text(text = "来源页", style = texts.cardTitle) },
            text = {
                AsyncImage(
                    model = file,
                    contentDescription = "OCR 来源页",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 560.dp)
                        .clip(RoundedCornerShape(AppTheme.radius.md)),
                )
            },
            confirmButton = {
                TextButton(onClick = { previewPage = null }) { Text(text = "关闭") }
            },
        )
    }

    deleting?.let { question ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(text = "删除这道题？", style = texts.cardTitle) },
            text = {
                Text(
                    text = "题目和 OCR 来源页会一起删除，不可恢复。",
                    style = texts.aux,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.delete(question)
                        deleting = null
                    },
                ) {
                    Text(text = "删除", color = colors.warningInk)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text(text = "取消") }
            },
        )
    }
}

@Composable
private fun ReviewCard(
    question: Question,
    pageFile: File?,
    cropFiles: List<File>,
    onConfirm: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onOpenPage: (File) -> Unit,
) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    val meta = OcrLayoutJson.parse(question.ocrLayoutJson)
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = question.subject + " · " + question.typeEnum.label,
                style = texts.caption,
                color = colors.accentInk,
                modifier = Modifier.weight(1f),
            )
            if (question.ocrConfidence >= 0f) {
                Text(
                    text = "置信度 " + (question.ocrConfidence * 100).toInt().coerceIn(0, 100) + "%",
                    style = texts.caption,
                    color = colors.warningInk,
                )
            }
        }
        Spacer(Modifier.height(AppTheme.space.xs))
        Text(
            text = question.stem,
            style = texts.body,
            maxLines = 5,
            overflow = TextOverflow.Ellipsis,
        )
        if (meta.formulaHints.isNotEmpty()) {
            Spacer(Modifier.height(AppTheme.space.xs))
            Text(
                text = "含疑似公式/符号 " + meta.formulaHints.size + " 处" +
                    if (meta.formulaCrops.isNotEmpty()) "，已留 " + meta.formulaCrops.size + " 张裁片" else "",
                style = texts.caption,
                color = colors.warningInk,
            )
        }
        if (cropFiles.isNotEmpty()) {
            Spacer(Modifier.height(AppTheme.space.xs))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(AppTheme.space.xs),
            ) {
                cropFiles.forEach { file ->
                    AsyncImage(
                        model = file,
                        contentDescription = "公式/图形裁片",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .height(88.dp)
                            .width(132.dp)
                            .clip(RoundedCornerShape(AppTheme.radius.sm))
                            .clickable { onOpenPage(file) },
                    )
                }
            }
        }
        pageFile?.let { file ->
            Spacer(Modifier.height(AppTheme.space.sm))
            AsyncImage(
                model = file,
                contentDescription = "OCR 来源页缩略图",
                contentScale = ContentScale.FillWidth,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp)
                    .clip(RoundedCornerShape(AppTheme.radius.md))
                    .clickable { onOpenPage(file) },
            )
        }
        Spacer(Modifier.height(AppTheme.space.xs))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onConfirm) {
                Text(text = "确认无误", color = colors.accentInk)
            }
            TextButton(onClick = onEdit) {
                Text(text = "编辑", color = colors.accentInk)
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onDelete) {
                Text(text = "删除", color = colors.warningInk)
            }
        }
    }
}

@Composable
private fun OcrReviewEditDialog(
    question: Question,
    onDismiss: () -> Unit,
    onSave: (String, List<String>, String, String, (Boolean) -> Unit) -> Unit,
) {
    val texts = AppTheme.texts
    val colors = AppTheme.colors
    var stem by remember(question.id) { mutableStateOf(question.stem) }
    var optionsText by remember(question.id) {
        mutableStateOf(readOptions(question).joinToString("\n"))
    }
    var answer by remember(question.id) { mutableStateOf(readAnswer(question)) }
    var explanation by remember(question.id) { mutableStateOf(question.explanation) }
    var error by remember(question.id) { mutableStateOf<String?>(null) }
    val choice = question.typeEnum == QuestionType.SINGLE ||
        question.typeEnum == QuestionType.MULTIPLE ||
        question.typeEnum == QuestionType.TRUE_FALSE

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "编辑题目", style = texts.cardTitle) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = "题型：" + question.typeEnum.label,
                    style = texts.caption,
                    color = colors.secondaryText,
                )
                Spacer(Modifier.height(AppTheme.space.sm))
                AppMultilineTextField(
                    value = stem,
                    onValueChange = { stem = it },
                    label = "题干",
                    placeholder = "题干文本",
                    minLines = 3,
                )
                if (choice) {
                    Spacer(Modifier.height(AppTheme.space.sm))
                    AppMultilineTextField(
                        value = optionsText,
                        onValueChange = { optionsText = it },
                        label = "选项（每行一个，判断题不用改）",
                        placeholder = "A 选项1\nB 选项2",
                        minLines = 3,
                    )
                }
                Spacer(Modifier.height(AppTheme.space.sm))
                AppTextField(
                    value = answer,
                    onValueChange = { answer = it },
                    label = answerLabel(question.typeEnum),
                    placeholder = "A / AC / H2O / 参考答案",
                )
                Spacer(Modifier.height(AppTheme.space.sm))
                AppMultilineTextField(
                    value = explanation,
                    onValueChange = { explanation = it },
                    label = "答案解析/评分要点（可空）",
                    placeholder = "解析",
                    minLines = 2,
                )
                error?.let { message ->
                    Spacer(Modifier.height(AppTheme.space.xs))
                    Text(text = message, style = texts.caption, color = colors.warningInk)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val options = if (choice) optionsText.split('\n') else emptyList()
                    onSave(stem, options, answer, explanation) { ok ->
                        if (!ok) error = "答案格式不对：选择题填字母，判断题填正确/错误，填空/简答填答案文本"
                    }
                },
            ) {
                Text(text = "保存并确认")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(text = "取消") }
        },
    )
}

private fun answerLabel(type: QuestionType): String = when (type) {
    QuestionType.SINGLE, QuestionType.MULTIPLE -> "正确答案（字母，多选如 AC）"
    QuestionType.TRUE_FALSE -> "答案（正确 / 错误）"
    QuestionType.FILL, QuestionType.CALCULATION -> "可接受答案（多个用分号分隔）"
    else -> "参考答案"
}

private fun readOptions(question: Question): List<String> = runCatching {
    val array = JSONArray(question.optionsJson)
    buildList(array.length()) {
        for (i in 0 until array.length()) {
            val value = array.optString(i, "")
            if (value.isNotEmpty()) add(value)
        }
    }
}.getOrDefault(emptyList())

private fun readAnswer(question: Question): String = when (question.typeEnum) {
    QuestionType.SINGLE -> checkboxLetter(question.answerIndex)
    QuestionType.MULTIPLE -> question.correctIndices().sorted().joinToString("") { checkboxLetter(it) }
    QuestionType.TRUE_FALSE -> if (question.answerIndex == 0) "正确" else "错误"
    QuestionType.FILL, QuestionType.CALCULATION -> question.acceptedAnswers().joinToString(";")
    else -> question.referenceAnswer()
}

private fun checkboxLetter(index: Int): String {
    if (index < 0 || index > 25) return ""
    return ('A' + index).toString()
}
