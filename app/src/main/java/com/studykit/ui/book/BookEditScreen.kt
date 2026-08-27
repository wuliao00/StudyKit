package com.studykit.ui.book

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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.studykit.ui.components.AppButton
import com.studykit.ui.components.AppTextField
import com.studykit.ui.theme.DesignTokens

/** 添加 / 编辑书籍页：书名、作者、总页数 */
@Composable
fun BookEditScreen(
    bookId: Long?,
    viewModel: BookViewModel,
    onBack: () -> Unit,
) {
    var title by rememberSaveable { mutableStateOf("") }
    var author by rememberSaveable { mutableStateOf("") }
    var totalPages by rememberSaveable { mutableStateOf("") }
    var loaded by rememberSaveable { mutableStateOf(bookId == null) }

    // 编辑模式：回填已有书籍数据（仅一次）
    if (bookId != null) {
        val book by viewModel.observeBook(bookId).collectAsStateWithLifecycle(initialValue = null)
        LaunchedEffect(book) {
            if (!loaded && book != null) {
                title = book!!.title
                author = book!!.author
                totalPages = book!!.totalPages.toString()
                loaded = true
            }
        }
    }

    val totalPagesValue = totalPages.toIntOrNull() ?: 0

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
                text = if (bookId == null) "添加书籍" else "编辑书籍",
                style = DesignTokens.PageTitle,
            )
        }

        Spacer(Modifier.height(DesignTokens.SpacingLg))
        AppTextField(
            value = title,
            onValueChange = { title = it },
            label = "书名",
            placeholder = "例如：小王子",
        )

        Spacer(Modifier.height(DesignTokens.SpacingLg))
        AppTextField(
            value = author,
            onValueChange = { author = it },
            label = "作者",
            placeholder = "例如：圣-埃克苏佩里",
        )

        Spacer(Modifier.height(DesignTokens.SpacingLg))
        AppTextField(
            value = totalPages,
            onValueChange = { if (it.all(Char::isDigit) && it.length <= 6) totalPages = it },
            label = "总页数",
            placeholder = "例如：97",
        )

        Spacer(Modifier.height(DesignTokens.SpacingXl))
        AppButton(
            text = if (bookId == null) "保存到书架" else "保存修改",
            enabled = loaded && title.isNotBlank() && totalPagesValue > 0,
            onClick = {
                viewModel.saveBook(bookId, title, author, totalPagesValue) { onBack() }
            },
        )
        Spacer(Modifier.height(DesignTokens.SpacingXl))
    }
}
