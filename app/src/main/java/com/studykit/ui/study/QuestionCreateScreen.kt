package com.studykit.ui.study

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.studykit.ui.components.AppButton
import com.studykit.ui.components.AppMultilineTextField
import com.studykit.ui.components.AppTextField
import com.studykit.ui.theme.DesignTokens

/** 题目录入页：学科 + 题干 + 4 选项 + 正确答案选择 + 解析，保存入 questions 表 */
@Composable
fun QuestionCreateScreen(
    viewModel: StudyViewModel,
    onBack: () -> Unit,
) {
    var subject by rememberSaveable { mutableStateOf("") }
    var stem by rememberSaveable { mutableStateOf("") }
    var optionA by rememberSaveable { mutableStateOf("") }
    var optionB by rememberSaveable { mutableStateOf("") }
    var optionC by rememberSaveable { mutableStateOf("") }
    var optionD by rememberSaveable { mutableStateOf("") }
    var answerIndex by rememberSaveable { mutableStateOf(0) }
    var explanation by rememberSaveable { mutableStateOf("") }

    val options = listOf(optionA, optionB, optionC, optionD)
    val canSave = subject.isNotBlank() && stem.isNotBlank() && options.all { it.isNotBlank() }

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
            Text(text = "录入题目", style = DesignTokens.PageTitle)
        }

        Spacer(Modifier.height(DesignTokens.SpacingLg))
        AppTextField(
            value = subject,
            onValueChange = { subject = it },
            label = "学科",
            placeholder = "例如：数学",
        )
        Spacer(Modifier.height(DesignTokens.SpacingLg))
        AppMultilineTextField(
            value = stem,
            onValueChange = { stem = it },
            label = "题干",
            placeholder = "输入题目内容",
            minLines = 3,
        )
        Spacer(Modifier.height(DesignTokens.SpacingLg))
        AppTextField(
            value = optionA,
            onValueChange = { optionA = it },
            label = "选项 A",
            placeholder = "输入选项内容",
        )
        Spacer(Modifier.height(DesignTokens.SpacingMd))
        AppTextField(
            value = optionB,
            onValueChange = { optionB = it },
            label = "选项 B",
            placeholder = "输入选项内容",
        )
        Spacer(Modifier.height(DesignTokens.SpacingMd))
        AppTextField(
            value = optionC,
            onValueChange = { optionC = it },
            label = "选项 C",
            placeholder = "输入选项内容",
        )
        Spacer(Modifier.height(DesignTokens.SpacingMd))
        AppTextField(
            value = optionD,
            onValueChange = { optionD = it },
            label = "选项 D",
            placeholder = "输入选项内容",
        )

        Spacer(Modifier.height(DesignTokens.SpacingLg))
        Text(text = "正确答案", style = DesignTokens.Caption)
        Spacer(Modifier.height(DesignTokens.SpacingSm))
        Row(horizontalArrangement = Arrangement.spacedBy(DesignTokens.SpacingSm)) {
            listOf("A", "B", "C", "D").forEachIndexed { index, letter ->
                val selected = index == answerIndex
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(DesignTokens.CornerRadius))
                        .background(if (selected) DesignTokens.Success else DesignTokens.Card)
                        .border(
                            width = 1.dp,
                            color = if (selected) DesignTokens.Success else DesignTokens.Divider,
                            shape = RoundedCornerShape(DesignTokens.CornerRadius),
                        )
                        .clickable { answerIndex = index }
                        .padding(vertical = DesignTokens.SpacingSm),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = letter,
                        style = DesignTokens.Auxiliary.copy(
                            color = if (selected) DesignTokens.Card else DesignTokens.PrimaryText,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        ),
                    )
                }
            }
        }

        Spacer(Modifier.height(DesignTokens.SpacingLg))
        AppMultilineTextField(
            value = explanation,
            onValueChange = { explanation = it },
            label = "解析（可选）",
            placeholder = "解释正确答案的思路",
            minLines = 2,
        )

        Spacer(Modifier.height(DesignTokens.SpacingXl))
        AppButton(
            text = "保存题目",
            enabled = canSave,
            onClick = {
                viewModel.saveQuestion(subject, stem, options, answerIndex, explanation) { onBack() }
            },
        )
        Spacer(Modifier.height(DesignTokens.SpacingXl))
    }
}
