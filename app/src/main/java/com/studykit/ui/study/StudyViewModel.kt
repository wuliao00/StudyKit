package com.studykit.ui.study

import android.app.Application
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.studykit.StudyKitApp
import com.studykit.data.entity.Mistake
import com.studykit.data.entity.Question
import com.studykit.data.entity.Word
import com.studykit.srs.Confidence
import com.studykit.srs.Fsrs
import com.studykit.srs.MemoryState
import com.studykit.srs.Rating
import com.studykit.srs.ReviewPlanner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONArray
import kotlin.math.roundToInt

/** 解析题目的 options_json（JSONArray 字符串）为选项列表 */
fun parseOptions(optionsJson: String): List<String> = try {
    val array = JSONArray(optionsJson)
    (0 until array.length()).map { array.optString(it) }
} catch (e: Exception) {
    emptyList()
}

/** 卡片会话阶段：新词预测试 → 强迫回忆 → 翻面评分 */
enum class CardPhase { PRETEST, RECALL, ANSWER }

/**
 * 一次评分后的排期结果（纯函数输出，便于单元测试，不直接触库）。
 */
data class SchedulingOutcome(
    val state: MemoryState,
    val dueAt: Long,
    val intervalDays: Int,
    val status: String,
)

/**
 * 卡片会话的纯逻辑：阶段状态机、旧按钮到四档的映射、Word↔MemoryState 转换、
 * 走 FSRS 引擎的排期、记忆看板与遗忘曲线的取数与格式化。
 *
 * 抽成无 Android 依赖的对象，是为了让「检索优先 + FSRS 排期」这套决策可测，
 * Compose UI 只负责把这些结果画出来。
 */
object CardSessionLogic {

    /** 由 Word 的 FSRS 列构造记忆状态 */
    fun wordToState(word: Word): MemoryState = MemoryState(
        stability = word.stability,
        difficulty = word.difficulty,
        lastReviewAt = word.lastReviewAt,
        reps = word.reps,
        lapses = word.lapses,
    )

    /** 一张卡片的起始阶段：新词（reps==0）先进预测试，其余直接进回忆 */
    fun startingPhase(word: Word): CardPhase =
        if (word.reps <= 0) CardPhase.PRETEST else CardPhase.RECALL

    /** 预测试结束（猜过或跳过）后一律进入 RECALL；非预测试阶段保持不变 */
    fun afterPretest(current: CardPhase, skipped: Boolean): CardPhase =
        if (current == CardPhase.PRETEST) CardPhase.RECALL else current

    /** 选定自评信心后进入 ANSWER（翻面） */
    fun afterConfidence(current: CardPhase): CardPhase =
        if (current == CardPhase.RECALL) CardPhase.ANSWER else current

    /** 旧「认识 / 不认识」按钮语义映射到新四档，保持向后兼容 */
    fun ratingForLegacy(known: Boolean): Rating = if (known) Rating.GOOD else Rating.AGAIN

    /**
     * 走引擎排期：未调度（reps==0 或 stability<=0）用 firstRating 起步，
     * 其余交给 ReviewPlanner.review（内部按自评信心微调目标保留率）。
     * Again 无论哪条路径都落在 now + RELEARN_MS（十分钟内回到队列）。
     */
    fun schedule(
        word: Word,
        rating: Rating,
        confidence: Confidence,
        now: Long,
    ): SchedulingOutcome {
        val state = wordToState(word)
        val correct = rating != Rating.AGAIN
        val retention = ReviewPlanner.nextRetention(confidence, correct)
        val (newState, dueAt, interval) = if (state.reps <= 0 || state.stability <= 0.0) {
            val first = Fsrs.firstRating(rating, now)
            val due = if (rating == Rating.AGAIN) now + Fsrs.RELEARN_MS else Fsrs.dueAt(first, retention)
            val days = if (rating == Rating.AGAIN) 0 else Fsrs.intervalDays(first.stability, retention)
            Triple(first, due, days)
        } else {
            val result = ReviewPlanner.review(state, rating, confidence, correct, now)
            Triple(result.state, result.dueAt, result.intervalDays)
        }
        return SchedulingOutcome(
            state = newState,
            dueAt = dueAt,
            intervalDays = interval,
            status = Word.statusFor(newState.reps, newState.stability),
        )
    }

    /** 未调度词的保留率返回 null，UI 显示占位符而非 0% */
    fun predictedRetention(word: Word, now: Long): Double? {
        val state = wordToState(word)
        return if (state.stability <= 0.0) null else Fsrs.retrievability(state, now)
    }

    /** 已调度词的欠账天数（未到期为 0） */
    fun overdueDays(word: Word, now: Long): Int {
        val state = wordToState(word)
        return if (state.stability <= 0.0) 0 else ReviewPlanner.overdueDays(state, now)
    }

    /** 未来 [days] 天的平均预测遗忘曲线，逐点取当天末尾的整体保留率 */
    fun forgettingCurve(states: List<MemoryState>, now: Long, days: Int = 14): List<Double> {
        if (states.isEmpty()) return emptyList()
        return (0 until days).map { offset ->
            val at = now + offset * Fsrs.DAY_MS
            states.sumOf { Fsrs.retrievability(it, at) } / states.size
        }
    }

    /** 保留率转整数百分比（四舍五入，钳到 0..100） */
    fun retentionPercent(retention: Double): Int =
        (retention * 100.0).roundToInt().coerceIn(0, 100)

    /** 看板曲线标题：把参与统计的单词数直接写进文案，避免空泛描述 */
    fun boardCurveCaption(scheduledTotal: Int, days: Int = 14): String =
        "未来 $days 天预测保留率走势 · 基于 $scheduledTotal 个已排期单词"
}

/** 学习首页状态：今日待复习 / 单词总数 / 已掌握 / 未掌握错题数 + 记忆看板 */
data class StudyHomeUiState(
    val dueCount: Int = 0,
    val totalCount: Int = 0,
    val masteredCount: Int = 0,
    val mistakeCount: Int = 0,
    val dueToday: Int = 0,
    val dueTomorrow: Int = 0,
    val scheduledTotal: Int = 0,
    val predictedRetentionPercent: Int = 0,
    val forgettingCurve: List<Double> = emptyList(),
)

/** 卡片学习会话状态：队列快照 + 当前下标 + 阶段 + 三档自评 + 认识/不认识统计 */
data class CardSessionUi(
    val queue: List<Word> = emptyList(),
    val index: Int = 0,
    val knownCount: Int = 0,
    val unknownCount: Int = 0,
    val phase: CardPhase = CardPhase.RECALL,
    val confidence: Confidence? = null,
    val pretestSkipped: Boolean = false,
    val consecutiveAgain: Int = 0,
) {
    val total: Int get() = queue.size
    val current: Word? get() = queue.getOrNull(index)
    val finished: Boolean get() = index >= queue.size
}

/** 题库练习会话状态：学科 + 题目快照 + 当前题序 + 作答与对错统计 */
data class QuizUiState(
    val subject: String = "",
    val questions: List<Question> = emptyList(),
    val index: Int = 0,
    val selected: Int? = null,
    val correctCount: Int = 0,
) {
    val started: Boolean get() = questions.isNotEmpty()
    val current: Question? get() = questions.getOrNull(index)
    val finished: Boolean get() = started && index >= questions.size
    val total: Int get() = questions.size
    val accuracyPercent: Int
        get() = if (questions.isEmpty()) 0 else correctCount * 100 / questions.size
}

/**
 * 学习模块 ViewModel：学习首页统计、单词列表、卡片学习会话、
 * 题库练习会话、单词/题目录入，全部 StateFlow 驱动。
 */
class StudyViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as StudyKitApp).container
    private val wordRepository = container.wordRepository
    private val questionRepository = container.questionRepository
    private val mistakeRepository = container.mistakeRepository

    companion object {
        private const val SESSION_SIZE = 10
        private const val QUIZ_SIZE = 10
    }

    // ── 学习首页状态 ──────────────────────────────────────────────────────
    val homeState: StateFlow<StudyHomeUiState> = combine(
        wordRepository.observeAll(),
        mistakeRepository.observeUnmasteredCount(),
    ) { words, unmasteredMistakes ->
        val now = System.currentTimeMillis()
        val scheduled = words.filter { it.isScheduled }.map { CardSessionLogic.wordToState(it) }
        val board = ReviewPlanner.board(scheduled, now)
        StudyHomeUiState(
            dueCount = words.count { it.status != Word.STATUS_MASTERED && it.nextReviewAt <= now },
            totalCount = words.size,
            masteredCount = words.count { it.status == Word.STATUS_MASTERED },
            mistakeCount = unmasteredMistakes,
            dueToday = board.dueToday,
            dueTomorrow = board.dueTomorrow,
            scheduledTotal = board.total,
            predictedRetentionPercent = CardSessionLogic.retentionPercent(board.predictedRetention),
            forgettingCurve = CardSessionLogic.forgettingCurve(scheduled, now),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StudyHomeUiState())

    /** 单词列表（单词列表页使用） */
    val words: StateFlow<List<Word>> = wordRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 错题列表（错题 Tab 使用） */
    val mistakes: StateFlow<List<Mistake>> = mistakeRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 题库学科列表（distinct subject） */
    val subjects: StateFlow<List<String>> = questionRepository.observeSubjects()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ── 卡片学习会话 ──────────────────────────────────────────────────────
    private val _session = MutableStateFlow<CardSessionUi?>(null)
    val session: StateFlow<CardSessionUi?> = _session

    /** 组一轮学习队列：今日到期（next_review_at <= now）且未掌握的单词，取前 10 个；首张按其新旧定阶段 */
    fun startCardSession() {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val queue = wordRepository.getAll()
                .filter { it.status != Word.STATUS_MASTERED && it.nextReviewAt <= now }
                .sortedBy { it.nextReviewAt }
                .take(SESSION_SIZE)
            val first = queue.firstOrNull()
            _session.value = CardSessionUi(
                queue = queue,
                phase = if (first != null) CardSessionLogic.startingPhase(first) else CardPhase.RECALL,
            )
        }
    }

    /** 预测试结束：skipped=true 记录为跳过，两种情况都进入 RECALL */
    fun endPretest(skipped: Boolean) {
        val state = _session.value ?: return
        if (state.phase != CardPhase.PRETEST) return
        _session.value = state.copy(
            phase = CardSessionLogic.afterPretest(state.phase, skipped),
            pretestSkipped = state.pretestSkipped || skipped,
        )
    }

    /** RECALL 阶段选定自评信心，翻面进入 ANSWER */
    fun selectConfidence(confidence: Confidence) {
        val state = _session.value ?: return
        if (state.phase != CardPhase.RECALL) return
        _session.value = state.copy(
            confidence = confidence,
            phase = CardSessionLogic.afterConfidence(state.phase),
        )
    }

    /** 四档评分：走引擎排期并落库，推进到下一张并重置阶段 */
    fun rate(rating: Rating) {
        val state = _session.value ?: return
        val word = state.current ?: return
        val confidence = state.confidence ?: Confidence.VAGUE
        val now = System.currentTimeMillis()
        val outcome = CardSessionLogic.schedule(word, rating, confidence, now)
        viewModelScope.launch {
            wordRepository.applyScheduling(
                id = word.id,
                stability = outcome.state.stability,
                difficulty = outcome.state.difficulty,
                reps = outcome.state.reps,
                lapses = outcome.state.lapses,
                lastReviewAt = outcome.state.lastReviewAt,
                confidence = confidence.level,
                status = outcome.status,
                nextReviewAt = outcome.dueAt,
            )
            wordRepository.recordReview(
                wordId = word.id,
                rating = rating,
                confidence = state.confidence,
                stabilityAfter = outcome.state.stability,
                reviewedAt = now,
            )
            val nextIndex = state.index + 1
            val nextWord = state.queue.getOrNull(nextIndex)
            val again = rating == Rating.AGAIN
            _session.value = state.copy(
                index = nextIndex,
                knownCount = state.knownCount + if (again) 0 else 1,
                unknownCount = state.unknownCount + if (again) 1 else 0,
                phase = if (nextWord != null) CardSessionLogic.startingPhase(nextWord) else CardPhase.RECALL,
                confidence = null,
                pretestSkipped = false,
                consecutiveAgain = if (again) state.consecutiveAgain + 1 else 0,
            )
        }
    }

    /** 「认识」旧入口：映射为 GOOD，以中性信心走同一套引擎排期，保持向后兼容 */
    fun markKnown() = legacyRate(known = true)

    /** 「不认识」旧入口：映射为 AGAIN */
    fun markUnknown() = legacyRate(known = false)

    private fun legacyRate(known: Boolean) {
        val state = _session.value ?: return
        if (state.current == null) return
        _session.value = state.copy(confidence = Confidence.VAGUE, phase = CardPhase.ANSWER)
        rate(CardSessionLogic.ratingForLegacy(known))
    }

    // ── 题库练习会话 ──────────────────────────────────────────────────────
    private val _quiz = MutableStateFlow(QuizUiState())
    val quiz: StateFlow<QuizUiState> = _quiz

    /** 选择学科，开始一轮练习（该学科前 10 道题） */
    fun startQuiz(subject: String) {
        viewModelScope.launch {
            val questions = questionRepository.getBySubject(subject, QUIZ_SIZE, 0)
            _quiz.value = QuizUiState(subject = subject, questions = questions)
        }
    }

    /** 重置练习会话（返回学科选择） */
    fun resetQuiz() {
        _quiz.value = QuizUiState()
    }

    /** 选择选项：即时判定，写入练习记录；答错幂等写入错题本 */
    fun selectOption(selected: Int) {
        val state = _quiz.value
        val question = state.current ?: return
        if (state.selected != null) return
        viewModelScope.launch {
            val correct = questionRepository.submitAnswer(question.id, selected)
            if (!correct) {
                addMistakeIfAbsent(question)
            }
            _quiz.value = state.copy(
                selected = selected,
                correctCount = state.correctCount + if (correct) 1 else 0,
            )
        }
    }

    /** 答错入错题本：同一 question_id 已存在（以 note 中的 qid 标记判断）则不重复插入 */
    private suspend fun addMistakeIfAbsent(question: Question) {
        val marker = "qid:${question.id}"
        val exists = mistakeRepository.observeAll().first().any {
            it.source == Mistake.SOURCE_PRACTICE && it.note == marker
        }
        if (exists) return
        val options = parseOptions(question.optionsJson)
        val content = buildString {
            appendLine("题干：${question.stem}")
            options.forEachIndexed { i, option ->
                appendLine("${'A' + i}. $option")
            }
            appendLine("正确答案：${'A' + question.answerIndex}. ${options.getOrNull(question.answerIndex).orEmpty()}")
            append("解析：${question.explanation}")
        }
        mistakeRepository.add(
            source = Mistake.SOURCE_PRACTICE,
            subject = question.subject,
            title = question.stem,
            content = content,
            note = marker,
        )
    }

    /** 进入下一题；越过末尾后由 finished 标记接管显示结果页 */
    fun nextQuestion() {
        val state = _quiz.value
        if (state.selected == null) return
        _quiz.value = state.copy(index = state.index + 1, selected = null)
    }

    // ── 录入 ──────────────────────────────────────────────────────────────
    /** 保存新单词入 words 表 */
    fun saveWord(word: String, meaning: String, example: String, onSaved: () -> Unit) {
        viewModelScope.launch {
            wordRepository.add(word.trim(), meaning.trim(), example.trim())
            toast("已保存单词")
            onSaved()
        }
    }

    /** 保存新题目入 questions 表（options_json 使用 JSONArray 构建） */
    fun saveQuestion(
        subject: String,
        stem: String,
        options: List<String>,
        answerIndex: Int,
        explanation: String,
        onSaved: () -> Unit,
    ) {
        viewModelScope.launch {
            questionRepository.add(
                subject = subject.trim(),
                stem = stem.trim(),
                options = options.map { it.trim() },
                answerIndex = answerIndex,
                explanation = explanation.trim(),
            )
            toast("已保存题目")
            onSaved()
        }
    }

    /** 错题标记为已掌握 */
    fun markMistakeMastered(id: Long) {
        viewModelScope.launch {
            mistakeRepository.markMastered(id)
        }
    }

    private fun toast(message: String) {
        Toast.makeText(getApplication(), message, Toast.LENGTH_SHORT).show()
    }
}
