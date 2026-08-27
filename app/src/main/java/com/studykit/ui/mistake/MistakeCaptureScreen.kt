package com.studykit.ui.mistake

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.studykit.ui.components.AppButton
import com.studykit.ui.components.AppCard
import com.studykit.ui.components.AppMultilineTextField
import com.studykit.ui.components.AppTextField
import com.studykit.ui.components.SectionHeader
import com.studykit.ui.theme.DesignTokens

/**
 * 拍照录入页：拍照返回后选择学科（已有学科 + 可输入新学科）+ 填写标题/备注 → 保存。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MistakeCaptureScreen(
    viewModel: MistakeViewModel,
    onBack: () -> Unit,
    onSaved: () -> Unit,
) {
    val pending by viewModel.pendingCapture.collectAsStateWithLifecycle()
    val subjects by viewModel.subjects.collectAsStateWithLifecycle()
    var selectedSubject by remember { mutableStateOf("") }
    var newSubject by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }

    val finalSubject = newSubject.ifBlank { selectedSubject }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = DesignTokens.PageHorizontalPadding),
    ) {
        Spacer(Modifier.height(DesignTokens.SpacingSm))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = {
                viewModel.discardPendingCapture()
                onBack()
            }) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = DesignTokens.Accent,
                )
            }
            Text(text = "拍照录入", style = DesignTokens.PageTitle)
        }

        Spacer(Modifier.height(DesignTokens.SpacingMd))
        val captured = pending
        if (captured != null && captured.exists()) {
            AppCard(modifier = Modifier.fillMaxWidth()) {
                AsyncImage(
                    model = captured,
                    contentDescription = "拍照预览",
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(DesignTokens.SpacingSm)),
                )
            }
        } else {
            Text(text = "未获取到照片，请返回重新拍照", style = DesignTokens.Caption)
        }

        Spacer(Modifier.height(DesignTokens.SpacingLg))
        SectionHeader(title = "选择学科")
        Spacer(Modifier.height(DesignTokens.SpacingMd))
        if (subjects.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(DesignTokens.SpacingSm),
                verticalArrangement = Arrangement.spacedBy(DesignTokens.SpacingSm),
            ) {
                subjects.forEach { subject ->
                    SubjectOption(
                        label = subject,
                        selected = selectedSubject == subject && newSubject.isBlank(),
                    ) {
                        selectedSubject = subject
                        newSubject = ""
                    }
                }
            }
            Spacer(Modifier.height(DesignTokens.SpacingMd))
        }
        AppTextField(
            value = newSubject,
            onValueChange = { newSubject = it },
            label = "或输入新学科",
            placeholder = "如：物理、化学…",
        )

        Spacer(Modifier.height(DesignTokens.SpacingLg))
        AppTextField(
            value = title,
            onValueChange = { title = it },
            label = "标题",
            placeholder = "给这道错题起个名字",
        )

        Spacer(Modifier.height(DesignTokens.SpacingLg))
        AppMultilineTextField(
            value = note,
            onValueChange = { note = it },
            label = "备注 / 题目内容",
            placeholder = "抄录题干、错因分析或解题思路…",
            minLines = 4,
        )

        Spacer(Modifier.height(DesignTokens.SpacingXl))
        AppButton(
            text = "保存错题",
            enabled = finalSubject.isNotBlank(),
            onClick = {
                viewModel.savePhotoMistake(
                    subject = finalSubject,
                    title = title,
                    note = note,
                    onSaved = onSaved,
                )
            },
        )
        Spacer(Modifier.height(DesignTokens.SpacingXl))
    }
}

@Composable
private fun SubjectOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(DesignTokens.CornerRadius))
            .background(if (selected) DesignTokens.Accent.copy(alpha = 0.12f) else DesignTokens.Card)
            .border(
                width = 1.dp,
                color = if (selected) DesignTokens.Accent else DesignTokens.Divider,
                shape = RoundedCornerShape(DesignTokens.CornerRadius),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = DesignTokens.SpacingMd, vertical = DesignTokens.SpacingSm),
    ) {
        Text(
            text = label,
            style = DesignTokens.Auxiliary.copy(
                color = if (selected) DesignTokens.Accent else DesignTokens.PrimaryText,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            ),
        )
    }
}
