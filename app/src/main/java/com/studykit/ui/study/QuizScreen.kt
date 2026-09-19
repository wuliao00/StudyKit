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
import com.studykit.ui.motion.MotionSpec
import com.studykit.data.entity.Question
import com.studykit.ui.components.AppButton
import com.studykit.ui.components.AppCard
import com.studykit.ui.components.EmptyState
import com.studykit.ui.components.QuizOptionState
import com.studykit.ui.components.QuizOptionTile
import com.studykit.ui.components.RingGauge
import com.studykit.ui.theme.AppTheme

/**
 * 题库练习页：学科选择 → 逐题作答（即时判定 + 解析）→ 正确率环形结果页。
 *
 * 颜色与文字样式统一取 `AppTheme`，间距/圆角取 `AppTheme.space` / `AppTheme.radius` 的 dp 常量。
 *
 * 一题的状态机（详见 [QuestionView] 与 [quizOptionState]）：
 * `点击 → 本地锁定（Selected）→ DB 回写判定（Correct/Wrong + 解析）→ 下一题`，
 * 每一跳都只有一个入口能推进，重复点击与连点「下一题」都被挡掉。
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

        if (answered) {
            val right = selected == question.answerIndex
            AppCard(modifier = Modifier.fillMaxWidth()) {
                // 判定文案是文字：走 ink（浅色 success 2.22:1 / warning 3.07:1 都不达 AA，
                // successInk/warningInk 压白卡 5.39 / 5.60:1）
                Text(
                    text = if (right) "回答正确" else "回答错误",
                    style = texts.cardTitle.copy(
                        color = if (right) colors.successInk else colors.warningInk,
                    ),
                )
                Spacer(Modifier.height(AppTheme.space.sm))
                Text(text = "解析：${question.explanation}", style = texts.aux)
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
