package com.studykit.ui.study

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.studykit.data.entity.Question
import com.studykit.ui.components.AppButton
import com.studykit.ui.components.AppCard
import com.studykit.ui.components.EmptyState
import com.studykit.ui.theme.DesignTokens

/**
 * 题库练习页：学科选择 → 逐题作答（即时判定 + 解析）→ 正确率结果页。
 */
@Composable
fun QuizScreen(
    viewModel: StudyViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.quiz.collectAsStateWithLifecycle()
    val subjects by viewModel.subjects.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = DesignTokens.PageHorizontalPadding),
    ) {
        Spacer(Modifier.height(DesignTokens.SpacingSm))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = {
                    viewModel.resetQuiz()
                    onBack()
                },
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = DesignTokens.Accent,
                )
            }
            Spacer(Modifier.width(DesignTokens.SpacingXs))
            Text(text = "题库练习", style = DesignTokens.PageTitle)
            Spacer(Modifier.weight(1f))
            if (state.started && !state.finished) {
                Text(
                    text = "第 ${state.index + 1} / ${state.total} 题",
                    style = DesignTokens.Caption,
                )
            }
        }

        when {
            state.finished -> QuizResult(
                correctCount = state.correctCount,
                total = state.total,
                percent = state.accuracyPercent,
                onRestart = { viewModel.resetQuiz() },
                onBack = onBack,
            )

            state.current != null -> QuestionView(
                question = state.current!!,
                selected = state.selected,
                onSelect = { viewModel.selectOption(it) },
                onNext = { viewModel.nextQuestion() },
                isLast = state.index == state.total - 1,
            )

            else -> SubjectPicker(
                subjects = subjects,
                onPick = { viewModel.startQuiz(it) },
            )
        }
    }
}

/** 学科选择：从 questions 表 distinct subject 渲染入口卡片 */
@Composable
private fun SubjectPicker(
    subjects: List<String>,
    onPick: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(DesignTokens.SpacingMd))
        Text(text = "选择学科，开始一轮练习", style = DesignTokens.Caption)
        Spacer(Modifier.height(DesignTokens.SpacingMd))
        if (subjects.isEmpty()) {
            Spacer(Modifier.height(DesignTokens.SpacingXl * 2))
            EmptyState(
                title = "题库还是空的",
                caption = "回到学习首页，在「题库练习」卡片上点击 + 录入第一道题",
                icon = Icons.Outlined.CheckCircle,
            )
        } else {
            subjects.forEach { subject ->
                AppCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(subject) },
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = subject,
                            style = DesignTokens.CardTitle,
                            modifier = Modifier.weight(1f),
                        )
                        Text(text = "开始练习", style = DesignTokens.Caption.copy(color = DesignTokens.Accent))
                    }
                }
                Spacer(Modifier.height(DesignTokens.SpacingSm))
            }
        }
        Spacer(Modifier.height(DesignTokens.SpacingLg))
    }
}

/** 逐题作答：题干 + 4 选项，点击即时判定，作答后展示解析与「下一题」 */
@Composable
private fun QuestionView(
    question: Question,
    selected: Int?,
    onSelect: (Int) -> Unit,
    onNext: () -> Unit,
    isLast: Boolean,
) {
    val options = parseOptions(question.optionsJson)
    val answered = selected != null

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(DesignTokens.SpacingMd))
        Text(
            text = question.subject,
            style = DesignTokens.Caption.copy(color = DesignTokens.Accent, fontWeight = FontWeight.Medium),
        )
        Spacer(Modifier.height(DesignTokens.SpacingSm))
        AppCard(modifier = Modifier.fillMaxWidth()) {
            Text(text = question.stem, style = DesignTokens.Body)
        }

        Spacer(Modifier.height(DesignTokens.SpacingMd))
        options.forEachIndexed { index, option ->
            OptionRow(
                letter = "${'A' + index}",
                text = option,
                isCorrect = index == question.answerIndex,
                isSelected = index == selected,
                answered = answered,
                onClick = { onSelect(index) },
            )
            Spacer(Modifier.height(DesignTokens.SpacingSm))
        }

        if (answered) {
            AppCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = if (selected == question.answerIndex) "回答正确" else "回答错误",
                    style = DesignTokens.CardTitle.copy(
                        color = if (selected == question.answerIndex) DesignTokens.Success else DesignTokens.Warning,
                    ),
                )
                Spacer(Modifier.height(DesignTokens.SpacingSm))
                Text(text = "解析：${question.explanation}", style = DesignTokens.Auxiliary)
            }
            Spacer(Modifier.height(DesignTokens.SpacingMd))
            AppButton(
                text = if (isLast) "查看结果" else "下一题",
                onClick = onNext,
            )
        }
        Spacer(Modifier.height(DesignTokens.SpacingLg))
    }
}

/** 选项行：作答前中性描边；作答后正确项=成功色、选中错项=警示色、其余淡出 */
@Composable
private fun OptionRow(
    letter: String,
    text: String,
    isCorrect: Boolean,
    isSelected: Boolean,
    answered: Boolean,
    onClick: () -> Unit,
) {
    val borderColor: Color
    val backgroundColor: Color
    val textColor: Color
    when {
        answered && isCorrect -> {
            borderColor = DesignTokens.Success
            backgroundColor = DesignTokens.Success.copy(alpha = 0.10f)
            textColor = DesignTokens.PrimaryText
        }

        answered && isSelected -> {
            borderColor = DesignTokens.Warning
            backgroundColor = DesignTokens.Warning.copy(alpha = 0.10f)
            textColor = DesignTokens.PrimaryText
        }

        answered -> {
            borderColor = DesignTokens.Divider
            backgroundColor = DesignTokens.Card
            textColor = DesignTokens.SecondaryText
        }

        else -> {
            borderColor = DesignTokens.Divider
            backgroundColor = DesignTokens.Card
            textColor = DesignTokens.PrimaryText
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(DesignTokens.CornerRadius))
            .background(backgroundColor)
            .border(
                width = if (answered && (isCorrect || isSelected)) 1.5.dp else 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(DesignTokens.CornerRadius),
            )
            .clickable(enabled = !answered, onClick = onClick)
            .padding(DesignTokens.CardPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = letter,
            style = DesignTokens.CardTitle.copy(
                color = if (answered && isCorrect) DesignTokens.Success
                else if (answered && isSelected) DesignTokens.Warning
                else textColor,
            ),
        )
        Spacer(Modifier.width(DesignTokens.SpacingMd))
        Text(
            text = text,
            style = DesignTokens.Auxiliary.copy(color = textColor),
        )
    }
}

/** 结果页：正确率百分比 + 答对题数 */
@Composable
private fun QuizResult(
    correctCount: Int,
    total: Int,
    percent: Int,
    onRestart: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        AppCard(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "本轮完成",
                style = DesignTokens.PageTitle,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(DesignTokens.SpacingLg))
            Text(
                text = "$percent%",
                style = DesignTokens.LargeTitle.copy(
                    color = if (percent >= 60) DesignTokens.Success else DesignTokens.Warning,
                ),
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(DesignTokens.SpacingXs))
            Text(
                text = "答对 $correctCount / $total 题",
                style = DesignTokens.Caption,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(DesignTokens.SpacingLg))
        AppButton(text = "返回", onClick = onBack)
        Spacer(Modifier.height(DesignTokens.SpacingMd))
        AppButton(text = "换个学科再来一轮", secondary = true, onClick = onRestart)
    }
}
