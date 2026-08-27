package com.studykit.ui.study

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.studykit.ui.components.AppButton
import com.studykit.ui.components.AppTextField
import com.studykit.ui.theme.DesignTokens

/** 单词录入页：单词 + 释义 + 例句表单，保存入 words 表 */
@Composable
fun WordCreateScreen(
    viewModel: StudyViewModel,
    onBack: () -> Unit,
) {
    var word by rememberSaveable { mutableStateOf("") }
    var meaning by rememberSaveable { mutableStateOf("") }
    var example by rememberSaveable { mutableStateOf("") }

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
            Text(text = "录入单词", style = DesignTokens.PageTitle)
        }

        Spacer(Modifier.height(DesignTokens.SpacingLg))
        AppTextField(
            value = word,
            onValueChange = { word = it },
            label = "单词",
            placeholder = "例如：serendipity",
        )
        Spacer(Modifier.height(DesignTokens.SpacingLg))
        AppTextField(
            value = meaning,
            onValueChange = { meaning = it },
            label = "释义",
            placeholder = "例如：n. 意外发现珍宝的运气",
        )
        Spacer(Modifier.height(DesignTokens.SpacingLg))
        AppTextField(
            value = example,
            onValueChange = { example = it },
            label = "例句（可选）",
            placeholder = "例如：Finding that book was pure serendipity.",
        )

        Spacer(Modifier.height(DesignTokens.SpacingXl))
        AppButton(
            text = "保存单词",
            enabled = word.isNotBlank() && meaning.isNotBlank(),
            onClick = {
                viewModel.saveWord(word, meaning, example) { onBack() }
            },
        )
        Spacer(Modifier.height(DesignTokens.SpacingXl))
    }
}
