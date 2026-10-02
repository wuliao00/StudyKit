package com.studykit.ui.book

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.studykit.data.entity.ChapterTest
import com.studykit.ui.components.AppButton
import com.studykit.ui.components.AppButtonTone
import com.studykit.ui.components.AppCard
import com.studykit.ui.components.AppMultilineTextField
import com.studykit.ui.components.AppTextField
import com.studykit.ui.components.SectionHeader
import com.studykit.ui.theme.AppTheme

/**
 * 章节自测页（v2.7 计划 B Task 18 / spec §3.2）。
 *
 * 两件事一屏：① 新建一道自测（章节标签 / 问题 / 参考答案）；② 已有的题走「先回忆 → 展开对照 →
 * 自评布尔」这条线。自评只有**记住了 / 没记住**两档，不做任何文本比对（无 NLP）——
 * 参照答案只是给用户自己核对的，判定归用户。保存本书第一道时会点亮 EXPLAIN_WHY 贴士
 * （由 `BookViewModel` 触发，渲染落回详情页）。
 */
@Composable
fun ChapterTestScreen(
    bookId: Long,
    viewModel: BookViewModel,
    onBack: () -> Unit,
) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts

    val tests by viewModel.observeChapterTests(bookId).collectAsStateWithLifecycle(initialValue = emptyList())

    var label by rememberSaveable { mutableStateOf("") }
    var question by rememberSaveable { mutableStateOf("") }
    var expected by rememberSaveable { mutableStateOf("") }
    // 每题「展开对照没有」是本页的一次性视图态（离页即清），不进 rememberSaveable：
    // 换题作答时它本就该归零，用普通 remember 的 mutableStateMapOf 按题 id 记即可。
    val revealed = remember { mutableStateMapOf<Long, Boolean>() }

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
            Text(text = "本章自测", style = texts.pageTitle)
        }

        Spacer(Modifier.height(AppTheme.space.lg))
        SectionHeader(title = "新建一道自测")
        Spacer(Modifier.height(AppTheme.space.md))
        AppTextField(
            value = label,
            onValueChange = { label = it },
            label = "章节（可选）",
            placeholder = "例如：第三章 · 记忆的巩固",
        )
        Spacer(Modifier.height(AppTheme.space.md))
        AppMultilineTextField(
            value = question,
            onValueChange = { question = it },
            label = "问题",
            placeholder = "合上书，问自己一句……",
            minLines = 2,
        )
        Spacer(Modifier.height(AppTheme.space.md))
        AppMultilineTextField(
            value = expected,
            onValueChange = { expected = it },
            label = "参考答案",
            placeholder = "用来给自己核对的关键点",
            minLines = 3,
        )
        Spacer(Modifier.height(AppTheme.space.lg))
        AppButton(
            text = "保存这道自测",
            enabled = question.isNotBlank() && expected.isNotBlank(),
            onClick = {
                viewModel.saveChapterTest(
                    bookId = bookId,
                    chapterLabel = label,
                    question = question,
                    expectedAnswer = expected,
                ) {
                    label = ""
                    question = ""
                    expected = ""
                }
            },
        )

        Spacer(Modifier.height(AppTheme.space.xl))
        SectionHeader(title = "题目 · 回忆 → 展开对照 → 自评")
        Spacer(Modifier.height(AppTheme.space.md))
        if (tests.isEmpty()) {
            AppCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "还没有自测题，先在上方建一道",
                    style = texts.caption,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(AppTheme.space.md)) {
                tests.forEach { test ->
                    ChapterTestQuestionBody(
                        test = test,
                        revealed = revealed[test.id] == true,
                        onReveal = { revealed[test.id] = true },
                        onGrade = { passed -> viewModel.gradeChapterTest(test.id, passed) },
                    )
                }
            }
        }
        Spacer(Modifier.height(AppTheme.space.xl))
    }
}

/**
 * 单道章节自测的**无状态内容层**：吃「这道题 + 是否已展开」，吐展开与自评两个回调。
 * 抽出来供 [ExcerptReviewRenderTest] 用点击驱动回忆→展开→自评这条线，不牵 Room / ViewModel。
 */
@Composable
internal fun ChapterTestQuestionBody(
    test: ChapterTest,
    revealed: Boolean,
    onReveal: () -> Unit,
    onGrade: (Boolean) -> Unit,
) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    AppCard(modifier = Modifier.fillMaxWidth()) {
        if (test.chapterLabel.isNotBlank()) {
            Text(text = test.chapterLabel, style = texts.caption)
            Spacer(Modifier.height(AppTheme.space.xs))
        }
        Text(text = test.question, style = texts.body)
        Spacer(Modifier.height(AppTheme.space.sm))
        // 已有自评结果就如实标一下（记住了 / 待再测），这是「查得到」的历史，不催促
        Text(
            text = if (test.passed) "上次自评：记住了" else "上次自评：待再测",
            style = texts.caption.copy(
                color = if (test.passed) colors.successInk else colors.secondaryText,
                fontWeight = FontWeight.Medium,
            ),
        )

        Spacer(Modifier.height(AppTheme.space.md))
        if (!revealed) {
            AppButton(text = "先在脑子里答一遍，再展开对照", secondary = true, onClick = onReveal)
        } else {
            Text(text = "参考答案", style = texts.caption)
            Spacer(Modifier.height(AppTheme.space.xs))
            Text(text = test.expectedAnswer, style = texts.body)
            Spacer(Modifier.height(AppTheme.space.md))
            Row(
                horizontalArrangement = Arrangement.spacedBy(AppTheme.space.sm),
                modifier = Modifier.fillMaxWidth(),
            ) {
                AppButton(
                    text = "没记住",
                    onClick = { onGrade(false) },
                    tone = AppButtonTone.Warning,
                    modifier = Modifier.weight(1f),
                )
                AppButton(
                    text = "记住了",
                    onClick = { onGrade(true) },
                    tone = AppButtonTone.Success,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
