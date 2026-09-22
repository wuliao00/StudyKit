package com.studykit.ui.study

import android.app.Application
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.studykit.StudyKitApp
import com.studykit.data.entity.Mistake
import com.studykit.data.entity.Question
import com.studykit.data.entity.Word
import com.studykit.data.memory.MemoryModel
import com.studykit.data.memory.MemoryParams
import com.studykit.data.memory.MemoryScheduler
import com.studykit.data.memory.MemoryState
import com.studykit.data.memory.ReviewGrade
import com.studykit.data.memory.ReviewStrictness
import com.studykit.data.memory.Scheduling
import com.studykit.util.OneShotGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/** 解析题目的 options_json（JSONArray 字符串）为选项列表 */
fun parseOptions(optionsJson: String): List<String> = try {
    val array = JSONArray(optionsJson)
    (0 until array.length()).map { array.optString(it) }
} catch (e: Exception) {
    emptyList()
}

/**
 * 学习首页状态：今日待复习 / 单词总数 / 已掌握 / 未掌握错题数 / 连续学习天数 / 今日完成次数
 *
 * [tomorrowCount] 是"明天要复习多少"——把排期的未来摊到用户眼前。
 * 墨墨的学习情况页直接把柱子画到未来 6 天，这是它整套调度能被信任的原因：
 * 用户看得见"今天少背两个，明天就少五个"，而不是一句"坚持下去"。
 */
data class StudyHomeUiState(
    val dueCount: Int = 0,
    val totalCount: Int = 0,
    val masteredCount: Int = 0,
    val mistakeCount: Int = 0,
    val streakDays: Int = 0,
    val todayDone: Int = 0,
    val tomorrowCount: Int = 0,
)

/** 卡片学习会话状态：队列快照 + 当前下标 + 三档评分统计 */
data class CardSessionUi(
    val queue: List<Word> = emptyList(),
    val index: Int = 0,
    val knownCount: Int = 0,
    val vagueCount: Int = 0,
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
    private val settingsRepository = container.settingsRepository

    companion object {
        private val ONE_DAY_MS = TimeUnit.DAYS.toMillis(1)
        private const val SESSION_SIZE = 10
        private const val QUIZ_SIZE = 10

        /** 半衰期到这个天数就打上「已掌握」标签（**只作展示**，不再决定它会不会回到队列） */
        private const val MASTERED_HALF_LIFE_DAYS = 7.0
    }

    /**
     * 本轮生效的调度参数（目标准确率 + 间隔上限），跟着设置页的考试日期与严格度走。
     *
     * 卡片页要拿它算"按这个按钮会排到几天后"并直接印在按钮上，所以是 StateFlow 而不是内部变量。
     */
    private val _scheduling = MutableStateFlow(
        MemoryScheduler.forSettings(ReviewStrictness.AUTO, 0L, LocalDate.now()),
    )
    val scheduling: StateFlow<Scheduling> = _scheduling

    init {
        viewModelScope.launch {
            settingsRepository.settings.collect { s ->
                _scheduling.value = MemoryScheduler.forSettings(
                    strictness = s.reviewStrictness,
                    examEpochDay = s.examEpochDay,
                    today = LocalDate.now(),
                )
            }
        }
    }

    // ── 学习首页状态 ──────────────────────────────────────────────────────
    val homeState: StateFlow<StudyHomeUiState> = combine(
        wordRepository.observeAll(),
        mistakeRepository.observeUnmasteredCount(),
        wordRepository.observeReviewTimestamps(),
        questionRepository.observePracticeTimestamps(),
        wordRepository.observeScheduledTimestamps(),
    ) { words, unmasteredMistakes, reviewTimestamps, practiceTimestamps, scheduled ->
        val now = System.currentTimeMillis()
        // zone/today 各取一次：既用于「今日 0 点」也用于连续天数锚点，避免跨零点时两者不一致
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val dayStart = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val all = reviewTimestamps + practiceTimestamps
        // 「明天要复习多少」的区间：按本地日切，不用 SQL 的 date()（不吃时区）
        val tomorrowStart = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val dayAfterStart = today.plusDays(2).atStartOfDay(zone).toInstant().toEpochMilli()
        StudyHomeUiState(
            // 判据从「未掌握且到期」改成「有排期且到期」：见 WordDao.getDueForReview 的注释
            dueCount = words.count { it.nextReviewAt in 1L..now },
            totalCount = words.size,
            masteredCount = words.count { it.status == Word.STATUS_MASTERED },
            mistakeCount = unmasteredMistakes,
            streakDays = StudyStreak.streakDays(all, zone, today),
            todayDone = all.count { it in dayStart..now },
            tomorrowCount = scheduled.count { it in tomorrowStart until dayAfterStart },
        )
    }
        // Room 的 flowOn 只作用上游，combine 变换（全量时间戳拼接 + HashSet 重建）默认落在
        // stateIn 的收集线程（Main.immediate），显式切到 Default 避免大列表在主线程重建
        .flowOn(context = Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StudyHomeUiState())

    /** 单词列表（单词列表页使用） */
    val words: StateFlow<List<Word>> = wordRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 题库学科列表（distinct subject） */
    val subjects: StateFlow<List<String>> = questionRepository.observeSubjects()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ── 卡片学习会话 ──────────────────────────────────────────────────────
    private val _session = MutableStateFlow<CardSessionUi?>(null)
    val session: StateFlow<CardSessionUi?> = _session

    /** 组一轮学习队列：有排期且已到期的单词，按到期时刻升序取前 10 个 */
    fun startCardSession() {
        // 入口先同步清空：`_session` 是 VM 里的常驻状态，上一轮跑完后它是 `finished` 的小结态。
        // 不等这一步的话，重进页面会先渲染「上一轮已完成 + 彩带」（页面只在下一帧才拿到新队列），
        // 用户看到的是闪一下旧小结（终审 I5）。
        _session.value = null
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val queue = wordRepository.getAll()
                .filter { it.nextReviewAt in 1L..now }
                .sortedBy { it.nextReviewAt }
                .take(SESSION_SIZE)
            _session.value = CardSessionUi(queue = queue)
        }
    }

    fun markKnown() = gradeCard(ReviewGrade.RECALL)

    /** 「模糊」：想起来了但犹豫过。加固照算，难度照涨 —— 不是"半个错" */
    fun markVague() = gradeCard(ReviewGrade.VAGUE)

    fun markUnknown() = gradeCard(ReviewGrade.FORGET)

    /**
     * 评一次分：把半衰期模型走一遍并落库。
     *
     * 取代原来的"答对 +1 天 / +3 天、答错 +10 分钟"写死阶梯 ——
     * 那套阶梯与历史答对次数无关，背到第 20 次仍然只隔 3 天。
     *
     * @param reactionMs 从翻面到按下按钮的毫秒数。只入库供以后校准参考，**不进模型**：
     *                   自我评分的犹豫时长和真实提取时长不是一回事。
     */
    fun gradeCard(grade: ReviewGrade, reactionMs: Long? = null) {
        val state = _session.value ?: return
        val word = state.current ?: return
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val sched = _scheduling.value
            val params = MemoryParams()
            val before = MemoryState(word.halfLifeDays, word.difficulty)
            // 从没复习过的词拿"加入学习"那天当锚点：Δt=0 会让第一次评分的加固量归零
            // （成功支里 (1−p)^0.970 在 p=1 时被夹到 1e-3，间隔效应直接消失）
            val anchor = word.lastReviewAt ?: word.createdAt
            val gapDays = (now - anchor).coerceAtLeast(0L) / ONE_DAY_MS.toDouble()

            val predicted = MemoryModel.recallProbability(gapDays, before.halfLifeDays)
            val after = MemoryModel.update(before, gapDays, grade, params)
            val days = MemoryModel.schedule(after, grade, sched.targetRecall, sched.maxIntervalDays, params)
            val status = when {
                grade == ReviewGrade.FORGET -> Word.STATUS_LEARNING
                after.halfLifeDays >= MASTERED_HALF_LIFE_DAYS -> Word.STATUS_MASTERED
                else -> Word.STATUS_LEARNING
            }

            // 顺序不能反：先写状态、再写历史。中间被杀进程只丢一条历史记录（下次复习时刻仍对）；
            // 反过来会留下"历史里有一次评分、但半衰期没涨"的行，那是在污染以后的校准样本。
            wordRepository.applyReview(
                wordId = word.id,
                halfLifeDays = after.halfLifeDays,
                difficulty = after.difficulty,
                status = status,
                nextReviewAt = now + (days * ONE_DAY_MS).toLong().coerceAtLeast(TimeUnit.MINUTES.toMillis(5)),
                lastReviewAt = now,
                lapseInc = if (grade == ReviewGrade.FORGET) 1 else 0,
            )
            wordRepository.recordGradedReview(
                wordId = word.id,
                grade = grade.ordinal,
                correct = grade != ReviewGrade.FORGET,
                gapDays = gapDays,
                pAtReview = predicted,
                hBefore = before.halfLifeDays,
                hAfter = after.halfLifeDays,
                reactionMs = reactionMs,
            )
            _session.value = state.copy(
                index = state.index + 1,
                knownCount = state.knownCount + if (grade == ReviewGrade.RECALL) 1 else 0,
                vagueCount = state.vagueCount + if (grade == ReviewGrade.VAGUE) 1 else 0,
                unknownCount = state.unknownCount + if (grade == ReviewGrade.FORGET) 1 else 0,
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
    // 两个录入入口各一枚门（终审 C4）：本页「保存」是写完即 pop 的一次性动作，
    // 连点两次会双插库 + 双 pop。门开在 VM 上而不是页面的 `enabled`，六个入口才只用一套机制。
    private val savingWord = OneShotGate()
    private val savingQuestion = OneShotGate()

    /** 保存新单词入 words 表 */
    fun saveWord(word: String, meaning: String, example: String, onSaved: () -> Unit) {
        if (!savingWord.tryEnter()) return
        viewModelScope.launch {
            try {
                wordRepository.add(word.trim(), meaning.trim(), example.trim())
                toast("已保存单词")
                onSaved()
            } finally {
                savingWord.leave()
            }
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
        if (!savingQuestion.tryEnter()) return
        viewModelScope.launch {
            try {
                questionRepository.add(
                    subject = subject.trim(),
                    stem = stem.trim(),
                    options = options.map { it.trim() },
                    answerIndex = answerIndex,
                    explanation = explanation.trim(),
                )
                toast("已保存题目")
                onSaved()
            } finally {
                savingQuestion.leave()
            }
        }
    }

    private fun toast(message: String) {
        Toast.makeText(getApplication(), message, Toast.LENGTH_SHORT).show()
    }
}
