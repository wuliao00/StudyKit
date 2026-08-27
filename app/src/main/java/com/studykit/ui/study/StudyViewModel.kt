package com.studykit.ui.study

import android.app.Application
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.studykit.StudyKitApp
import com.studykit.data.entity.Mistake
import com.studykit.data.entity.Question
import com.studykit.data.entity.Word
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.util.concurrent.TimeUnit

/** 解析题目的 options_json（JSONArray 字符串）为选项列表 */
fun parseOptions(optionsJson: String): List<String> = try {
    val array = JSONArray(optionsJson)
    (0 until array.length()).map { array.optString(it) }
} catch (e: Exception) {
    emptyList()
}

/** 学习首页状态：今日待复习 / 单词总数 / 已掌握 / 未掌握错题数 */
data class StudyHomeUiState(
    val dueCount: Int = 0,
    val totalCount: Int = 0,
    val masteredCount: Int = 0,
    val mistakeCount: Int = 0,
)

/** 卡片学习会话状态：队列快照 + 当前下标 + 认识/不认识统计 */
data class CardSessionUi(
    val queue: List<Word> = emptyList(),
    val index: Int = 0,
    val knownCount: Int = 0,
    val unknownCount: Int = 0,
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
        private val ONE_DAY_MS = TimeUnit.DAYS.toMillis(1)
        private val RETRY_MS = TimeUnit.MINUTES.toMillis(10)
        private const val SESSION_SIZE = 10
        private const val QUIZ_SIZE = 10
    }

    // ── 学习首页状态 ──────────────────────────────────────────────────────
    val homeState: StateFlow<StudyHomeUiState> = combine(
        wordRepository.observeAll(),
        mistakeRepository.observeUnmasteredCount(),
    ) { words, unmasteredMistakes ->
        val now = System.currentTimeMillis()
        StudyHomeUiState(
            dueCount = words.count { it.status != Word.STATUS_MASTERED && it.nextReviewAt <= now },
            totalCount = words.size,
            masteredCount = words.count { it.status == Word.STATUS_MASTERED },
            mistakeCount = unmasteredMistakes,
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

    /** 组一轮学习队列：今日到期（next_review_at <= now）且未掌握的单词，取前 10 个 */
    fun startCardSession() {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val queue = wordRepository.getAll()
                .filter { it.status != Word.STATUS_MASTERED && it.nextReviewAt <= now }
                .sortedBy { it.nextReviewAt }
                .take(SESSION_SIZE)
            _session.value = CardSessionUi(queue = queue)
        }
    }

    /** 「认识」：状态推进（NEW→LEARNING→MASTERED），间隔顺延 +1 天 / +3 天 */
    fun markKnown() = answerCard(correct = true)

    /** 「不认识」：保持状态，10 分钟后再复习 */
    fun markUnknown() = answerCard(correct = false)

    private fun answerCard(correct: Boolean) {
        val state = _session.value ?: return
        val word = state.current ?: return
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            if (correct) {
                val nextStatus = if (word.status == Word.STATUS_NEW) {
                    Word.STATUS_LEARNING
                } else {
                    Word.STATUS_MASTERED
                }
                val interval = if (word.status == Word.STATUS_NEW) ONE_DAY_MS else ONE_DAY_MS * 3
                wordRepository.updateStatus(word.id, nextStatus, now + interval)
            } else {
                val keepStatus = if (word.status == Word.STATUS_MASTERED) {
                    Word.STATUS_LEARNING
                } else {
                    word.status
                }
                wordRepository.updateStatus(word.id, keepStatus, now + RETRY_MS)
            }
            wordRepository.recordReview(word.id, correct)
            _session.value = state.copy(
                index = state.index + 1,
                knownCount = state.knownCount + if (correct) 1 else 0,
                unknownCount = state.unknownCount + if (correct) 0 else 1,
            )
        }
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
