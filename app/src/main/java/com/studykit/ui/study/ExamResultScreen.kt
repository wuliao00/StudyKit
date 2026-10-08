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
import com.studykit.ui.components.QuizOptionState
import com.studykit.ui.components.QuizOptionTile
import com.studykit.ui.components.SectionHeader
import com.studykit.ui.theme.AppTheme

/**
 * 模考成绩页（v2.7 计划 B Task 13；v3.0-B T12 起分摊"判过的"与"没答的"）：
 * 交卷之后才逐题摊开——延迟反馈的另一半。
 *
 * 每题走练习模式同一套 [QuizFeedback] 三层（任务级判词 + 过程级 + 自我调节级）与完整解析，
 * 判对判错到这一屏才第一次出现。答错项的批量入错题本在 `StudyViewModel.submitMockExam` 里做
 * （priority 走 [com.studykit.ui.mistake.MistakeIntake]，模考无信心采集 → conf=null → 普通档），
 * 这一屏只负责呈现，不重复入库。
 *
 * 未作答的那些题**不在逐题判词那一列里**，而是单独一栏「待批改」：它们没有判词（没人给出过选择），
 * 把它们排进判分那一列就会得到一句"回答错误，正确项是 X"——那是把空缺报成失败，还顺手把答案摊给
 * 一个还没做过这题的人看（[ExamResultBody] 的 KDoc）。
 */
@Composable
fun ExamResultScreen(
    viewModel: StudyViewModel,
    onBack: () -> Unit,
    onGoCapture: (String) -> Unit = {},
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
            // id 翻回题目再传（ExamResultBody 只吃 Question，不看 VM）
            pendingGrading = state.toGrade.mapNotNull { id -> state.questions.firstOrNull { it.id == id } },
            autoSubmitted = state.autoSubmitted,
            onGoCapture = onGoCapture,
            onBack = onBack,
        )
    }
}

/**
 * 成绩内容：顶部一张小结卡（**以判过的题为分母**的正确率 + 未答题数）+ 逐题走 [QuizFeedback] 三层，
 * 最后单独一栏列出没答的那些题。
 *
 * 抽成只吃 [Question] 列表与 [MockExamState.Answer] 映射的内部 Composable，
 * 是为了让 [MockExamRenderTest] 能在没有 Room 的 JVM 侧把"判词出现了 / 未答的没被算成错"
 * 这两件事真组合出来验一次。
 *
 * 三个可见产出（v3.0-B T12）：
 * 1. [autoSubmitted] → 「时间到，已自动交卷」（R3：钟替他按的交卷与自己按的，在分数上不该看起来一样）；
 * 2. [pendingGrading] 非空 → 「待批改 N 题」那一栏，每题只出题干与**四块全中性**的选项，
 *    **不出正确答案**：这些题还没判，出答案就是提前把结果摊给一个随时可能回去补答的人；
 * 3. 有答错的题 → 「错了 N 道，去建卡？」+ [onGoCapture]（带的学科取答错那些题的学科，
 *    拍照录入页据此预选，用户少点一次）。N=0 时整枚按钮不出现 —— 没有错题就没有建卡要做，
 *    摆一枚点了什么也不会发生的按钮是骗点击。
 *
 * 分数那一行写清分母（`判了 N 题 · 未答 M 题`）：屏幕上印一个把未作答算成答错的百分比，
 * 是在骗用户他的记忆水平有多差（R2）。
 */
@Composable
internal fun ExamResultBody(
    questions: List<Question>,
    results: Map<Long, MockExamState.Answer>,
    pendingGrading: List<Question> = emptyList(),
    autoSubmitted: Boolean = false,
    onGoCapture: (String) -> Unit = {},
    onBack: () -> Unit,
) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    val pendingIds = pendingGrading.map { it.id }.toSet()
    // 判过的那些题才排版给判词；未答的让给下面那一栏
    val graded = questions.filterNot { it.id in pendingIds }
    val gradedCount = results.size
    val correctCount = results.values.count { it.correct }
    val wrongCount = gradedCount - correctCount
    val percent = if (gradedCount == 0) 0 else correctCount * 100 / gradedCount

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
            if (autoSubmitted) {
                Spacer(Modifier.height(AppTheme.space.xs))
                Text(
                    text = "时间到，已自动交卷",
                    style = texts.caption,
                    color = colors.warningInk,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(AppTheme.space.sm))
            Text(
                text = "$percent%",
                style = texts.statValue,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
            Text(
                text = "答对 $correctCount / $gradedCount 题",
                style = texts.caption,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
            // 分母说清楚：这一行的 N 是"判过的题"，不是整卷题数
            Text(
                text = "判了 $gradedCount 题 · 未答 ${pendingGrading.size} 题",
                style = texts.caption.copy(color = colors.secondaryText),
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
        if (wrongCount > 0) {
            Spacer(Modifier.height(AppTheme.space.sm))
            Text(
                text = "错了 $wrongCount 道，去建卡？（拍照 → 预览 → 确认）",
                style = texts.aux,
                color = colors.accentInk,
            )
            Spacer(Modifier.height(AppTheme.space.sm))
            AppButton(
                text = "去拍这几道",
                secondary = true,
                onClick = {
                    // 带答错那些题的学科过去，录入页据此预选学科（一张卷同一科，取第一条即可）
                    onGoCapture(graded.firstOrNull { results[it.id]?.correct == false }?.subject.orEmpty())
                },
            )
        }
        Spacer(Modifier.height(AppTheme.space.md))

        graded.forEachIndexed { position, question ->
            ExamResultQuestionCard(
                question = question,
                position = position,
                answer = results[question.id],
            )
            Spacer(Modifier.height(AppTheme.space.md))
        }

        if (pendingGrading.isNotEmpty()) {
            SectionHeader("待批改 ${pendingGrading.size} 题")
            Spacer(Modifier.height(AppTheme.space.sm))
            Text(
                text = "这些题交卷时是空的，判不了",
                style = texts.caption,
                color = colors.secondaryText,
            )
            Spacer(Modifier.height(AppTheme.space.md))
            pendingGrading.forEach { question ->
                ExamPendingQuestionCard(
                    question = question,
                    position = questions.indexOf(question),
                )
                Spacer(Modifier.height(AppTheme.space.md))
            }
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

/**
 * 待批改（交卷时是空的）那一栏的单卡：题面 + 四块**全中性**的选项，不出判词、也不点正确项。
 *
 * 与 [ExamResultQuestionCard] 唯一的分别就在这一句：那些题没有判词，因为没人给出过选择。
 * 把 `graded` 传 -1 走下去的话，正确项会被点亮 —— 那是把答案摊给一份还没做完的卷，
 * 而「待批改」这个队列存在的意义正是"它们还没被判"（R2）。选项砖块因此直接给
 * [QuizOptionState.Idle] + `enabled = false`，压根不经过 [quizOptionState] 的判定分支。
 */
@Composable
private fun ExamPendingQuestionCard(
    question: Question,
    position: Int,
) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    val options = parseOptions(question.optionsJson)

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
                text = "第 ${position + 1} 题 · 未作答",
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
                    state = QuizOptionState.Idle,
                    enabled = false,
                    onClick = {},
                )
                Spacer(Modifier.height(AppTheme.space.sm))
            }
        }
    }
}
