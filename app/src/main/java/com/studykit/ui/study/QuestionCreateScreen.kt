package com.studykit.ui.study

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.studykit.data.question.ExamStage
import com.studykit.data.question.QuestionType
import com.studykit.ui.components.AppButton
import com.studykit.ui.components.AppCard
import com.studykit.ui.components.AppMultilineTextField
import com.studykit.ui.components.AppTextField
import com.studykit.ui.motion.StaggeredIn
import com.studykit.ui.theme.AppTheme

/**
 * 题目录入页：阶段 + 题型 + 学科 + 题干 + 题型对应字段 + 解析/评分要点。
 *
 * 题型字段不是一张固定表单：
 * - 单选/多选：A–D 四个选项 + 正确项选择；
 * - 判断：选项固定“正确/错误”，只选一个；
 * - 填空/计算：可接受答案（`;` 分隔），应用做规范化比对；
 * - 简答/主观/材料：参考答案（必填）+ 评分要点，应用只让你自评。
 *
 * 阶段与题型都是**筛选元数据**，不替用户判断某道题“应该”属于哪个阶段。
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
    var stage by rememberSaveable { mutableStateOf(ExamStage.UNSPECIFIED) }
    var type by rememberSaveable { mutableStateOf(QuestionType.SINGLE) }
    var optionA by rememberSaveable { mutableStateOf("") }
    var optionB by rememberSaveable { mutableStateOf("") }
    var optionC by rememberSaveable { mutableStateOf("") }
    var optionD by rememberSaveable { mutableStateOf("") }
    var answerLetters by rememberSaveable { mutableStateOf("A") }
    var fillAnswers by rememberSaveable { mutableStateOf("") }
    var referenceAnswer by rememberSaveable { mutableStateOf("") }
    var explanation by rememberSaveable { mutableStateOf("") }

    val options = listOf(optionA, optionB, optionC, optionD)
    val answerIndices = answerLetters.map { it - 'A' }.distinct().sorted()
    val acceptedAnswers = fillAnswers
        .split(';', '；')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
    val canSave = subject.isNotBlank() && stem.isNotBlank() && when (type) {
        QuestionType.SINGLE -> options.all { it.isNotBlank() } && answerIndices.size == 1
        QuestionType.MULTIPLE -> options.all { it.isNotBlank() } && answerIndices.size >= 2
        QuestionType.TRUE_FALSE -> answerIndices.size == 1
        QuestionType.FILL, QuestionType.CALCULATION -> acceptedAnswers.isNotEmpty()
        QuestionType.SHORT_ANSWER,
        QuestionType.ESSAY,
        QuestionType.CASE_ANALYSIS,
        -> referenceAnswer.isNotBlank()
    }

    fun resetFor(candidate: QuestionType) {
        type = candidate
        answerLetters = when (candidate) {
            QuestionType.SINGLE, QuestionType.TRUE_FALSE -> "A"
            else -> ""
        }
        fillAnswers = ""
        referenceAnswer = ""
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = AppTheme.space.pageH),
    ) {
        Spacer(Modifier.height(AppTheme.space.sm))
        StaggeredIn(index = 0, modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        tint = colors.accentInk,
                    )
                }
                Spacer(Modifier.width(AppTheme.space.xs))
                Text(text = "录入题目", style = texts.pageTitle)
            }
        }

        Spacer(Modifier.height(AppTheme.space.lg))
        StaggeredIn(index = 1, modifier = Modifier.fillMaxWidth()) {
            AppCard(modifier = Modifier.fillMaxWidth()) {
                AppTextField(
                    value = subject,
                    onValueChange = { subject = it },
                    label = "学科",
                    placeholder = "例如：数学 / 行测 / 申论",
                )
                Spacer(Modifier.height(AppTheme.space.md))
                AppMultilineTextField(
                    value = stem,
                    onValueChange = { stem = it },
                    label = "题干",
                    placeholder = "输入题目内容；材料题可把材料一并放在这里",
                    minLines = 3,
                )
            }
        }

        Spacer(Modifier.height(AppTheme.space.lg))
        StaggeredIn(index = 2, modifier = Modifier.fillMaxWidth()) {
            AppCard(modifier = Modifier.fillMaxWidth()) {
                Text(text = "阶段", style = texts.caption)
                Spacer(Modifier.height(AppTheme.space.sm))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .selectableGroup(),
                    horizontalArrangement = Arrangement.spacedBy(AppTheme.space.sm),
                ) {
                    ExamStage.entries.forEach { candidate ->
                        FormChip(
                            label = candidate.label,
                            selected = stage == candidate,
                            onClick = { stage = candidate },
                        )
                    }
                }

                Spacer(Modifier.height(AppTheme.space.md))
                Text(text = "题型", style = texts.caption)
                Spacer(Modifier.height(AppTheme.space.sm))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .selectableGroup(),
                    horizontalArrangement = Arrangement.spacedBy(AppTheme.space.sm),
                ) {
                    QuestionType.entries.forEach { candidate ->
                        FormChip(
                            label = candidate.label,
                            selected = type == candidate,
                            onClick = { resetFor(candidate) },
                        )
                    }
                }

                Spacer(Modifier.height(AppTheme.space.lg))
                when (type) {
                    QuestionType.SINGLE, QuestionType.MULTIPLE -> {
                        AppTextField(
                            value = optionA,
                            onValueChange = { optionA = it },
                            label = "选项 A",
                            placeholder = "输入选项内容",
                        )
                        Spacer(Modifier.height(AppTheme.space.md))
                        AppTextField(
                            value = optionB,
                            onValueChange = { optionB = it },
                            label = "选项 B",
                            placeholder = "输入选项内容",
                        )
                        Spacer(Modifier.height(AppTheme.space.md))
                        AppTextField(
                            value = optionC,
                            onValueChange = { optionC = it },
                            label = "选项 C",
                            placeholder = "输入选项内容",
                        )
                        Spacer(Modifier.height(AppTheme.space.md))
                        AppTextField(
                            value = optionD,
                            onValueChange = { optionD = it },
                            label = "选项 D",
                            placeholder = "输入选项内容",
                        )
                        Spacer(Modifier.height(AppTheme.space.lg))
                        Text(
                            text = if (type == QuestionType.MULTIPLE) "正确答案（至少选两个）" else "正确答案",
                            style = texts.caption,
                        )
                        Spacer(Modifier.height(AppTheme.space.sm))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectableGroup(),
                            horizontalArrangement = Arrangement.spacedBy(AppTheme.space.sm),
                        ) {
                            listOf("A", "B", "C", "D").forEach { letter ->
                                AnswerChip(
                                    label = letter,
                                    selected = letter in answerLetters,
                                    role = if (type == QuestionType.MULTIPLE) Role.Checkbox else Role.RadioButton,
                                    modifier = Modifier.weight(1f),
                                    onClick = {
                                        answerLetters = when {
                                            type == QuestionType.SINGLE -> letter
                                            letter in answerLetters -> answerLetters.replace(letter, "")
                                            else -> (answerLetters + letter).toSortedSet().joinToString("")
                                        }
                                    },
                                )
                            }
                        }
                    }

                    QuestionType.TRUE_FALSE -> {
                        Text(text = "判断题选项固定为「正确 / 错误」，只选一个。", style = texts.aux)
                        Spacer(Modifier.height(AppTheme.space.sm))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectableGroup(),
                            horizontalArrangement = Arrangement.spacedBy(AppTheme.space.sm),
                        ) {
                            listOf("A" to "正确", "B" to "错误").forEach { (letter, label) ->
                                AnswerChip(
                                    label = label,
                                    selected = answerLetters == letter,
                                    role = Role.RadioButton,
                                    modifier = Modifier.weight(1f),
                                    onClick = { answerLetters = letter },
                                )
                            }
                        }
                    }

                    QuestionType.FILL, QuestionType.CALCULATION -> {
                        AppMultilineTextField(
                            value = fillAnswers,
                            onValueChange = { fillAnswers = it },
                            label = if (type == QuestionType.CALCULATION) {
                                "可接受的最终答案（用 ; 分隔）"
                            } else {
                                "可接受答案（用 ; 分隔）"
                            },
                            placeholder = "答案1;答案2",
                            minLines = 2,
                        )
                    }

                    QuestionType.SHORT_ANSWER,
                    QuestionType.ESSAY,
                    QuestionType.CASE_ANALYSIS,
                    -> {
                        AppMultilineTextField(
                            value = referenceAnswer,
                            onValueChange = { referenceAnswer = it },
                            label = "参考答案",
                            placeholder = "写下参考答案；主观题只用于对照自评，不会自动判分",
                            minLines = 3,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(AppTheme.space.lg))
        StaggeredIn(index = 3, modifier = Modifier.fillMaxWidth()) {
            AppCard(modifier = Modifier.fillMaxWidth()) {
                AppMultilineTextField(
                    value = explanation,
                    onValueChange = { explanation = it },
                    label = if (type.isSelfRubric) "评分要点（可选）" else "解析（可选）",
                    placeholder = if (type.isSelfRubric) {
                        "评分点/采分点，便于自评时对照"
                    } else {
                        "解释正确答案的思路"
                    },
                    minLines = 2,
                )
            }
        }

        Spacer(Modifier.height(AppTheme.space.xl))
        StaggeredIn(index = 4, modifier = Modifier.fillMaxWidth()) {
            AppButton(
                text = "保存题目",
                enabled = canSave,
                onClick = {
                    val saveOptions = when (type) {
                        QuestionType.SINGLE, QuestionType.MULTIPLE -> options
                        QuestionType.TRUE_FALSE -> listOf("正确", "错误")
                        else -> emptyList()
                    }
                    val saveIndices = when (type) {
                        QuestionType.SINGLE, QuestionType.MULTIPLE, QuestionType.TRUE_FALSE -> answerIndices
                        else -> emptyList()
                    }
                    val saveTexts = when (type) {
                        QuestionType.FILL, QuestionType.CALCULATION -> acceptedAnswers
                        QuestionType.SHORT_ANSWER,
                        QuestionType.ESSAY,
                        QuestionType.CASE_ANALYSIS,
                        -> listOf(referenceAnswer)
                        else -> emptyList()
                    }
                    viewModel.saveQuestion(
                        type = type,
                        stage = stage,
                        subject = subject,
                        stem = stem,
                        options = saveOptions,
                        answerIndices = saveIndices,
                        acceptedAnswers = saveTexts,
                        explanation = explanation,
                    ) {
                        onBack()
                    }
                },
            )
        }
        Spacer(Modifier.height(AppTheme.space.xl))
    }
}

@Composable
private fun FormChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    Box(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .clip(RoundedCornerShape(50))
            .background(if (selected) colors.accentSoft else colors.background)
            .border(
                width = 1.dp,
                color = if (selected) colors.accent else colors.divider,
                shape = RoundedCornerShape(50),
            )
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .padding(horizontal = AppTheme.space.md, vertical = AppTheme.space.sm),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = texts.aux.copy(
                color = if (selected) colors.accentInk else colors.primaryText,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            ),
        )
    }
}

@Composable
private fun AnswerChip(
    label: String,
    selected: Boolean,
    role: Role,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    Box(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .clip(RoundedCornerShape(AppTheme.radius.md))
            .background(if (selected) colors.successSoft else colors.background)
            .border(
                width = 1.dp,
                color = if (selected) colors.success else colors.divider,
                shape = RoundedCornerShape(AppTheme.radius.md),
            )
            .selectable(
                selected = selected,
                role = role,
                onClick = onClick,
            )
            .padding(vertical = AppTheme.space.sm),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = texts.aux.copy(
                color = if (selected) colors.successInk else colors.primaryText,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            ),
        )
    }
}
