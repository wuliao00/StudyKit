package com.studykit.ui.study

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.studykit.data.entity.Question
import com.studykit.ui.components.AppButton
import com.studykit.ui.components.AppCard
import com.studykit.ui.components.EmptyState
import com.studykit.ui.components.QuizOptionTile
import com.studykit.ui.theme.AppTheme

/**
 * 模考页（v2.7 计划 B Task 13）：选学科 → 一次性作答全部题（交卷前没有任何对错反馈）→ 交卷。
 *
 * 与 [QuizScreen] 的练习模式**完全分叉**：这里不渲染信心条、不给"给点提示"、答完一题也不出
 * 三层反馈——模考要模拟真实考场，边答边摊答案就测不出真实水平（spec「延迟反馈」承诺，由
 * [MockExamRenderTest] 机检）。判分逻辑收在纯状态机 [MockExamState]，对错只在交卷后算一次，
 * 交卷即跳 [ExamResultScreen]。
 *
 * 题面与选项砖块刻意复用练习模式同一批可组合件（[AppCard] 题面 + [QuizOptionTile] 选项 +
 * [quizOptionState] 映射），保证两模式视觉一致；差别只在传给砖块的状态：模考永远给
 * `Selected/Idle`（`graded=null`），判对判错压根不进这一屏。
 */
@Composable
fun MockExamScreen(
    viewModel: StudyViewModel,
    onBack: () -> Unit,
    onSubmitted: () -> Unit,
) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    val state by viewModel.mockExam.collectAsStateWithLifecycle()
    val subjects by viewModel.subjects.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = AppTheme.space.pageH),
    ) {
        Spacer(Modifier.height(AppTheme.space.sm))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = {
                    viewModel.resetMockExam()
                    onBack()
                },
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = colors.accentInk,
                )
            }
            Spacer(Modifier.width(AppTheme.space.xs))
            Text(text = "模考", style = texts.pageTitle)
        }

        val current = state.current
        when {
            state.started && current != null -> MockExamBody(
                question = current,
                selectedIndex = state.answers[current.id],
                answeredCount = state.answeredCount,
                position = state.index,
                total = state.total,
                onAnswer = { index -> viewModel.answerMockExam(current.id, index) },
                onPrev = { viewModel.gotoMockExam(state.index - 1) },
                onNext = { viewModel.gotoMockExam(state.index + 1) },
                onSubmit = {
                    viewModel.submitMockExam()
                    onSubmitted()
                },
            )

            state.started -> {
                // started 但取不到当前题（越界/已交卷后又绕回来）：交卷跳走前一般到不了这里，
                // 兜一句避免空白屏。
                EmptyState(
                    title = "本卷已交",
                    caption = "回到模考入口可以再来一卷",
                    icon = Icons.Outlined.CheckCircle,
                )
            }

            else -> ExamSubjectPicker(
                subjects = subjects,
                onPick = { viewModel.startMockExam(it) },
            )
        }
    }
}

/**
 * 模考学科选择：从 questions 表 distinct subject 渲染入口卡片。
 *
 * 刻意比 [com.studykit.ui.study.QuizScreen] 的学科选择更薄：没有"综合练习"、没有交错开关行——
 * 一张模拟卷按学科出全部题，交卷前不打扰，才是考场。取数仍走现有 `getBySubject`（不新增仓库方法、
 * 不碰 Room schema）。
 */
@Composable
private fun ExamSubjectPicker(
    subjects: List<String>,
    onPick: (String) -> Unit,
) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(AppTheme.space.md))
        Text(text = "选一个学科，组一卷模拟卷", style = texts.caption)
        Spacer(Modifier.height(AppTheme.space.sm))
        Text(
            text = "模考按真实考场来：一次作答、交卷前不给对错，交卷后再逐题看解析。",
            style = texts.aux,
        )
        Spacer(Modifier.height(AppTheme.space.md))
        if (subjects.isEmpty()) {
            Spacer(Modifier.height(AppTheme.space.xl * 2))
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
                            style = texts.cardTitle,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "开始模考",
                            style = texts.caption.copy(color = colors.accentInk),
                        )
                    }
                }
                Spacer(Modifier.height(AppTheme.space.sm))
            }
        }
        Spacer(Modifier.height(AppTheme.space.lg))
    }
}

/**
 * 考卷本体（一屏一题）：题面 + 选项砖块 + 上一题 / 下一题 / 交卷。
 *
 * 抽成只吃纯数据的内部 Composable，是为了让 [MockExamRenderTest] 能在没有 Room 的 JVM 侧
 * 把它真组合出来，钉死"作答后不出现判词"这条承诺（口径同只摆组件本体的 SwipeRatingCard 宿主）。
 *
 * 传给 [QuizOptionTile] 的状态恒走 [quizOptionState] 的 `graded = null` 分支——交卷前
 * 永远只可能拿到 `Selected/Idle`，正确项压根不会被点亮，也就不会把答案泄露出去。
 */
@Composable
internal fun MockExamBody(
    question: Question,
    selectedIndex: Int?,
    answeredCount: Int,
    position: Int,
    total: Int,
    onAnswer: (Int) -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onSubmit: () -> Unit,
) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    val options = parseOptions(question.optionsJson)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(AppTheme.space.md))
        // 顶栏进度：这是交卷前唯一的"元信息"，不含任何对错
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "第 ${position + 1} / $total 题",
                style = texts.caption,
            )
            Text(
                text = "已答 $answeredCount 题",
                style = texts.caption.copy(color = colors.secondaryText),
            )
        }
        Spacer(Modifier.height(AppTheme.space.sm))

        Text(
            text = question.subject,
            style = texts.caption.copy(color = colors.accentInk, fontWeight = FontWeight.Medium),
        )
        Spacer(Modifier.height(AppTheme.space.sm))
        AppCard(modifier = Modifier.fillMaxWidth()) {
            Text(text = question.stem, style = texts.body)
        }

        Spacer(Modifier.height(AppTheme.space.md))
        // 换题即整列重建，砖块不带上一题的选择残留
        key(question.id) {
            options.forEachIndexed { index, option ->
                QuizOptionTile(
                    optionText = option,
                    index = index,
                    state = quizOptionState(
                        index = index,
                        answerIndex = question.answerIndex,
                        graded = null,          // 交卷前不给判定：正确项永不点亮
                        pending = selectedIndex ?: -1,
                    ),
                    enabled = true,
                    onClick = { onAnswer(index) },
                )
                Spacer(Modifier.height(AppTheme.space.sm))
            }
        }

        Spacer(Modifier.height(AppTheme.space.md))
        Row(horizontalArrangement = Arrangement.spacedBy(AppTheme.space.sm)) {
            AppButton(
                text = "上一题",
                secondary = true,
                enabled = position > 0,
                onClick = onPrev,
                modifier = Modifier.weight(1f),
            )
            AppButton(
                text = if (position < total - 1) "下一题" else "已是最后一题",
                secondary = true,
                enabled = position < total - 1,
                onClick = onNext,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(AppTheme.space.sm))
        // 交卷：整卷唯一一次判分的入口，点了才跳结果页
        AppButton(text = "交卷", onClick = onSubmit)
        Spacer(Modifier.height(AppTheme.space.lg))
    }
}
