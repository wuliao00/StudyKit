package com.studykit.ui.study

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
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.studykit.data.entity.Question
import com.studykit.ui.components.AppButton
import com.studykit.ui.components.AppCard
import com.studykit.ui.components.QuizOptionTile
import com.studykit.ui.theme.AppTheme

/**
 * 模考成绩页（v2.7 计划 B Task 13）：交卷之后才逐题摊开——延迟反馈的另一半。
 *
 * 每题走练习模式同一套 [QuizFeedback] 三层（任务级判词 + 过程级 + 自我调节级）与完整解析，
 * 判对判错到这一屏才第一次出现。答错项的批量入错题本在 `StudyViewModel.submitMockExam` 里做
 * （priority 走 [com.studykit.ui.mistake.MistakeIntake]，模考无信心采集 → conf=null → 普通档），
 * 这一屏只负责呈现，不重复入库。
 */
@Composable
fun ExamResultScreen(
    viewModel: StudyViewModel,
    onBack: () -> Unit,
) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    val state by viewModel.mockExam.collectAsStateWithLifecycle()

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
            Text(text = "模考成绩", style = texts.pageTitle)
        }

        ExamResultBody(
            questions = state.questions,
            results = state.results,
            onBack = onBack,
        )
    }
}

/**
 * 成绩内容：顶部一张小结卡（正确数 + 百分比）+ 逐题走 [QuizFeedback] 三层。
 *
 * 抽成只吃 [Question] 列表与 [MockExamState.Answer] 映射的内部 Composable，
 * 是为了让 [MockExamRenderTest] 能在没有 Room 的 JVM 侧把"判词出现了"这件事真组合出来验一次。
 */
@Composable
internal fun ExamResultBody(
    questions: List<Question>,
    results: Map<Long, MockExamState.Answer>,
    onBack: () -> Unit,
) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    val total = questions.size
    val correctCount = questions.count { results[it.id]?.correct == true }
    val percent = if (total == 0) 0 else correctCount * 100 / total

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(AppTheme.space.md))
        AppCard(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "本卷完成",
                style = texts.cardTitle,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(AppTheme.space.sm))
            Text(
                text = "$percent%",
                style = texts.statValue,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
            Text(
                text = "答对 $correctCount / $total 题",
                style = texts.caption,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(AppTheme.space.sm))
        Text(
            text = "答错的题已自动收入错题本，可以稍后按遗忘曲线重做。",
            style = texts.aux,
            color = colors.secondaryText,
        )
        Spacer(Modifier.height(AppTheme.space.md))

        questions.forEachIndexed { position, question ->
            ExamResultQuestionCard(
                question = question,
                position = position,
                answer = results[question.id],
            )
            Spacer(Modifier.height(AppTheme.space.md))
        }

        AppButton(text = "返回", onClick = onBack)
        Spacer(Modifier.height(AppTheme.space.lg))
    }
}

/**
 * 单题成绩卡：题面 + 选项砖块（此刻才允许点亮正确 / 错误）+ [QuizFeedback] 三层 + 完整解析。
 *
 * 判分复用练习模式同一批纯函数（[buildFeedback] / [shouldRevealFullExplanation] / [quizOptionState]），
 * 两模式的反馈文案与分档规则只有一套实现。未作答（[MockExamState.Answer.selected] = null）时，
 * `graded` 传 -1：正确项照常点亮、但不标"你选的"（没选就没有错选），解析照常摊开。
 */
@Composable
private fun ExamResultQuestionCard(
    question: Question,
    position: Int,
    answer: MockExamState.Answer?,
) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    val options = parseOptions(question.optionsJson)
    val selected = answer?.selected
    // 判分以交卷时算好的 answer.correct 为准；缺省（理论到不了）回退到"选了且命中"
    val correct = answer?.correct ?: (selected != null && selected == question.answerIndex)

    // 三层反馈：模考没有逐题提示，hintsUsed 恒 0
    val feedback = buildFeedback(
        question = question,
        selected = selected,
        options = options,
        hintsUsed = 0,
    )

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = question.subject,
                style = texts.caption.copy(color = colors.accentInk, fontWeight = FontWeight.Medium),
            )
            Text(
                text = if (selected == null) "第 ${position + 1} 题 · 未作答" else "第 ${position + 1} 题",
                style = texts.caption.copy(color = colors.secondaryText),
            )
        }
        Spacer(Modifier.height(AppTheme.space.sm))
        AppCard(modifier = Modifier.fillMaxWidth()) {
            Text(text = question.stem, style = texts.body)
        }

        Spacer(Modifier.height(AppTheme.space.sm))
        key(question.id) {
            options.forEachIndexed { index, option ->
                QuizOptionTile(
                    optionText = option,
                    index = index,
                    // 交卷之后 graded 才非 null：正确项点亮，答错时"你选的"也点亮（未作答传 -1 → 只亮正确项）
                    state = quizOptionState(
                        index = index,
                        answerIndex = question.answerIndex,
                        graded = selected ?: -1,
                        pending = -1,
                    ),
                    enabled = false,
                    onClick = {},
                )
                Spacer(Modifier.height(AppTheme.space.sm))
            }
        }

        AppCard(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = feedback.verdict,
                style = texts.cardTitle.copy(
                    color = if (correct) colors.successInk else colors.warningInk,
                ),
            )
            Spacer(Modifier.height(AppTheme.space.sm))
            Text(text = feedback.process, style = texts.aux)
            Spacer(Modifier.height(AppTheme.space.sm))
            Text(text = feedback.selfReg, style = texts.aux)
            if (shouldRevealFullExplanation(hintsUsed = 0, correct = correct) &&
                feedback.explanation.isNotBlank()
            ) {
                Spacer(Modifier.height(AppTheme.space.sm))
                Text(text = "完整解析：${feedback.explanation}", style = texts.aux)
            }
            Spacer(Modifier.height(AppTheme.space.sm))
            Text(
                text = FEEDBACK_SCOPE_NOTE,
                style = texts.caption.copy(color = colors.secondaryText),
            )
        }
    }
}
