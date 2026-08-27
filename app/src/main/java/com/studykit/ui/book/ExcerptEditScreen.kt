package com.studykit.ui.book

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.studykit.ui.components.AppButton
import com.studykit.ui.components.AppMultilineTextField
import com.studykit.ui.components.AppTextField
import com.studykit.ui.theme.DesignTokens

/** 书摘编辑页：内容多行输入 + 页码输入，保存 / 删除 */
@Composable
fun ExcerptEditScreen(
    excerptId: Long?,
    bookId: Long?,
    viewModel: BookViewModel,
    onBack: () -> Unit,
) {
    var content by rememberSaveable { mutableStateOf("") }
    var pageNo by rememberSaveable { mutableStateOf("") }
    var loaded by rememberSaveable { mutableStateOf(excerptId == null) }
    // 编辑模式下书籍 id 从书摘记录中取得
    var resolvedBookId by rememberSaveable { mutableStateOf(bookId ?: 0L) }

    if (excerptId != null) {
        val excerpt by viewModel.observeExcerpt(excerptId).collectAsStateWithLifecycle(initialValue = null)
        LaunchedEffect(excerpt) {
            if (!loaded && excerpt != null) {
                content = excerpt!!.content
                pageNo = excerpt!!.pageNo?.toString() ?: ""
                resolvedBookId = excerpt!!.bookId
                loaded = true
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = DesignTokens.PageHorizontalPadding),
    ) {
        Spacer(Modifier.height(DesignTokens.SpacingSm))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = DesignTokens.Accent,
                )
            }
            Spacer(Modifier.width(DesignTokens.SpacingXs))
            Text(
                text = if (excerptId == null) "添加书摘" else "编辑书摘",
                style = DesignTokens.PageTitle,
            )
        }

        Spacer(Modifier.height(DesignTokens.SpacingLg))
        AppMultilineTextField(
            value = content,
            onValueChange = { content = it },
            label = "书摘内容",
            placeholder = "摘录打动你的句子…",
            minLines = 5,
        )

        Spacer(Modifier.height(DesignTokens.SpacingLg))
        AppTextField(
            value = pageNo,
            onValueChange = { if (it.all(Char::isDigit) && it.length <= 5) pageNo = it },
            label = "页码（可选）",
            placeholder = "例如：63",
        )

        Spacer(Modifier.height(DesignTokens.SpacingXl))
        AppButton(
            text = "保存书摘",
            enabled = loaded && content.isNotBlank() && resolvedBookId > 0L,
            onClick = {
                viewModel.saveExcerpt(
                    excerptId = excerptId,
                    bookId = resolvedBookId,
                    content = content,
                    pageNo = pageNo.toIntOrNull(),
                ) { onBack() }
            },
        )

        if (excerptId != null) {
            Spacer(Modifier.height(DesignTokens.SpacingMd))
            AppButton(
                text = "删除书摘",
                secondary = true,
                onClick = { viewModel.deleteExcerpt(excerptId) { onBack() } },
            )
            Spacer(Modifier.height(DesignTokens.SpacingXs))
            Text(
                text = "删除后无法恢复",
                style = DesignTokens.Caption.copy(fontWeight = FontWeight.Normal),
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(DesignTokens.SpacingXl))
    }
}
