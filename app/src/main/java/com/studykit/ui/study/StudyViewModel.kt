package com.studykit.ui.study

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.studykit.StudyKitApp
import com.studykit.data.entity.Mistake
import com.studykit.data.entity.Question
import com.studykit.data.entity.Word
import com.studykit.data.memory.Confidence
import com.studykit.data.memory.Hypercorrection
import com.studykit.data.memory.MemoryScheduler
import com.studykit.data.memory.ReviewGrade
import com.studykit.data.memory.ReviewStrictness
import com.studykit.data.memory.Scheduling
import com.studykit.data.memory.fsrsDifficultyFromHalfLife
import com.studykit.data.memory.halfDifficultyFromFsrs
import com.studykit.data.memory.kernelStateFor
import com.studykit.data.memory.recallForDisplay
import com.studykit.data.memory.toKernelRating
import com.studykit.ui.mistake.MistakeIntake
import com.studykit.util.OneShotGate
import com.studykit.util.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.zip
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlin.math.min

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
 * [tomorrow] 是"明天要复习多少 + 到时候大约还记得多少"——把排期的未来摊到用户眼前。
 * 墨墨的学习情况页直接把柱子画到未来 6 天，这是它整套调度能被信任的原因：
 * 用户看得见"今天少背两个，明天就少五个"，而不是一句"坚持下去"。
 *
 * 词数与保留率刻意合成**一个** [TomorrowLoad] 而不是两个 Int 字段：那一行上两个数是
 * 同一批词的两个说法，分成两个字段就能被两处代码各自算一遍，算出"12 词 · 记得 87%"
 * 却数的是两批词 —— 那种错不报错，只是整行数字没意义（v2.5 §2.3）。
 */
data class StudyHomeUiState(
    val dueCount: Int = 0,
    val totalCount: Int = 0,
    val masteredCount: Int = 0,
    val mistakeCount: Int = 0,
    val streakDays: Int = 0,
    val todayDone: Int = 0,
    val tomorrow: TomorrowLoad = TomorrowLoad(wordCount = 0, sampledCount = 0, predictedRecallPercent = 0),
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
 * 模考会话状态（v2.7 计划 B Task 13）：一次性持有整卷题、逐题只记所选、交卷前不产出对错。
 *
 * 与 [QuizUiState] 的关键差别：练习模式每题答完就地判定（`selected` / `correctCount` 边答边涨），
 * 这里 `answers` 只是"第几题选了哪个"，对错与反馈全部压到 [submitMockExam] 那一刻，
 * 算完才填进 [results]（[MockExamState] 的产物）。交卷前 `results` 为空、`correctCount` 恒 0，
 * 页面也就无从泄露判词。
 */
data class MockExamUiState(
    val subject: String = "",
    val questions: List<Question> = emptyList(),
    val answers: Map<Long, Int> = emptyMap(),
    val index: Int = 0,
    val submitted: Boolean = false,
    val results: Map<Long, MockExamState.Answer> = emptyMap(),
) {
    val started: Boolean get() = questions.isNotEmpty()
    val total: Int get() = questions.size
    val current: Question? get() = questions.getOrNull(index)
    val answeredCount: Int get() = answers.size
    val correctCount: Int
        get() = if (submitted) questions.count { results[it.id]?.correct == true } else 0
    val accuracyPercent: Int
        get() = if (total == 0) 0 else correctCount * 100 / total
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

        /** 模考整卷题量：比一轮练习更长一点，取数仍走现有 `getBySubject`（库里没有就有多少出多少） */
        private const val MOCK_EXAM_SIZE = 20

        /**
         * 到这个天数就打上「已掌握」标签（**只作展示**，不再决定它会不会回到队列）。
         *
         * v2.7 双内核之后这个名字只有一半字面是真的：
         *  - 跑半衰期时它量的仍是**半衰期天数**；
         *  - 跑 FSRS 时它量的是**实际排出的下次间隔天数**（那一侧 `hDays` 是 ×12.79 的镜像读数，
         *    直接喂这个阈值会“一次评分即已掌握”，A-T3 复审遗留决定，消费点见 [gradeCard]）。
         * 不改名是为了不把 diff 扩到所有读数点上。
         */
        private const val MASTERED_HALF_LIFE_DAYS = 7.0

        /**
         * 预测试干扰项池的条数：需要 2 条，多捞几条当余量 ——
         * 纯函数那侧还会按文本再收一次（掐首尾空白、丢掉与正确项同文的），
         * 刚好捞 2 条时任何一条脏数据都会把这道题判成"凑不满三条"而整轮跳过。
         */
        private const val PRETEST_POOL_SIZE = 5
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
    // 明日那一格取的是 `observeScheduledMemoryRows()` 一条 SQL 里的那几个字段：
    // 词数与保留率必须出自同一批行，所以这里不能退回 `observeScheduledTimestamps()` +
    // `observeHalfLifeDays()` 两次查询再配对（理由见那条投影查询的 KDoc）。
    // 活跃内核 id 也进 combine：设置页切了内核，这一格必须**当场**按新内核重算，
    // 不能等下一次数据变化才跟上 —— 切内核不会写 words 表，只挂数据的流根本不会重发。
    val homeState: StateFlow<StudyHomeUiState> = combine(
        wordRepository.observeAll(),
        mistakeRepository.observeUnmasteredCount(),
        wordRepository.observeReviewTimestamps(),
        questionRepository.observePracticeTimestamps(),
        wordRepository.observeScheduledMemoryRows()
            // 只跟踪内核 id 这一件事：换主题/改玻璃都不该重算整页首页（A-T9 双审 Minor）
            .zip(settingsRepository.settings.map { it.schedulingKernel }.distinctUntilChanged()) { rows, kernelId ->
                rows to kernelId
            },
    ) { words, unmasteredMistakes, reviewTimestamps, practiceTimestamps, (scheduled, kernelId) ->
        val now = System.currentTimeMillis()
        // zone/today 各取一次：既用于「今日 0 点」也用于连续天数锚点，避免跨零点时两者不一致
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val dayStart = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val all = reviewTimestamps + practiceTimestamps
        // 「明天」的区间按本地日切，不用 SQL 的 date()（不吃时区）；
        // 这一格只算一次，词数和保留率都从它来
        val tomorrow = TomorrowForecast.of(
            scheduled, TomorrowForecast.window(zone, today), KernelHub.forId(kernelId),
        )
        StudyHomeUiState(
            // 判据从「未掌握且到期」改成「有排期且到期」：见 WordDao.getDueForReview 的注释
            dueCount = words.count { it.nextReviewAt in 1L..now },
            totalCount = words.size,
            masteredCount = words.count { it.status == Word.STATUS_MASTERED },
            mistakeCount = unmasteredMistakes,
            streakDays = StudyStreak.streakDays(all, zone, today),
            todayDone = all.count { it in dayStart..now },
            tomorrow = tomorrow,
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
        // 上一轮的预测试题一起清零：新session 里同一个词会重新出题（词库可能已经变了），
        // 留着旧题只会让页面先画一帧过期选项
        _pretest.value = null
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val queue = wordRepository.getAll()
                .filter { it.nextReviewAt in 1L..now }
                .sortedBy { it.nextReviewAt }
                .take(SESSION_SIZE)
            _session.value = CardSessionUi(queue = queue)
        }
    }

    /**
     * 滑动/两颗按钮那两档快判（右=认识、左=忘记）。
     * [conf] 由计划 B 的信心行带入（翻面后、评分前采集）；null = 用户没选，走原口径（不触发超纠正、confidence 落 NULL）。
     */
    fun markKnown(conf: Confidence? = null) = gradeCard(ReviewGrade.RECALL, conf = conf)

    /** 「模糊」：想起来了但犹豫过。加固照算，难度照涨 —— 不是"半个错" */
    fun markVague(conf: Confidence? = null) = gradeCard(ReviewGrade.VAGUE, conf = conf)

    fun markUnknown(conf: Confidence? = null) = gradeCard(ReviewGrade.FORGET, conf = conf)

    /**
     * 评一次分：把**当前活跃内核**走一遍并落库。
     *
     * 取代原来的"答对 +1 天 / +3 天、答错 +10 分钟"写死阶梯 ——
     * 那套阶梯与历史答对次数无关，背到第 20 次仍然只隔 3 天。
     *
     * @param reactionMs 从翻面到按下按钮的毫秒数。只入库供以后校准参考，**不进模型**：
     *                   自我评分的犹豫时长和真实提取时长不是一回事。
     * @param conf 用户自评信心，计划 B 的信心行传入；null = 没采集。
     *             超纠正侧信道只在 `SURE × 未忆起` 触发，所以 null 一律不干预；
     *             落库的 `confidence` 也就留 null —— 拿 0 冒充「瞎猜」是假数据。
     */
    fun gradeCard(grade: ReviewGrade, reactionMs: Long? = null, conf: Confidence? = null) {
        val state = _session.value ?: return
        val word = state.current ?: return
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val sched = _scheduling.value
            // 内核每轮会话现取，且与评分按钮预览同一个来源（AppTheme.settings.schedulingKernel）：
            // 按钮上印的间隔与点下去真排出的间隔不能分家（KernelHub 的 KDoc）
            val kernel = KernelHub.forId(settingsRepository.current().schedulingKernel)
            val before = kernelStateFor(kernel, word)
            // 从没复习过的词拿"加入学习"那天当锚点：Δt=0 会让第一次评分的加固量归零
            // （成功支里 (1−p)^0.970 在 p=1 时被夹到 1e-3，间隔效应直接消失）
            val anchor = word.lastReviewAt ?: word.createdAt
            val gapDays = (now - anchor).coerceAtLeast(0L) / ONE_DAY_MS.toDouble()

            val rating = grade.toKernelRating()
            val predicted = recallForDisplay(kernel, before, gapDays)
            val after = kernel.review(before, gapDays, rating, conf)
            val days = kernel.nextIntervalDays(after, rating, sched.targetRecall, sched.maxIntervalDays)
            // 「已掌握」的读数按活跃内核分岔（A-T3 复审遗留决定）：FSRS 那侧 after.hDays 是
            // ×12.79 的镜像读数，喂进 7 天阈值会"一次评分即已掌握"，所以看**真排出去的间隔**；
            // 半衰期那侧继续看半衰期，与 v2.6 逐比特一致
            val status = when {
                grade == ReviewGrade.FORGET -> Word.STATUS_LEARNING
                kernel.id == "HALF_LIFE" ->
                    if ((after.hDays ?: word.halfLifeDays) >= MASTERED_HALF_LIFE_DAYS) {
                        Word.STATUS_MASTERED
                    } else {
                        Word.STATUS_LEARNING
                    }
                else ->
                    if (days >= MASTERED_HALF_LIFE_DAYS) Word.STATUS_MASTERED else Word.STATUS_LEARNING
            }
            // 排期先算完，再问侧信道要不要提前：min 只允许把时刻往前拉，
            // 侧信道无权把用户已经排好的间隔推后
            val scheduledAt = now + (days * ONE_DAY_MS).toLong().coerceAtLeast(TimeUnit.MINUTES.toMillis(5))
            val retestMinutes = Hypercorrection.retestDelayMinutes(conf, recalled = grade != ReviewGrade.FORGET)
            val nextReviewAt = if (retestMinutes == null) {
                scheduledAt
            } else {
                min(scheduledAt, now + TimeUnit.MINUTES.toMillis(retestMinutes))
            }

            // 顺序不能反：先写状态、再写历史。中间被杀进程只丢一条历史记录（下次复习时刻仍对）；
            // 反过来会留下"历史里有一次评分、但半衰期没涨"的行，那是在污染以后的校准样本。
            wordRepository.applyReview(
                wordId = word.id,
                // 双写（spec §2.1）：活跃内核那一侧是原生值，另一侧是适配器算出的换算镜像；
                // 两列都来自同一个 after，不留"半新半旧"的镜像
                halfLifeDays = after.hDays ?: word.halfLifeDays,
                // 列量纲固定（A-T9 终审）：words.difficulty 永远存半衰期口径那一份——
                // FSRS 活跃时 after.difficulty 是 FSRS 的 1..10，必须过逆映射再入库，
                // 否则切回 HALF_LIFE 会拿 FSRS 数当 halfD 喂模型（kernelStateFor 无条件读它）
                difficulty = if (kernel.id == "HALF_LIFE") after.difficulty else halfDifficultyFromFsrs(after.difficulty),
                status = status,
                nextReviewAt = nextReviewAt,
                lastReviewAt = now,
                lapseInc = if (grade == ReviewGrade.FORGET) 1 else 0,
                fsrsStability = after.stability,
                // words.difficulty 存的永远是活跃内核自己那份（量纲归属见 KernelState KDoc），
                // fsrs_difficulty 必须是 FSRS 口径：跑半衰期时在这里折算一次（与 MIGRATION_6_7 同式）
                fsrsDifficulty = if (kernel.id == "HALF_LIFE") {
                    fsrsDifficultyFromHalfLife(after.difficulty)
                } else {
                    after.difficulty
                },
                fsrsState = after.cardState.ordinal + 1,
                kernel = kernel.id,
            )
            wordRepository.recordGradedReview(
                wordId = word.id,
                grade = grade.ordinal,
                correct = grade != ReviewGrade.FORGET,
                gapDays = gapDays,
                pAtReview = predicted,
                hBefore = before.hDays ?: word.halfLifeDays,
                hAfter = after.hDays ?: word.halfLifeDays,
                reactionMs = reactionMs,
                confidence = conf?.ordinal?.plus(1),
                fsrsRating = rating.ordinal + 1,
            )
            _session.value = state.copy(
                index = state.index + 1,
                knownCount = state.knownCount + if (grade == ReviewGrade.RECALL) 1 else 0,
                vagueCount = state.vagueCount + if (grade == ReviewGrade.VAGUE) 1 else 0,
                unknownCount = state.unknownCount + if (grade == ReviewGrade.FORGET) 1 else 0,
            )
        }
    }

    // ── 新词预测试（v2.5 §3.3）─────────────────────────────────────────────
    private val _pretest = MutableStateFlow<RecallPretest?>(null)

    /**
     * 当前这张卡的预测试题。null = 不做预测试，页面直接进原有的翻面+评分流程
     * （闸门关着 / 不是新词 / 凑不出 3 条互不相同的释义，三种都算"整轮跳过"）。
     *
     * 题面自带 `wordId`：切卡很快时上一张的题必须挂不到这一张上，页面按那一列判命中。
     */
    val pretest: StateFlow<RecallPretest?> = _pretest

    /**
     * 该出题就出题，不该出就把这一格清零（清零也要做：上一张新词的题不能留在流里等下一张卡捡）。
     *
     * ## 猜的结果一律不落库
     * 这里**只读**词库，不写 `word_reviews`、不写任何表。
     * `word_reviews` 的 `gapDays / pAtReview / hBefore / hAfter` 是以后校准模型的样本，
     * 而"猜三选一"不是对已存记忆的提取，混进去样本语义就脏了；
     * 而且 [gradeCard] 走的 `applyReview` 会把 `last_review_at` 往前推，
     * 新词第二次评分的 Δt 锚点会跟着错。[gradeCard] 里"先写状态、再写历史"那段注释
     * 防的就是同一类污染，这条路径干脆一个字都不写。
     */
    fun loadRecallPretest(word: Word, gateEnabled: Boolean) {
        if (!isPretestCandidate(word = word, gateEnabled = gateEnabled)) {
            _pretest.value = null
            return
        }
        viewModelScope.launch {
            val pool = runCatching {
                wordRepository.recallPretestPool(
                    sourceListId = word.sourceListId,
                    wordId = word.id,
                    excludeMeaning = word.meaning,
                    count = PRETEST_POOL_SIZE,
                )
            }.getOrDefault(emptyList())
            // 只在这张卡还在当前位时发布：滑得很快时上一张的查询可能后到，
            // 覆盖了这一张的题等于把这一张的预测试悄悄关掉。
            if (_session.value?.current?.id == word.id) {
                _pretest.value = buildRecallPretest(word = word, pool = pool)
            }
        }
    }

    /**
     * 闸门那条一次性说明：点「知道了」时置真，此后每次安装都不再出现。
     *
     * 写失败不提示也不重投：后果只是"下次拦下时还会再说明一次"，
     * 而这条路径是用户正打算继续学习的时候，弹一条 toast 只会打断他。
     */
    fun markRecallGateHintSeen() {
        viewModelScope.launch {
            runCatching { settingsRepository.update { it.copy(recallGateHintSeen = true) } }
        }
    }

    /**
     * 翻面时那条 RECALL_FIRST 贴士：首次翻面出现时置真，此后每次安装都不再出现。
     *
     * 与 [markRecallGateHintSeen] 同一条纪律：写失败不提示也不重投 —— 后果只是"下次翻面还会
     * 再提一次"，而这条路径是用户正打算继续学习的时候，弹一条 toast 只会打断他。
     */
    fun markFlipTipSeen() {
        viewModelScope.launch {
            runCatching { settingsRepository.update { it.copy(flipTipSeen = true) } }
        }
    }

    // ── 题库练习会话 ──────────────────────────────────────────────────────
    private val _quiz = MutableStateFlow(QuizUiState())
    val quiz: StateFlow<QuizUiState> = _quiz

    /** 选择学科，开始一轮练习（该学科前 10 道题） */
    fun startQuiz(subject: String) {
        viewModelScope.launch {
            // `AppSettings.interleavingEnabled` 的消费点（设置页「每条设置必须有消费点」的规矩）：
            // 读一次、传进纯函数那一处判定。单科时 interleaveBySubject 原样退回（交错对单一材料无意义）。
            val interleaving = settingsRepository.current().interleavingEnabled
            val questions = questionRepository.getBySubject(subject, QUIZ_SIZE, 0)
            _quiz.value = QuizUiState(
                subject = subject,
                questions = interleaveBySubject(questions, enabled = interleaving),
            )
        }
    }

    /**
     * 综合练习：跨学科组一轮（每个已录入学科先取若干条，合并后按学科打散再截前 [QUIZ_SIZE] 道）。
     *
     * 这是交错真正生效的入口 —— 单科轮永远是原序，只有这一轮里多学科才谈得上「不连续多题同科」。
     * 取数只用现有的 `getBySubject` / `observeSubjects`，**不新增仓库方法、不碰 Room schema**。
     */
    fun startMixedQuiz() {
        viewModelScope.launch {
            val interleaving = settingsRepository.current().interleavingEnabled
            val subjects = questionRepository.observeSubjects().first()
            // 各科给同样的余量再合并：交错要的是"轮流动"，先把每科各自的前若干条捞齐
            val pool = subjects.flatMap { questionRepository.getBySubject(it, QUIZ_SIZE, 0) }
            val ordered = interleaveBySubject(pool, enabled = interleaving)
            _quiz.value = QuizUiState(
                subject = MIXED_SUBJECT_LABEL,
                questions = ordered.take(QUIZ_SIZE),
            )
        }
    }

    /** 重置练习会话（返回学科选择） */
    fun resetQuiz() {
        _quiz.value = QuizUiState()
    }

    /**
     * 写交错开关（[com.studykit.data.AppSettings.interleavingEnabled]）。
     * 消费点在 [startQuiz] / [startMixedQuiz] 组轮次时读它，判定落在 `interleaveBySubject` 一处。
     * 与 `markRecallGateHintSeen` 同一条纪律：写失败不打断学习，也不重投。
     */
    fun setInterleavingEnabled(on: Boolean) {
        viewModelScope.launch {
            runCatching { settingsRepository.update { it.copy(interleavingEnabled = on) } }
        }
    }

    /**
     * 选择选项：即时判定，写入练习记录；答错幂等写入错题本。
     *
     * [conf] 由答题页信心条在**提交前**采集（计划 B Task 12）；null = 没选或关掉开关。
     * 它一次喂两处：`practice_records.confidence`（1..3/null）与答错入本的 `mistakes.priority`（经 [MistakeIntake]）。
     */
    fun selectOption(selected: Int, conf: Confidence? = null) {
        val state = _quiz.value
        val question = state.current ?: return
        if (state.selected != null) return
        viewModelScope.launch {
            val correct = questionRepository.submitAnswer(question.id, selected, conf?.ordinal?.plus(1))
            if (!correct) {
                addMistakeIfAbsent(question, conf)
            }
            _quiz.value = state.copy(
                selected = selected,
                correctCount = state.correctCount + if (correct) 1 else 0,
            )
        }
    }

    /** 答错入错题本：同一 question_id 已存在（以 note 中的 qid 标记判断）则不重复插入；priority 取超纠正命中档 */
    private suspend fun addMistakeIfAbsent(question: Question, conf: Confidence?) {
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
            // 高置信答错（SURE × 错）= 超纠正机会，置顶；其余普通档。判定只在 MistakeIntake 一处。
            priority = MistakeIntake.priorityFor(conf, correct = false),
        )
    }

    /** 进入下一题；越过末尾后由 finished 标记接管显示结果页 */
    fun nextQuestion() {
        val state = _quiz.value
        if (state.selected == null) return
        _quiz.value = state.copy(index = state.index + 1, selected = null)
    }

    // ── 模考会话（v2.7 计划 B Task 13）──────────────────────────────────────
    private val _mockExam = MutableStateFlow(MockExamUiState())
    val mockExam: StateFlow<MockExamUiState> = _mockExam

    /**
     * 组一卷模拟卷：与 [startQuiz] 同一取数口径（该学科前 [MOCK_EXAM_SIZE] 题、读一次交错开关
     * 交给 [interleaveBySubject] 判定），差别只在这一卷**一次性拿全**、作答期间不再回源分页读库。
     */
    fun startMockExam(subject: String) {
        viewModelScope.launch {
            val interleaving = settingsRepository.current().interleavingEnabled
            val questions = questionRepository.getBySubject(subject, MOCK_EXAM_SIZE, 0)
            _mockExam.value = MockExamUiState(
                subject = subject,
                questions = interleaveBySubject(questions, enabled = interleaving),
            )
        }
    }

    /** 重置模考会话（返回学科选择 / 退出模考） */
    fun resetMockExam() {
        _mockExam.value = MockExamUiState()
    }

    /**
     * 记一次作答：只把"这题选了哪个"写进会话，**不判对错、不落库、不产反馈**。
     * 交卷前允许改答案（后写覆盖前写）；已交卷或整卷已跳走则忽略（终态锁死）。
     */
    fun answerMockExam(questionId: Long, selected: Int) {
        val state = _mockExam.value
        if (state.submitted) return
        if (state.questions.none { it.id == questionId }) return
        _mockExam.value = state.copy(answers = state.answers + (questionId to selected))
    }

    /** 在卷内前后翻题（交卷前自由导航）；越界夹到首 / 末题，不会崩 */
    fun gotoMockExam(index: Int) {
        val state = _mockExam.value
        if (state.submitted || state.total == 0) return
        _mockExam.value = state.copy(index = index.coerceIn(0, state.total - 1))
    }

    /**
     * 交卷：先用 [MockExamState] 把整卷一次性算成每题 (selected, correct) 并同步翻起 submitted
     *（页面当场跳成绩页），再把已作答的题走**与练习同一条落库路径**写练习记录（conf=null），
     * 答错的题走现成 [addMistakeIfAbsent] 幂等入错题本（priority 取 [MistakeIntake]，conf=null → 普通档 0）。
     *
     * 双写只发生一次：模考作答从不碰 [selectOption]，本方法又有 `if (submitted) return` 终态锁，
     * 未作答的题压根不进落库循环，因此不会出现记录或错题的二次入库。
     */
    fun submitMockExam() {
        val state = _mockExam.value
        if (!state.started || state.submitted) return
        val answerKey = state.questions.associate { it.id to it.answerIndex }
        val answerCount = state.questions.firstOrNull()?.let { parseOptions(it.optionsJson).size } ?: 0
        val machine = MockExamState(
            questionIds = state.questions.map { it.id },
            answerCount = answerCount,
            answerKey = answerKey,
        )
        state.answers.forEach { (questionId, selected) -> machine.answer(questionId, selected) }
        val results = machine.submit()
        // 先冻结 UI 终态（同步），成绩页立刻能读 results；落库在下面的协程里异步补
        _mockExam.value = state.copy(submitted = true, results = results)
        val answered = state.questions.filter { state.answers.containsKey(it.id) }
        viewModelScope.launch {
            answered.forEach { question ->
                val selected = state.answers.getValue(question.id)
                // 与练习同一 submitAnswer，只是 conf 传 null（模考不采集信心 → confidence 落 NULL）
                val correct = questionRepository.submitAnswer(question.id, selected)
                if (!correct) addMistakeIfAbsent(question, conf = null)
            }
        }
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
}
