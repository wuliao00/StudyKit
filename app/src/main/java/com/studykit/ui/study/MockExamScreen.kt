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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.studykit.data.entity.Question
import com.studykit.ui.components.AppButton
import com.studykit.ui.components.AppCard
import com.studykit.ui.components.ConfirmDialog
import com.studykit.ui.components.EmptyState
import com.studykit.ui.components.QuizOptionTile
import com.studykit.ui.theme.AppTheme
import kotlinx.coroutines.delay

/**
 * 模考页（v2.7 计划 B Task 13；v3.0-B T11 起带持久化与计时）：选学科 → 一次性作答全部题
 * （交卷前没有任何对错反馈）→ 交卷。
 *
 * 与 [QuizScreen] 的练习模式**完全分叉**：这里不渲染信心条、不给"给点提示"、答完一题也不出
 * 三层反馈——模考要模拟真实考场，边答边摊答案就测不出真实水平（spec「延迟反馈」承诺，由
 * [MockExamRenderTest] 机检）。判分逻辑收在纯状态机 [MockExamState]，对错只在交卷后算一次，
 * 交卷即跳 [ExamResultScreen]。
 *
 * 题面与选项砖块刻意复用练习模式同一批可组合件（[AppCard] 题面 + [QuizOptionTile] 选项 +
 * [quizOptionState] 映射），保证两模式视觉一致；差别只在传给砖块的状态：模考永远给
 * `Selected/Idle`（`graded=null`），判对判错压根不进这一屏。
 *
 * ## v3.0-B T11 改了两件用户能看见的事
 * 1. **返回不再等于作废**（R5）：顶栏箭头现在只做 [StudyViewModel.pauseMockExam]，卷留在库里，
 *    下次进来续上（进程被死也续得上）。作废整卷另有出口 —— 顶栏与暂停卡上的「丢弃本卷」，
 *    且必须过一道确认对话框。这条要进 CHANGELOG。
 * 2. **限时卷有表，暂停有停表**：`examLimitMinutes > 0` 时进度行右侧出现「剩余 mm:ss」，
 *    到 0 由 [StudyViewModel.onMockTick] 自动交卷并在成绩页标注「时间到，已自动交卷」（R3）；
 *    `paused` 时那一格换成「已暂停」、砖块冻结、**交卷按钮不在树里**（暂停中还能交卷是个真事故）。
 *
 * 计时回路的键是 `sessionId / paused / submitted`（R10）：任一变化都会**取消**上一圈协程，
 * 于是暂停、交卷、换一卷都自动停表，不需要谁记得去关它。
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
    var showDiscard by remember { mutableStateOf(false) }

    // 每秒喂一次假时钟友好入口：`nowTs` 由页面给，纯层不碰 System.currentTimeMillis。
    // 圈里再读一次当前状态并自杀式退出：兜住「按下交卷后、那一帧还没重组」的窗口，
    // 免得自动交卷在已经交过的卷上再交一次。
    LaunchedEffect(state.sessionId, state.paused, state.submitted) {
        while (true) {
            val ticking = viewModel.mockExam.value
            if (!ticking.started || ticking.paused || ticking.submitted) break
            viewModel.onMockTick(System.currentTimeMillis())
            delay(1_000L)
        }
    }

    // 自动交卷也得把人带到成绩页：那一跳以前只写在「交卷」按钮的 lambda 里，
    // 而"钟替他按的那一次"没有 lambda 可调。手动交卷仍走按钮那条 lambda（autoSubmitted=false，
    // 这里不重复导航）。
    LaunchedEffect(state.submitted) {
        if (state.submitted && state.autoSubmitted) onSubmitted()
    }

    val remaining = remainingLabel(state.nowTs, state.deadlineTs)
    val sessionId = state.sessionId

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = AppTheme.space.pageH),
    ) {
        Spacer(Modifier.height(AppTheme.space.sm))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = {
                    // 语义改了（R5）：以前这里是 resetMockExam()——返回即抹掉整卷。
                    // 现在只做"暂停并留住"，续得上；作废交给旁边的「丢弃本卷」（带确认）。
                    viewModel.pauseMockExam()
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
            Text(
                text = if (state.examName.isBlank()) "模考" else "模考 · ${state.examName}",
                style = texts.pageTitle,
                modifier = Modifier.weight(1f),
            )
            // 作废整卷的另一枚出口（卷还在作答中也给）：与返回分开放，且必须确认
            if (state.started && sessionId != null) {
                TextButton(onClick = { showDiscard = true }) {
                    Text(text = "丢弃本卷", style = texts.caption, color = colors.warningInk)
                }
            }
        }

        val current = state.current
        when {
            state.started && current != null -> {
                // 暂停时先在卷面之上摆一张"这卷还没交"的卡（续 / 弃两个出口），卷面本身冻结可翻题
                if (state.paused) {
                    MockExamResumePrompt(
                        state = state,
                        remainingLabel = remaining,
                        onResume = { viewModel.resumeMockExam() },
                        onDiscard = { showDiscard = true },
                    )
                }
                MockExamBody(
                    question = current,
                    selectedIndex = state.answers[current.id],
                    answeredCount = state.answeredCount,
                    position = state.index,
                    total = state.total,
                    remainingLabel = remaining,
                    paused = state.paused,
                    onAnswer = { index -> viewModel.answerMockExam(current.id, index) },
                    onPrev = { viewModel.gotoMockExam(state.index - 1) },
                    onNext = { viewModel.gotoMockExam(state.index + 1) },
                    onSubmit = {
                        viewModel.submitMockExam()
                        onSubmitted()
                    },
                    onPause = { viewModel.pauseMockExam() },
                    onResume = { viewModel.resumeMockExam() },
                )
            }

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

    if (showDiscard) {
        ConfirmDialog(
            title = "丢弃这一卷？",
            body = "这一卷已答的内容会一并删掉，找不回来。下次进来是重新组一卷。",
            confirmLabel = "丢弃",
            danger = true,
            onConfirm = {
                showDiscard = false
                sessionId?.let { viewModel.abandonMockSession(it) }
            },
            onDismiss = { showDiscard = false },
        )
    }
}

/**
 * 学科选择：从 questions 表 distinct subject 渲染入口卡片。
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
 * 「这卷还没交」那张卡（v3.0-B T11，R5/R6）：续卷与作废的两个出口。
 *
 * 为什么只有这张卡是可测的薄组合子：整屏要 [StudyViewModel]（背后是 Room），JVM 侧起不来，
 * 而"恢复时只给 继续 / 丢弃 两个动作、不给第三个（交卷）"这件事恰好长在卡片自己身上。
 * 口径同 [MockExamBody] / [ExamResultBody]（[MockExamRenderTest]）。
 *
 * 参数收的是 [MockExamUiState] 而不是 `MockSession`：那一行住在 VM 里，页面从来拿不到它，
 * 而这张卡要的五个读数（卷名、学科、已答、总量、剩余）状态里全有。
 */
@Composable
internal fun MockExamResumePrompt(
    state: MockExamUiState,
    remainingLabel: String?,
    onResume: () -> Unit,
    onDiscard: () -> Unit,
) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Text(text = "上次那卷还没交", style = texts.cardTitle)
        Spacer(Modifier.height(AppTheme.space.xs))
        // 卷名与学科两个读数分开两行写：合成一行就把"这卷是哪场考试的"和"答了几题"绑成一条断言了
        Text(
            text = if (state.examName.isBlank()) state.subject else "${state.examName} · ${state.subject}",
            style = texts.caption,
            color = colors.accentInk,
        )
        Text(
            text = "已答 ${state.answeredCount} / ${state.total} 题",
            style = texts.caption,
            color = colors.secondaryText,
        )
        Spacer(Modifier.height(AppTheme.space.xs))
        Text(
            text = if (remainingLabel != null) "计时已停下，剩余 $remainingLabel" else "计时已停下，这一卷不限时",
            style = texts.aux,
            color = colors.warningInk,
        )
        Spacer(Modifier.height(AppTheme.space.sm))
        AppButton(text = "继续作答", onClick = onResume)
        Spacer(Modifier.height(AppTheme.space.sm))
        AppButton(text = "丢弃本卷", secondary = true, onClick = onDiscard)
    }
}

/**
 * 考卷本体（一屏一题）：题面 + 选项砖块 + 上一题 / 下一题 / 暂停（或继续）/ 交卷。
 *
 * 抽成只吃纯数据的内部 Composable，是为了让 [MockExamRenderTest] 能在没有 Room 的 JVM 侧
 * 把它真组合出来，钉死"作答后不出现判词"这条承诺（口径同只摆组件本体的 SwipeRatingCard 宿主）。
 *
 * 传给 [QuizOptionTile] 的状态恒走 [quizOptionState] 的 `graded = null` 分支——交卷前
 * 永远只可能拿到 `Selected/Idle`，正确项压根不会被点亮，也就不会把答案泄露出去。
 *
 * [remainingLabel] 是纯函数 [remainingLabel](`nowTs`, `deadlineTs`) 的读数，`null` = 不限时，
 * 那一格就**不画**（画个「剩余 00:00」会被读成"时间到了"，而这一卷压根没限时）。
 * [paused] 时卷面冻结（砖块 `enabled = false`、可翻题不能作答），那一格换成「已暂停」，
 * 底部「交卷」整个换成「继续」—— 暂停中还能交卷是个真事故。
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
    remainingLabel: String? = null,
    paused: Boolean = false,
    onPause: () -> Unit = {},
    onResume: () -> Unit = {},
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
            // 计时那一格：暂停优先于剩余（两个时间读数同时出现，用户不知道看哪个）
            if (paused) {
                Text(
                    text = "已暂停",
                    style = texts.caption.copy(color = colors.warningInk, fontWeight = FontWeight.Medium),
                )
            } else if (remainingLabel != null) {
                Text(
                    text = "剩余 $remainingLabel",
                    style = texts.caption.copy(color = colors.secondaryText),
                )
            }
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
                    enabled = !paused,          // 暂停即冻结卷面：不能作答，但能翻题看自己答到哪
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
        if (paused) {
            // 暂停中「交卷」整个不在树里：唯一出口是继续（回到计时）与卡片上的丢弃
            AppButton(text = "继续", onClick = onResume)
        } else {
            AppButton(text = "暂停", secondary = true, onClick = onPause)
            Spacer(Modifier.height(AppTheme.space.sm))
            // 交卷：整卷唯一一次判分的入口，点了才跳结果页
            AppButton(text = "交卷", onClick = onSubmit)
        }
        Spacer(Modifier.height(AppTheme.space.lg))
    }
}
