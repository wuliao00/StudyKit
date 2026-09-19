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
import com.studykit.ui.components.AppCard
import com.studykit.ui.components.AppMultilineTextField
import com.studykit.ui.components.AppTextField
import com.studykit.ui.motion.StaggeredIn
import com.studykit.ui.theme.AppTheme
import com.studykit.ui.theme.DesignTokens

/**
 * 题目录入页：学科 + 题干 + 4 选项 + 正确答案选择 + 解析，保存入 questions 表。
 *
 * 输入区按语义并成三张 [AppCard]：题目（学科 / 题干）、选项与正确答案（A–D + 选择器）、
 * 解析；保存走 `AppButton`。页面自上而下错峰入场（标题行 → 三张卡 → 按钮，`StaggeredIn`
 * 每级 40ms，下标都是固定小值、无需 minOf 限幅）。
 *
 * 答案选择器的语义与迁移前一致：选中项 = `success` 实底 + `card` 字色 + `SemiBold`，
 * 未选中 = `card` 底 + `divider` 描边 + `primaryText`；圆角仍取 `DesignTokens.CornerRadius`。
 * 浅色主题下 `card`（白）落在 `success` 上约 2.2:1、不足文本 AA —— 属 T15
 * `successInk/warningInk`（或 onSuccess/onWarning）令牌批次的已知债，本页按「令牌迁移不改语义」
 * 保留品牌色，不自造 ink 令牌。
 * 输入框沿用 T3 已迁移的 [AppTextField] / [AppMultilineTextField]（accent 光标、
 * divider 底线），本页不再重复给色。
 */
@Composable
fun QuestionCreateScreen(
    viewModel: StudyViewModel,
    onBack: () -> Unit,
) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
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
        StaggeredIn(index = 0, modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        // 图标落在页面底色上，按 T1 裁定走 accentInk（accent 仅 3.04:1）
                        tint = colors.accentInk,
                    )
                }
                Spacer(Modifier.width(DesignTokens.SpacingXs))
                Text(text = "录入题目", style = texts.pageTitle)
            }
        }

        Spacer(Modifier.height(DesignTokens.SpacingLg))
        StaggeredIn(index = 1, modifier = Modifier.fillMaxWidth()) {
            AppCard(modifier = Modifier.fillMaxWidth()) {
                AppTextField(
                    value = subject,
                    onValueChange = { subject = it },
                    label = "学科",
                    placeholder = "例如：数学",
                )
                Spacer(Modifier.height(DesignTokens.SpacingMd))
                AppMultilineTextField(
                    value = stem,
                    onValueChange = { stem = it },
                    label = "题干",
                    placeholder = "输入题目内容",
                    minLines = 3,
                )
            }
        }

        Spacer(Modifier.height(DesignTokens.SpacingLg))
        StaggeredIn(index = 2, modifier = Modifier.fillMaxWidth()) {
            AppCard(modifier = Modifier.fillMaxWidth()) {
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
                Text(text = "正确答案", style = texts.caption)
                Spacer(Modifier.height(DesignTokens.SpacingSm))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(DesignTokens.SpacingSm),
                ) {
                    listOf("A", "B", "C", "D").forEachIndexed { index, letter ->
                        val selected = index == answerIndex
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(DesignTokens.CornerRadius))
                                .background(if (selected) colors.success else colors.card)
                                .border(
                                    width = 1.dp,
                                    color = if (selected) colors.success else colors.divider,
                                    shape = RoundedCornerShape(DesignTokens.CornerRadius),
                                )
                                .clickable { answerIndex = index }
                                .padding(vertical = DesignTokens.SpacingSm),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = letter,
                                style = texts.aux.copy(
                                    color = if (selected) colors.card else colors.primaryText,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                ),
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(DesignTokens.SpacingLg))
        StaggeredIn(index = 3, modifier = Modifier.fillMaxWidth()) {
            AppCard(modifier = Modifier.fillMaxWidth()) {
                AppMultilineTextField(
                    value = explanation,
                    onValueChange = { explanation = it },
                    label = "解析（可选）",
                    placeholder = "解释正确答案的思路",
                    minLines = 2,
                )
            }
        }

        Spacer(Modifier.height(DesignTokens.SpacingXl))
        StaggeredIn(index = 4, modifier = Modifier.fillMaxWidth()) {
            AppButton(
                text = "保存题目",
                enabled = canSave,
                onClick = {
                    viewModel.saveQuestion(subject, stem, options, answerIndex, explanation) {
                        onBack()
                    }
                },
            )
        }
        Spacer(Modifier.height(DesignTokens.SpacingXl))
    }
}
