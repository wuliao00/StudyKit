package com.studykit.ui.study

import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.studykit.tips.StudyTips
import com.studykit.tips.TipEvent
import com.studykit.ui.motion.MotionSpec
import com.studykit.data.entity.Question
import com.studykit.ui.components.AppButton
import com.studykit.ui.components.AppCard
import com.studykit.ui.components.EmptyState
import com.studykit.ui.components.QuizOptionState
import com.studykit.ui.components.QuizOptionTile
import com.studykit.ui.components.RingGauge
import com.studykit.ui.components.TipCard
import com.studykit.ui.theme.AppTheme

/**
 * 题库练习页：学科选择 → 逐题作答（作答前可挤牙膏式要提示，作答后三层反馈）→ 正确率环形结果页。
 *
 * 颜色与文字样式统一取 `AppTheme`，间距/圆角取 `AppTheme.space` / `AppTheme.radius` 的 dp 常量。
 *
 * 一题的状态机（详见 [QuestionView] 与 [quizOptionState]）：
 * `（可选：要提示 ×1~3）→ 点击 → 本地锁定（Selected）→ DB 回写判定（Correct/Wrong + 分层反馈）→ 下一题`，
 * 每一跳都只有一个入口能推进，重复点击与连点「下一题」都被挡掉。
 *
 * v2.5 缺口补齐加的两件事都收在纯函数层（`QuestionOrdering.kt` / `QuizFeedback.kt`）：
 *  - **交错练习**：多学科轮次（[StudyViewModel.startMixedQuiz]）的题序在取数时已打散，
 *   这一页只负责按队列渲染；`AppTheme.settings.interleavingEnabled` 在这里**只被读来做展示与开关呈现**，
 *   打不打散的判定只有 `interleaveBySubject` 一处。
 *  - **提示与分层反馈**：三档提示逐条揭示、都不给最终答案；答后按任务级 / 过程级 / 自我调节级三层给出。
 */
@Composable
fun QuizScreen(
    viewModel: StudyViewModel,
    onBack: () -> Unit,
) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    val state by viewModel.quiz.collectAsStateWithLifecycle()
    val subjects by viewModel.subjects.collectAsStateWithLifecycle()
    // 与 CardStudyScreen 读 recallBeforeGrade 同一姿势：组合期读一枚布尔，喂给展示与开关
    val interleaving = AppTheme.settings.interleavingEnabled

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = AppTheme.space.pageH),
    ) {
        Spacer(Modifier.height(AppTheme.space.sm))
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
                    tint = colors.accentInk,
                )
            }
            Spacer(Modifier.width(AppTheme.space.xs))
            Text(text = "题库练习", style = texts.pageTitle)
            Spacer(Modifier.weight(1f))
            if (state.started && !state.finished) {
                Text(
                    text = "第 ${state.index + 1} / ${state.total} 题",
                    style = texts.caption,
                )
            }
        }

        val current = state.current
        when {
            state.finished -> QuizResult(
                correctCount = state.correctCount,
                total = state.total,
                percent = state.accuracyPercent,
                onRestart = { viewModel.resetQuiz() },
                onBack = onBack,
            )

            current != null -> QuestionView(
                question = current,
                selected = state.selected,
                onSelect = { viewModel.selectOption(it) },
                onNext = { viewModel.nextQuestion() },
                isLast = state.index == state.total - 1,
            )

            else -> SubjectPicker(
                subjects = subjects,
                interleaving = interleaving,
                onPick = { viewModel.startQuiz(it) },
                onPickMixed = { viewModel.startMixedQuiz() },
                onToggleInterleaving = { viewModel.setInterleavingEnabled(it) },
            )
        }
    }
}

/**
 * 学科选择：从 questions 表 distinct subject 渲染入口卡片。
 *
 * 两个新增件：
 *  - **综合练习**（多学科 ≥ 2 才出现）：走 [StudyViewModel.startMixedQuiz]，题序已按学科打散；
 *  - **交错练习开关**：`AppTheme.settings.interleavingEnabled` 的开关位。本应落在设置屏，
 *    但那一屏本轮不在改动范围内，所以先接在练习入口里；说明文案把「更难是正常的、
 *    且更难往往记得更牢」这一层写清楚，不做玄学承诺。
 */
@Composable
private fun SubjectPicker(
    subjects: List<String>,
    interleaving: Boolean,
    onPick: (String) -> Unit,
    onPickMixed: () -> Unit,
    onToggleInterleaving: (Boolean) -> Unit,
) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(AppTheme.space.md))
        Text(text = "选择学科，开始一轮练习", style = texts.caption)
        Spacer(Modifier.height(AppTheme.space.md))
        if (subjects.isEmpty()) {
            Spacer(Modifier.height(AppTheme.space.xl * 2))
            EmptyState(
                title = "题库还是空的",
                caption = "回到学习首页，在「题库练习」卡片上点击 + 录入第一道题",
                icon = Icons.Outlined.CheckCircle,
            )
        } else {
            if (subjects.size >= 2) {
                AppCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPickMixed() },
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = MIXED_SUBJECT_LABEL,
                            style = texts.cardTitle,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "开始混合练习",
                            style = texts.caption.copy(color = colors.accentInk),
                        )
                    }
                    Spacer(Modifier.height(AppTheme.space.xs))
                    Text(
                        text = if (interleaving) {
                            "把已录入的学科混成一轮，同科连排会被打散：混着练当下更容易卡壳，但卡壳本身就是在费力提取，这一类练习记起来往往更稳。"
                        } else {
                            "已关掉交错：多学科轮次也按取数原序出，同科的题会连着出现。"
                        },
                        style = texts.aux,
                    )
                }
                Spacer(Modifier.height(AppTheme.space.sm))
                // 交错开关：判定只有一处（`interleaveBySubject`），这里只是呈现与写回。
                // 开关行本应归设置屏，那边本轮有人在看，先不放过去。
                AppCard(modifier = Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = "交错练习", style = texts.cardTitle)
                            Spacer(Modifier.height(AppTheme.space.xs))
                            Text(
                                text = "觉得更难是正常的，且更难往往记得更牢 —— 这不是玄学：" +
                                    "研究里交错练习的帮助是中等程度的，也不是对所有材料都有效；" +
                                    "只有一个学科时不会硬打乱。不习惯可以在这里关掉。",
                                style = texts.aux,
                            )
                        }
                        Spacer(Modifier.width(AppTheme.space.sm))
                        Switch(
                            checked = interleaving,
                            onCheckedChange = onToggleInterleaving,
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = colors.accentInk,
                            ),
                        )
                    }
                }
                Spacer(Modifier.height(AppTheme.space.sm))
                // 交错那件事的科学证据已经有 [科学验证] 条目（TipId.INTERLEAVE），
                // 走现成的 BlockingStreak 事件取，不改 tips 包
                StudyTips.forEvent(
                    TipEvent.BlockingStreak(items = StudyTips.BLOCKING_STREAK_THRESHOLD, subjects = 1),
                )?.let { tip ->
                    TipCard(tip = tip)
                    Spacer(Modifier.height(AppTheme.space.sm))
                }
            }
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
                        // accent 作文字色在白底只有 3.04:1，按 T1 裁定走 accentInk
                        Text(
                            text = "开始练习",
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

// ── 选项反馈态映射：纯函数，零 Compose 依赖，可在 JVM 单测里直取（同 decideSwipe 的做法）──

/**
 * 把「ViewModel 快照 + 本地乐观锁定」映射成 [QuizOptionState]。
 *
 * 优先级自上而下短路：
 *  1. 已判定（[graded] = `QuizUiState.selected`，非空即 DB 已回写、本题锁定）：
 *     正确项 → [QuizOptionState.Correct]，用户所选的错误项 → [QuizOptionState.Wrong]
 *     （选对时它同时是正确项，先命中 Correct），其余 → [QuizOptionState.Idle]；
 *  2. 未判定但本地已按下（[pending] ≠ -1）：被点的那一项 → [QuizOptionState.Selected]，其余 Idle。
 *
 * 判定之后不再看 [pending]：同一题只会有一份反馈，本地残留的下标不会把 Correct/Wrong 盖回 Selected。
 *
 * @param graded 已判定时用户所选的下标；未判定为 `null`。
 * @param pending 未判定时用户已按下的下标；没有则为 -1。
 */
internal fun quizOptionState(
    index: Int,
    answerIndex: Int,
    graded: Int?,
    pending: Int,
): QuizOptionState = when {
    graded != null -> when {
        index == answerIndex -> QuizOptionState.Correct
        index == graded -> QuizOptionState.Wrong
        else -> QuizOptionState.Idle
    }

    pending == index -> QuizOptionState.Selected
    else -> QuizOptionState.Idle
}

/**
 * 逐题作答：题干 + 4 选项砖块，点击即时判定，作答后展示解析与「下一题」。
 *
 * 锁定链路（同一题只允许一次作答、一次推进）：
 *  - `pending` 记下用户按下的下标：`selectOption` 要等 Room 写完才把 `selected` 推回来，
 *    这段窗口里被点的砖块先渲染成 [QuizOptionState.Selected]；
 *    整列的 `enabled = !answered && pending < 0` 把「作答后还能点」彻底关掉（无 ripple、无按压回弹），
 *    `onClick` 内的同名判断留作第二道闸（ViewModel 还有 `selected != null` 守卫）；
 *  - `advanced` 让「下一题」在同一题上只生效一次：`nextQuestion()` 只校验 `selected != null`，
 *    少这道闸的话连点会跳着吃掉一题。
 *
 * 两个标记都以 `question.id` 为键，切题即复位，转屏也能还原，因此不会出现「锁死在下一题」的残锁；
 * 选项整列另套一层 `key(question.id)`，换题时砖块连带内部动效状态一起重建。
 *
 * 作答前的「给点提示」与作答后的三层反馈（v2.5 缺口补齐）：
 *  - 提示逐条揭示（[nextHintLevel] 一次只挤一档、封顶 [HINT_TIER_COUNT]），
 *    [revealedHints] 只渲染已揭示的档位，文案由 [hintTiers] 保证**三档都不给最终答案**；
 *    完整解析不在提示里，要等作答后、且答错（[shouldRevealFullExplanation]）才摊开；
 *  - 答后反馈按 [buildFeedback] 分任务级 / 过程级 / 自我调节级三层，末尾挂 [FEEDBACK_SCOPE_NOTE] 那句分寸。
 */
@Composable
private fun QuestionView(
    question: Question,
    selected: Int?,
    onSelect: (Int) -> Unit,
    onNext: () -> Unit,
    isLast: Boolean,
) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    val options = parseOptions(question.optionsJson)
    val answered = selected != null
    var pending by rememberSaveable(question.id) { mutableIntStateOf(-1) }
    var advanced by rememberSaveable(question.id) { mutableStateOf(false) }
    // 已挤到第几档提示：以 question.id 为键，换题复位、转屏还原
    var hintLevel by rememberSaveable(question.id) { mutableIntStateOf(0) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(AppTheme.space.md))
        Text(
            text = question.subject,
            style = texts.caption.copy(color = colors.accentInk, fontWeight = FontWeight.Medium),
        )
        Spacer(Modifier.height(AppTheme.space.sm))
        AppCard(modifier = Modifier.fillMaxWidth()) {
            Text(text = question.stem, style = texts.body)
        }

        Spacer(Modifier.height(AppTheme.space.md))
        // 整列以 question.id 为键：换题即整列重建，砖块内的 saveable/Animatable
        // 不可能带着上一题的判定态或抖动残留进入下一题。
        key(question.id) {
            options.forEachIndexed { index, option ->
                QuizOptionTile(
                    optionText = option,
                    index = index,
                    state = quizOptionState(
                        index = index,
                        answerIndex = question.answerIndex,
                        graded = selected,
                        pending = pending,
                    ),
                    // 作答后（含 DB 回写在飞的 pending 窗口）整列锁死：不吃点击、无 ripple
                    enabled = !answered && pending < 0,
                    onClick = {
                        // 判定前只认第一次点击：pending 一置，本题其余砖块即刻失效
                        if (!answered && pending < 0) {
                            pending = index
                            onSelect(index)
                        }
                    },
                )
                Spacer(Modifier.height(AppTheme.space.sm))
            }
        }

        // ── 作答前：挤牙膏提示（线索 → 第一步 → 方法方向，三档都不给最终答案）──
        if (!answered) {
            val hints = revealedHints(question, hintLevel)
            if (hints.isNotEmpty()) {
                AppCard(modifier = Modifier.fillMaxWidth()) {
                    Text(text = "提示（一步一步来，答案还是你自己找）", style = texts.caption)
                    Spacer(Modifier.height(AppTheme.space.sm))
                    hints.forEachIndexed { tier, line ->
                        Text(text = "${tier + 1}. $line", style = texts.aux)
                        Spacer(Modifier.height(AppTheme.space.xs))
                    }
                }
                Spacer(Modifier.height(AppTheme.space.sm))
                // 提示要到了就把「卡壳是信号」那条 [科学验证] 文案带在旁边（现成事件，不改 tips 包）
                StudyTips.forEvent(TipEvent.StrugglingReview)?.let { tip ->
                    TipCard(tip = tip, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(AppTheme.space.sm))
                }
            }
            if (hintLevel < HINT_TIER_COUNT) {
                AppButton(
                    text = if (hintLevel == 0) "给点提示" else "再往前推一步（第 ${hintLevel + 1} / $HINT_TIER_COUNT 档）",
                    secondary = true,
                    onClick = { hintLevel = nextHintLevel(hintLevel) },
                )
                Spacer(Modifier.height(AppTheme.space.sm))
            }
        }

        if (answered) {
            val right = selected == question.answerIndex
            // 三层反馈全部出自 QuizFeedback 纯函数（单测钉着：提示不剧透、文案不夸大）
            val feedback = buildFeedback(
                question = question,
                selected = selected,
                options = options,
                hintsUsed = hintLevel,
            )
            AppCard(modifier = Modifier.fillMaxWidth()) {
                // 任务级：判定文案是文字：走 ink（浅色 success 2.22:1 / warning 3.07:1 都不达 AA，
                // successInk/warningInk 压白卡 5.39 / 5.60:1）
                Text(
                    text = feedback.verdict,
                    style = texts.cardTitle.copy(
                        color = if (right) colors.successInk else colors.warningInk,
                    ),
                )
                Spacer(Modifier.height(AppTheme.space.sm))
                // 过程级：错在哪一步 / 思路（来自 question.explanation）
                Text(text = feedback.process, style = texts.aux)
                Spacer(Modifier.height(AppTheme.space.sm))
                // 自我调节级：下一步建议（随用了几档提示变化）
                Text(text = feedback.selfReg, style = texts.aux)
                // 完整解析：答错才摊开；答对不重复灌（见 shouldRevealFullExplanation）
                if (shouldRevealFullExplanation(hintsUsed = hintLevel, correct = right) &&
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
            // 高置信答错（一档提示都没要却答错）是矫枉机会：带出现成的 HYPERCORRECTION 条目
            if (!right && hintLevel == 0) {
                Spacer(Modifier.height(AppTheme.space.sm))
                StudyTips.forEvent(TipEvent.HighConfidenceMistake)?.let { tip ->
                    TipCard(tip = tip, modifier = Modifier.fillMaxWidth())
                }
            }
            Spacer(Modifier.height(AppTheme.space.md))
            AppButton(
                text = if (isLast) "查看结果" else "下一题",
                onClick = {
                    if (!advanced) {
                        advanced = true
                        onNext()
                    }
                },
            )
        }
        Spacer(Modifier.height(AppTheme.space.lg))
    }
}

/**
 * 结果页：环形正确率（132dp 居中）+ 百分比 + 答对题数。
 *
 * `progress` 直接取整数百分比 / 100，因此环与读数永远同一个口径、不会出现「99% 的环画满格」；
 * 达成态换 `colors.goldInk` 的门槛是 80%（brief 规定），且只在真有正确率时出现；
 * 用 ink 而非 `gold`：金色环在浅色卡面只有 1.79:1，细一圈几乎看不见。
 *
 * 环与百分比都以 `born` 门控从 0 起步：[RingGauge] 与 `animateIntAsState` 首次组合都直接落在
 * target（没有「0 → N」的过程），故按 [StudyHomeScreen] 火焰徽章与 T8 小结卡同法补一次进场补间，
 * 交卷瞬间能看到环扫到位、数字滚动。`born` 取 `rememberSaveable`：交卷后转屏时它恢复为 `true`，
 * 目标值不再从 0 起步，因此环与读数都直接落位、不会凭空重播一遍。
 */
@Composable
private fun QuizResult(
    correctCount: Int,
    total: Int,
    percent: Int,
    onRestart: () -> Unit,
    onBack: () -> Unit,
) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    // saveable：转屏后 born 直接恢复为 true ⇒ 目标值不再从 0 起步，
    // 环与数字都不会凭空重播一次（与 `pending/advanced/wrongShaken` 同一条纪律）
    var born by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { born = true }
    val shownPercent by animateIntAsState(
        targetValue = if (born) percent else 0,
        animationSpec = tween(durationMillis = MotionSpec.CountUpMs, easing = MotionSpec.Easing),
        label = "quizAccuracy",
    )
    val progress = if (total == 0) 0f else percent / 100f

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        AppCard(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "本轮完成",
                style = texts.pageTitle,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(AppTheme.space.lg))
            // AppCard 的内容列默认起始对齐，这里再用一层居中 Box 把环放到卡片正中
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier.size(132.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    RingGauge(
                        progress = if (born) progress else 0f,
                        modifier = Modifier.fillMaxSize(),
                        strokeWidth = 11.dp,
                        color = if (percent >= 80 && total > 0) colors.goldInk else colors.accent,
                    )
                    Text(
                        text = "$shownPercent%",
                        style = texts.statValue,
                    )
                }
            }
            Spacer(Modifier.height(AppTheme.space.lg))
            Text(
                text = "答对 $correctCount / $total 题",
                style = texts.caption,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(AppTheme.space.lg))
        AppButton(text = "返回", onClick = onBack)
        Spacer(Modifier.height(AppTheme.space.md))
        AppButton(text = "换个学科再来一轮", secondary = true, onClick = onRestart)
    }
}
