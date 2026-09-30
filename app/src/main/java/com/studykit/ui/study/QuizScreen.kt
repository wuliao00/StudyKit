package com.studykit.ui.study

import android.app.Application
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
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.studykit.StudyKitApp
import com.studykit.data.entity.Question
import com.studykit.data.repository.QuestionRepository
import com.studykit.srs.Confidence
import com.studykit.srs.ReviewPlanner
import com.studykit.tips.Tip
import com.studykit.tips.TipEvent
import com.studykit.tips.StudyTips
import com.studykit.ui.components.AppButton
import com.studykit.ui.components.AppCard
import com.studykit.ui.components.EmptyState
import com.studykit.ui.components.TipCard
import com.studykit.ui.mistake.MistakeMastery
import com.studykit.ui.mistake.MistakeReviewLogic
import com.studykit.ui.theme.DesignTokens
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 一轮练习里一道题的完整作答痕迹（含自评信心与提示档数）。
 *
 * 这两列是元认知校准与超纠正排期的输入：没有信心自评就认不出「高置信答错」，
 * 没有提示档数就分不清「自己想出来」和「被提示带着走」。
 */
data class QuizAttempt(
    val questionId: Long,
    val confidence: Confidence,
    val hintLevel: Int,
    val selected: Int,
    val correct: Boolean,
)

/** 一道题的一条分层反馈：任务级（对不对）→ 过程级（思路）→ 自我调节级（下一步） */
data class QuizFeedback(
    val layer: MistakeMastery.FeedbackLayer,
    val label: String,
    val text: String,
)

/**
 * 刷题会话的纯逻辑：交错顺序、信心闸门、挤牙膏档位门槛、分层反馈内容选择。
 *
 * 抽成无 Android 依赖的函数只为可测，Compose 负责把结果画出来。三个反直觉设定：
 * - 先自评再作答（Metcalfe 2011 超纠正效应的前置条件）。
 * - 提示逐档揭示、完整解析留到最后（Bjork 必要难度：看解析只留熟悉感）。
 * - 多学科打散（Brunmair & Richter 2019；Kornell & Bjork 2008）。
 */
object QuizSessionLogic {

    /** 一轮计划的题数，与仓储层的会话规模一致 */
    val sessionSize: Int = QuestionRepository.SESSION_SIZE

    /**
     * 交错开关下的题目顺序。
     *
     * 关闭交错、题目不足 2 道、或只有一个学科时都保持原序：
     * 单学科硬打散没有意义，交错只在需要分辨考点时才产生收益。
     */
    fun orderedQuestions(questions: List<Question>, interleave: Boolean): List<Question> {
        if (!interleave || questions.size < 2) return questions
        if (subjectCount(questions) < 2) return questions
        val pairs = questions.mapIndexed { index, question -> index to question.subject }
        return ReviewPlanner.interleave(pairs).map { pair -> questions[pair.first] }
    }

    /** 题池里出现的学科数（<2 时交错不会真正生效） */
    fun subjectCount(questions: List<Question>): Int = questions.map { it.subject }.distinct().size

    /** 必须先自评信心，才允许提交答案 */
    fun canAnswer(confidence: Confidence?): Boolean = confidence != null

    /** 还能继续挤下一档提示 */
    fun canRevealMoreHints(hintLevel: Int): Boolean = hintLevel < MistakeMastery.HINT_LEVELS

    /** 挤一档：到第三档就停住，任何一档都不直接给最终答案 */
    fun nextHintLevel(hintLevel: Int): Int = MistakeMastery.nextHint(hintLevel).level

    /** 已经揭示出来的提示（按档位顺序） */
    fun revealedHints(hintLevel: Int): List<MistakeMastery.Hint> =
        (0 until hintLevel.coerceIn(0, MistakeMastery.HINT_LEVELS)).map { MistakeMastery.nextHint(it) }

    /**
     * 完整解析的闸门：只有把三档提示用完才展开。
     *
     * 作答本身不能绕过这道闸门——「对答案」属于任务级反馈，随时给；
     * 「看解析」是重复暴露，得由用户自己一档档挤出来。
     */
    fun canRevealSolution(hintLevel: Int): Boolean = hintLevel >= MistakeMastery.HINT_LEVELS

    /** 没用完提示时给用户的说明：解释规则，不做效果承诺 */
    fun solutionLockText(hintLevel: Int): String {
        val remain = (MistakeMastery.HINT_LEVELS - hintLevel).coerceIn(0, MistakeMastery.HINT_LEVELS)
        return "完整解析还差 $remain 档提示。逐档挤出来的过程本身就是练习；" +
            "一次看全更容易只留下「好像见过」的熟悉感。"
    }

    /** 高置信答错（超纠正信号）：越有把握却做错，越值得尽快重测 */
    fun isHighConfidenceError(correct: Boolean, confidence: Confidence?): Boolean =
        !correct && confidence == Confidence.SURE

    /** 自我调节级反馈：下一步该做什么 */
    fun nextStepAdvice(correct: Boolean, confidence: Confidence?, hintLevel: Int): String = when {
        !correct && confidence == Confidence.SURE ->
            "先写下你刚才为什么那么确定，再逐句对照解析找分歧点——这类错误纠正后记得最牢。"
        !correct && hintLevel >= MistakeMastery.HINT_LEVELS ->
            "把靠着提示走完的那几步用自己的话复述一遍，明天空手重做这道题。"
        !correct ->
            "先别急着往下刷：想一遍这题考的是哪个点。它已经进了错题本，到期时会回到队列。"
        correct && confidence == Confidence.GUESS ->
            "蒙对也要归因：记下这次依据的是哪条线索，否则下次仍然靠猜。"
        correct && hintLevel > 0 ->
            "提示用到第 $hintLevel 档：把最需要提示的那一步单独记下来，下次从那里开始想。"
        else ->
            "这题稳了，可以换个情境再练一遍，看看是不是真的能迁移。"
    }

    /**
     * 分层反馈内容选择。
     *
     * 任务级恒给出；过程级（思路 = 题目解析）只有三档提示用完之后才给出；
     * 自我调节级恒给出。
     */
    fun feedbackLines(
        correct: Boolean,
        confidence: Confidence?,
        hintLevel: Int,
        explanation: String,
    ): List<QuizFeedback> {
        val lines = mutableListOf(
            QuizFeedback(
                layer = MistakeMastery.FeedbackLayer.TASK,
                label = "本题",
                text = if (correct) "回答正确" else "回答错误",
            ),
        )
        if (canRevealSolution(hintLevel) && explanation.isNotBlank()) {
            lines += QuizFeedback(
                layer = MistakeMastery.FeedbackLayer.PROCESS,
                label = "思路",
                text = explanation,
            )
        }
        lines += QuizFeedback(
            layer = MistakeMastery.FeedbackLayer.SELF_REGULATION,
            label = "下一步",
            text = nextStepAdvice(correct, confidence, hintLevel),
        )
        return lines
    }

    /** 交错说明：交错真正生效时讲一次；单学科连做到阈值却没开交错时也讲一次 */
    fun interleaveTip(interleave: Boolean, subjectCount: Int, questionCount: Int): Tip? {
        val interleaving = interleave && subjectCount >= 2
        val blocking = !interleave && subjectCount <= 1 &&
            questionCount >= StudyTips.BLOCKING_STREAK_THRESHOLD
        return if (interleaving || blocking) interleaveTipFromLibrary() else null
    }

    /** 交错文案一律取自 StudyTips，UI 端不自写证据引用 */
    private fun interleaveTipFromLibrary(): Tip? = StudyTips.forEvent(
        TipEvent.BlockingStreak(items = StudyTips.BLOCKING_STREAK_THRESHOLD, subjects = 1),
    )

    /** 会话是否已答完（决定结果页） */
    fun isFinished(running: Boolean, questionCount: Int, index: Int): Boolean =
        running && questionCount > 0 && index >= questionCount

    /** 答对题数 */
    fun correctCount(attempts: List<QuizAttempt>): Int = attempts.count { it.correct }

    /** 高置信答错数：结果页据此解释为什么这些题排在错题本队列前面 */
    fun highConfidenceErrorCount(attempts: List<QuizAttempt>): Int =
        attempts.count { isHighConfidenceError(it.correct, it.confidence) }

    /** 正确率百分比（零题返回 0，避免除零） */
    fun accuracyPercent(attempts: List<QuizAttempt>): Int =
        if (attempts.isEmpty()) 0 else correctCount(attempts) * 100 / attempts.size

    /** 新错题的初始复习时间：与错题本同一口径（次日回到队列） */
    fun firstReviewAt(now: Long): Long = MistakeReviewLogic.firstReviewAt(now)

    /** 选项序号 A/B/C… */
    fun letterOf(index: Int): String = ('A' + index).toString()
}

/** 一轮刷题会话的界面状态 */
data class QuizSessionState(
    val running: Boolean = false,
    val loading: Boolean = false,
    val picked: List<String> = emptyList(),
    val interleave: Boolean = true,
    val pool: List<Question> = emptyList(),
    val questions: List<Question> = emptyList(),
    val index: Int = 0,
    val confidence: Confidence? = null,
    val hintLevel: Int = 0,
    val selected: Int? = null,
    val correct: Boolean? = null,
    val attempts: List<QuizAttempt> = emptyList(),
    val justCaptured: Boolean = false,
) {
    val current: Question? get() = questions.getOrNull(index)
    val answered: Boolean get() = selected != null
    val settled: Boolean get() = correct != null
    val finished: Boolean get() = QuizSessionLogic.isFinished(running, questions.size, index)
}

/**
 * 刷题会话 ViewModel：自持题池与顺序，把自评信心与提示档写进练习记录，
 * 答错按 qid 幂等收录进错题本并带上高置信标记。
 *
 * 有意不复用 StudyViewModel 里的旧题库流程：那条流程承载不了 confidence/hintLevel，
 * 也没有多学科题池与交错重排的入口，改它会和另一位同事的卡片改造冲突。
 */
class QuizSessionViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as StudyKitApp).container
    private val questionRepository = container.questionRepository
    private val mistakeRepository = container.mistakeRepository

    private val _ui = MutableStateFlow(QuizSessionState())
    val ui: StateFlow<QuizSessionState> = _ui

    /** 学科多选（仅在未开始会话时可调） */
    fun toggleSubject(subject: String) {
        val state = _ui.value
        if (state.running) return
        val picked = if (subject in state.picked) state.picked - subject else state.picked + subject
        _ui.value = state.copy(picked = picked)
    }

    /** 交错开关：默认开启。会话进行中只记录开关，不打乱已经做过的顺序 */
    fun setInterleave(enabled: Boolean) {
        val state = _ui.value
        _ui.value = if (state.running) {
            state.copy(interleave = enabled)
        } else {
            state.copy(
                interleave = enabled,
                questions = QuizSessionLogic.orderedQuestions(state.pool, enabled),
            )
        }
    }

    /** 开始一轮：取多学科题池，再按开关决定是否交错 */
    fun start() {
        val state = _ui.value
        if (state.running || state.picked.isEmpty()) return
        _ui.value = state.copy(running = true, loading = true)
        viewModelScope.launch {
            val pool = questionRepository.getPoolBySubjects(state.picked, QuizSessionLogic.sessionSize)
            val current = _ui.value
            _ui.value = current.copy(
                loading = false,
                pool = pool,
                questions = QuizSessionLogic.orderedQuestions(pool, current.interleave),
                index = 0,
                attempts = emptyList(),
            )
        }
    }

    /** 回到学科选择（保留已选学科与交错开关） */
    fun reset() {
        _ui.value = QuizSessionState(picked = _ui.value.picked, interleave = _ui.value.interleave)
    }

    /** 作答前的自评信心；提交之后锁定不可再改 */
    fun selectConfidence(confidence: Confidence) {
        val state = _ui.value
        if (state.answered) return
        _ui.value = state.copy(confidence = confidence)
    }

    /** 挤一档提示：最多三档，到顶就停 */
    fun revealHint() {
        val state = _ui.value
        if (!QuizSessionLogic.canRevealMoreHints(state.hintLevel)) return
        _ui.value = state.copy(hintLevel = QuizSessionLogic.nextHintLevel(state.hintLevel))
    }

    /** 提交答案：写练习记录（含信心与提示档），答错则幂等收录进错题本 */
    fun answer(selected: Int) {
        val state = _ui.value
        val question = state.current ?: return
        val confidence = state.confidence ?: return
        if (state.answered || !QuizSessionLogic.canAnswer(confidence)) return
        val hintLevel = state.hintLevel
        _ui.value = state.copy(selected = selected)
        viewModelScope.launch {
            val correct = questionRepository.submitAnswer(question.id, selected, confidence, hintLevel)
            val captured = if (correct) {
                false
            } else {
                mistakeRepository.capturePracticeMistake(
                    question = question,
                    options = parseOptions(question.optionsJson),
                    highConfidenceError = QuizSessionLogic.isHighConfidenceError(correct, confidence),
                    reviewAt = QuizSessionLogic.firstReviewAt(System.currentTimeMillis()),
                ) != null
            }
            val current = _ui.value
            _ui.value = current.copy(
                correct = correct,
                justCaptured = captured,
                attempts = current.attempts + QuizAttempt(
                    questionId = question.id,
                    confidence = confidence,
                    hintLevel = hintLevel,
                    selected = selected,
                    correct = correct,
                ),
            )
        }
    }

    /** 下一题：清空本题的信心、提示档与作答痕迹 */
    fun next() {
        val state = _ui.value
        if (!state.settled) return
        _ui.value = state.copy(
            index = state.index + 1,
            confidence = null,
            hintLevel = 0,
            selected = null,
            correct = null,
            justCaptured = false,
        )
    }
}

/**
 * 题库练习页：多学科选题（默认交错）→ 先自评信心 → 逐档挤提示 → 作答 → 分层反馈 → 结果页。
 */
@Composable
fun QuizScreen(
    viewModel: StudyViewModel,
    onBack: () -> Unit,
) {
    val sessionViewModel: QuizSessionViewModel = viewModel()
    val state by sessionViewModel.ui.collectAsStateWithLifecycle()
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
                    sessionViewModel.reset()
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
            if (state.running && !state.finished && state.questions.isNotEmpty()) {
                Text(
                    text = "第 ${state.index + 1} / ${state.questions.size} 题",
                    style = DesignTokens.Caption,
                )
            }
        }

        when {
            !state.running -> SubjectPicker(
                subjects = subjects,
                picked = state.picked,
                interleave = state.interleave,
                onToggleSubject = sessionViewModel::toggleSubject,
                onToggleInterleave = sessionViewModel::setInterleave,
                onStart = sessionViewModel::start,
            )

            state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = DesignTokens.Accent)
            }

            state.questions.isEmpty() -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(DesignTokens.SpacingXl * 2))
                EmptyState(
                    title = "这些学科还没有题目",
                    caption = "回到学习首页，在「题库练习」卡片上点击 + 录入几道题",
                    icon = Icons.Outlined.CheckCircle,
                )
                Spacer(Modifier.height(DesignTokens.SpacingLg))
                AppButton(text = "重新选题", onClick = sessionViewModel::reset)
            }

            state.finished || state.current == null -> QuizSummary(
                attempts = state.attempts,
                onRestart = sessionViewModel::reset,
                onBack = onBack,
            )

            else -> QuestionStage(
                question = state.current!!,
                state = state,
                onConfidence = sessionViewModel::selectConfidence,
                onHint = sessionViewModel::revealHint,
                onAnswer = sessionViewModel::answer,
                onNext = sessionViewModel::next,
                isLast = state.index == state.questions.size - 1,
            )
        }
    }
}

/** 学科多选 + 交错开关 */
@Composable
private fun SubjectPicker(
    subjects: List<String>,
    picked: List<String>,
    interleave: Boolean,
    onToggleSubject: (String) -> Unit,
    onToggleInterleave: (Boolean) -> Unit,
    onStart: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(DesignTokens.SpacingMd))
        Text(text = "选一个或多个学科，开始一轮练习", style = DesignTokens.Caption)
        Spacer(Modifier.height(DesignTokens.SpacingMd))

        if (subjects.isEmpty()) {
            Spacer(Modifier.height(DesignTokens.SpacingXl * 2))
            EmptyState(
                title = "题库还是空的",
                caption = "回到学习首页，在「题库练习」卡片上点击 + 录入第一道题",
                icon = Icons.Outlined.CheckCircle,
            )
            return@Column
        }

        subjects.forEach { subject ->
            PickRow(
                label = subject,
                checked = subject in picked,
                onClick = { onToggleSubject(subject) },
            )
            Spacer(Modifier.height(DesignTokens.SpacingSm))
        }

        Spacer(Modifier.height(DesignTokens.SpacingMd))
        AppCard(modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "交错练习", style = DesignTokens.CardTitle)
                    Spacer(Modifier.height(DesignTokens.SpacingXs))
                    Text(
                        text = "把不同学科的题目打散着做。这样当下会觉得更难，是正常的：" +
                            "难的是分辨考点，不是手生。",
                        style = DesignTokens.Caption,
                    )
                }
                Spacer(Modifier.width(DesignTokens.SpacingSm))
                Checkbox(checked = interleave, onCheckedChange = { onToggleInterleave(it) })
            }
        }

        QuizSessionLogic.interleaveTip(
            interleave = interleave,
            subjectCount = picked.size,
            questionCount = QuizSessionLogic.sessionSize,
        )?.let { tip ->
            Spacer(Modifier.height(DesignTokens.SpacingSm))
            TipCard(tip = tip, modifier = Modifier.fillMaxWidth(), showEvidence = true)
        }

        Spacer(Modifier.height(DesignTokens.SpacingLg))
        AppButton(
            text = if (picked.size > 1) "开始交错练习" else "开始练习",
            enabled = picked.isNotEmpty(),
            onClick = onStart,
        )
        Spacer(Modifier.height(DesignTokens.SpacingLg))
    }
}

/** 可点的选择行：整行点击与勾选框等价，避免小目标难点 */
@Composable
private fun PickRow(label: String, checked: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(DesignTokens.CornerRadius))
            .background(DesignTokens.Card)
            .border(
                width = if (checked) 1.5.dp else 1.dp,
                color = if (checked) DesignTokens.Accent else DesignTokens.Divider,
                shape = RoundedCornerShape(DesignTokens.CornerRadius),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = DesignTokens.CardPadding, vertical = DesignTokens.SpacingSm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = DesignTokens.CardTitle,
            modifier = Modifier.weight(1f),
        )
        Checkbox(checked = checked, onCheckedChange = { onClick() })
    }
}

/** 单题阶段：信心自评 → 逐档提示 → 选项 → 分层反馈 */
@Composable
private fun QuestionStage(
    question: Question,
    state: QuizSessionState,
    onConfidence: (Confidence) -> Unit,
    onHint: () -> Unit,
    onAnswer: (Int) -> Unit,
    onNext: () -> Unit,
    isLast: Boolean,
) {
    val options = parseOptions(question.optionsJson)
    val answered = state.answered
    val solutionVisible = QuizSessionLogic.canRevealSolution(state.hintLevel)
    val firstQuestionTip = if (state.index == 0) {
        QuizSessionLogic.interleaveTip(
            interleave = state.interleave,
            subjectCount = QuizSessionLogic.subjectCount(state.questions),
            questionCount = state.questions.size,
        )
    } else {
        null
    }

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
        firstQuestionTip?.let { tip ->
            Spacer(Modifier.height(DesignTokens.SpacingXs))
            TipCard(tip = tip, modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(DesignTokens.SpacingSm))
        AppCard(modifier = Modifier.fillMaxWidth()) {
            Text(text = question.stem, style = DesignTokens.Body)
        }

        // ── 作答前的自评信心：没有把握度就没有超纠正信号 ──────────────────
        Spacer(Modifier.height(DesignTokens.SpacingMd))
        Text(
            text = if (answered) "本次自评：${confidenceLabel(state.confidence)}" else "先自评把握，再选答案",
            style = DesignTokens.Caption,
        )
        Spacer(Modifier.height(DesignTokens.SpacingSm))
        Row(horizontalArrangement = Arrangement.spacedBy(DesignTokens.SpacingSm)) {
            Confidence.entries.forEach { level ->
                ChoiceChip(
                    text = confidenceLabel(level),
                    color = DesignTokens.Accent,
                    selected = state.confidence == level,
                    interactive = !answered,
                    modifier = Modifier.weight(1f),
                    onClick = { onConfidence(level) },
                )
            }
        }

        // ── 挤牙膏提示：三档用完才允许展开完整解析 ────────────────────────
        Spacer(Modifier.height(DesignTokens.SpacingMd))
        QuizSessionLogic.revealedHints(state.hintLevel).forEach { hint ->
            Text(
                text = "第 ${hint.level} 档｜${hint.label}",
                style = DesignTokens.Auxiliary.copy(color = DesignTokens.SecondaryText),
                modifier = Modifier.padding(bottom = DesignTokens.SpacingXs),
            )
        }
        AppButton(
            text = if (QuizSessionLogic.canRevealMoreHints(state.hintLevel)) {
                "挤一档提示（已用 ${state.hintLevel} / ${MistakeMastery.HINT_LEVELS}）"
            } else {
                "提示已经挤到第三档"
            },
            secondary = true,
            enabled = QuizSessionLogic.canRevealMoreHints(state.hintLevel),
            onClick = onHint,
        )

        // ── 选项 ──────────────────────────────────────────────────────────
        Spacer(Modifier.height(DesignTokens.SpacingMd))
        if (!answered && state.confidence == null) {
            Text(
                text = "选好上面的把握度之后，选项才会放开。",
                style = DesignTokens.Caption.copy(color = DesignTokens.Accent),
                modifier = Modifier.padding(bottom = DesignTokens.SpacingSm),
            )
        }
        options.forEachIndexed { index, option ->
            OptionRow(
                letter = QuizSessionLogic.letterOf(index),
                text = option,
                isCorrect = index == question.answerIndex,
                isSelected = index == state.selected,
                answered = answered,
                selectable = !answered && state.confidence != null,
                onClick = { onAnswer(index) },
            )
            Spacer(Modifier.height(DesignTokens.SpacingSm))
        }

        // ── 分层反馈 ──────────────────────────────────────────────────────
        if (answered && !state.settled) {
            Text(text = "记录作答中…", style = DesignTokens.Caption)
        }
        if (state.settled) {
            Spacer(Modifier.height(DesignTokens.SpacingSm))
            FeedbackCard(
                lines = QuizSessionLogic.feedbackLines(
                    correct = state.correct == true,
                    confidence = state.confidence,
                    hintLevel = state.hintLevel,
                    explanation = question.explanation,
                ),
                lockedProcessText = if (solutionVisible) {
                    null
                } else {
                    QuizSessionLogic.solutionLockText(state.hintLevel)
                },
            )
        }

        if (state.settled && QuizSessionLogic.isHighConfidenceError(state.correct == true, state.confidence)) {
            StudyTips.forEvent(TipEvent.HighConfidenceMistake)?.let { tip ->
                Spacer(Modifier.height(DesignTokens.SpacingSm))
                TipCard(tip = tip, modifier = Modifier.fillMaxWidth())
            }
        }

        if (state.settled && state.justCaptured) {
            Spacer(Modifier.height(DesignTokens.SpacingSm))
            Text(
                text = "这道错题已收进错题本，复习时间由算法按欠账程度安排。",
                style = DesignTokens.Caption,
            )
        }

        Spacer(Modifier.height(DesignTokens.SpacingMd))
        AppButton(
            text = if (isLast) "查看结果" else "下一题",
            enabled = state.settled,
            onClick = onNext,
        )
        Spacer(Modifier.height(DesignTokens.SpacingLg))
    }
}

/** 分层反馈卡：任务级 / 过程级 / 自我调节级各占一段 */
@Composable
private fun FeedbackCard(lines: List<QuizFeedback>, lockedProcessText: String?) {
    AppCard(modifier = Modifier.fillMaxWidth()) {
        lines.forEachIndexed { index, line ->
            Text(
                text = line.label,
                style = DesignTokens.Caption.copy(
                    color = layerColor(line.layer),
                    fontWeight = FontWeight.Medium,
                ),
            )
            Spacer(Modifier.height(DesignTokens.SpacingXs))
            Text(text = line.text, style = DesignTokens.Auxiliary)
            if (index != lines.lastIndex) Spacer(Modifier.height(DesignTokens.SpacingMd))
        }
        if (lockedProcessText != null) {
            Spacer(Modifier.height(DesignTokens.SpacingMd))
            Text(text = lockedProcessText, style = DesignTokens.Caption)
        }
    }
}

private fun layerColor(layer: MistakeMastery.FeedbackLayer): Color = when (layer) {
    MistakeMastery.FeedbackLayer.TASK -> DesignTokens.SecondaryText
    MistakeMastery.FeedbackLayer.PROCESS -> DesignTokens.Accent
    MistakeMastery.FeedbackLayer.ROOT_CAUSE -> DesignTokens.Gold
    MistakeMastery.FeedbackLayer.SELF_REGULATION -> DesignTokens.Success
}

/** 选项行：未自评信心时不可点；作答后正确项=成功色、选中错项=警示色、其余淡出 */
@Composable
private fun OptionRow(
    letter: String,
    text: String,
    isCorrect: Boolean,
    isSelected: Boolean,
    answered: Boolean,
    selectable: Boolean,
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
            .clickable(enabled = selectable, onClick = onClick)
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

/** 信心三档中文标签 */
private fun confidenceLabel(confidence: Confidence?): String = when (confidence) {
    Confidence.GUESS -> "瞎猜"
    Confidence.VAGUE -> "有点印象"
    Confidence.SURE -> "非常确定"
    null -> "未自评"
}

/** 描边选择块：文案色即语义色，用于信心三档 */
@Composable
private fun ChoiceChip(
    text: String,
    color: Color,
    selected: Boolean,
    interactive: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .height(50.dp)
            .clip(RoundedCornerShape(DesignTokens.CornerRadius))
            .background(if (selected) color.copy(alpha = 0.12f) else DesignTokens.Card)
            .border(
                width = if (selected) 1.5.dp else 1.dp,
                color = if (selected) color else DesignTokens.Divider,
                shape = RoundedCornerShape(DesignTokens.CornerRadius),
            )
            .clickable(enabled = interactive, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = DesignTokens.Caption.copy(
                color = when {
                    selected -> color
                    !interactive -> DesignTokens.Divider
                    else -> DesignTokens.SecondaryText
                },
            ),
            textAlign = TextAlign.Center,
        )
    }
}

/** 结果页：正确率 + 高置信答错数（超纠正队列的入口线索） */
@Composable
private fun QuizSummary(
    attempts: List<QuizAttempt>,
    onRestart: () -> Unit,
    onBack: () -> Unit,
) {
    val correct = QuizSessionLogic.correctCount(attempts)
    val percent = QuizSessionLogic.accuracyPercent(attempts)
    val highConfidenceErrors = QuizSessionLogic.highConfidenceErrorCount(attempts)

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
                text = "答对 $correct / ${attempts.size} 题",
                style = DesignTokens.Caption,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
            if (highConfidenceErrors > 0) {
                Spacer(Modifier.height(DesignTokens.SpacingMd))
                Text(
                    text = "其中 $highConfidenceErrors 题是很确定却答错的，它们在错题本里排得靠前、间隔也更紧。",
                    style = DesignTokens.Auxiliary.copy(color = DesignTokens.Warning),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        Spacer(Modifier.height(DesignTokens.SpacingLg))
        AppButton(text = "返回", onClick = onBack)
        Spacer(Modifier.height(DesignTokens.SpacingMd))
        AppButton(text = "重新选题再来一轮", secondary = true, onClick = onRestart)
    }
}
