package com.studykit.ui.study

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.studykit.StudyKitApp
import com.studykit.data.entity.Mistake
import com.studykit.data.entity.MockSession
import com.studykit.data.entity.Question
import com.studykit.data.entity.ReviewLog
import com.studykit.data.entity.Word
import com.studykit.data.memory.Confidence
import com.studykit.data.memory.Hypercorrection
import com.studykit.data.memory.KernelRating
import com.studykit.data.memory.ReviewGrade
import com.studykit.data.memory.Scheduling
import com.studykit.data.memory.fsrsDifficultyFromHalfLife
import com.studykit.data.memory.halfDifficultyFromFsrs
import com.studykit.data.memory.kernelStateFor
import com.studykit.data.memory.recallForDisplay
import com.studykit.data.memory.toKernelRating
import com.studykit.data.memory.toStorageInt
import com.studykit.ui.mistake.MistakeIntake
import com.studykit.util.OneShotGate
import com.studykit.util.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.zip
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
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
 *
 * v3.0-B T11 起这一份是**可持久化**的会话（`mock_sessions` 表）：[sessionId] 是那行的主键，
 * [examName] 是开卷那一刻从考试清单盖上的名字（清单后来改了也不回头改历史，计划 D2），
 * [deadlineTs] / [paused] / [nowTs] 三个原始数撑起计时 —— 页面上那串 `mm:ss` 由纯函数
 * [remainingLabel](`nowTs`, `deadlineTs`) 现算，**不在这份状态里再放一个同名派生属性**：
 * 属性与函数同名会自引用，而且计时读数一旦有两个来源，屏幕与落库就会各说一套。
 * [autoSubmitted] 是「时间到，钟替他按的交卷」（R3），结果页据此标注，且写进 `result` 那一列。
 */
data class MockExamUiState(
    val subject: String = "",
    val questions: List<Question> = emptyList(),
    val answers: Map<Long, Int> = emptyMap(),
    val index: Int = 0,
    val submitted: Boolean = false,
    val results: Map<Long, MockExamState.Answer> = emptyMap(),
    val sessionId: Long? = null,
    val examName: String = "",
    val deadlineTs: Long? = null,
    val paused: Boolean = false,
    val nowTs: Long = 0L,
    val toGrade: List<Long> = emptyList(),
    val autoSubmitted: Boolean = false,
    /**
     * 这一次暂停的起点（评审 F1）：`resumeMockExam` 按它补回停表那一段，暂停卡上那句「剩余」也按它
     * 预先演一遍恢复的结果。null = 没在暂停中。跨进程恢复时它来自 `mock_sessions.paused_at`。
     */
    val pausedAtTs: Long? = null,
    /**
     * 设置里的「暂停是否扣时」（`examPauseCountsTime`），开卷与恢复时各读一次，只喂暂停卡上那句读数。
     * 恢复那一刻仍回库里读**当时**的值（用户可能在暂停期间改过设置），这一份不是判据、只是预览。
     */
    val pauseCountsTime: Boolean = false,
) {
    val started: Boolean get() = questions.isNotEmpty()
    val total: Int get() = questions.size
    val current: Question? get() = questions.getOrNull(index)
    val answeredCount: Int get() = answers.size

    /**
     * **判过**的题数（v3.0-B T12，R2）：交卷后 = [results] 的条数，未答的那些在 [toGrade] 里、
     * 压根没有判词。分数的分母用它，不用 [total]。
     */
    val gradedCount: Int get() = results.size

    /** 判过的题里对了几题（交卷前恒 0） */
    val correctCount: Int
        get() = if (submitted) results.values.count { it.correct } else 0

    /** 答**错**的题数（不是"没答的 + 答错的"）—— 结果页那句「错了 N 道，去建卡」用它 */
    val wrongCount: Int
        get() = if (submitted) gradedCount - correctCount else 0

    /**
     * 正确率。分母是 [gradedCount]（判过的题），**不是** [total]。
     *
     * 这不是措辞问题而是诚实问题：20 题只答 1 题、且答对，旧口径会印「5%」——那是把 19 个
     * 没发生过的作答当成 19 次遗忘，屏幕上那个数字会在用户自己的记忆水平上撒一个难看的谎。
     */
    val accuracyPercent: Int
        get() = if (gradedCount == 0) 0 else correctCount * 100 / gradedCount
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
    private val reviewLogRepository = container.reviewLogRepository

    /** 套卷会话持久化（v3.0-B T11）：`mock_sessions` 表那一行的唯一写入口在下面 `persistMockSession` */
    private val mockSessionRepository = container.mockSessionRepository

    /**
     * v2.9 Task 6：背词与练习两条评分点共用的**粗粒度会话键**（每个 VM 实例一个，同一屏幕内的多次评分
     * 共享）。review_log 的 `session_id` 供 Task 7 统计会话长度 / 同会话连点去重分组用；两类写入路径靠
     * `origin`（word / practice）区分，不靠 session 键。
     */
    private val sessionId: String = UUID.randomUUID().toString()

    companion object {
        private val ONE_DAY_MS = TimeUnit.DAYS.toMillis(1)
        private const val SESSION_SIZE = 10
        private const val QUIZ_SIZE = 10

        /** 模考整卷题量：比一轮练习更长一点，取数仍走现有 `getBySubject`（库里没有就有多少出多少） */
        private const val MOCK_EXAM_SIZE = 20

        /**
         * 模考作答/跳题的防抖落库窗口（v3.0-B T11，毫秒）。
         *
         * 与设置页那个同名手法（`SettingsScreen` 的考试清单编辑器）同一档位：够短到用户察觉不出
         * "已经存了"这件事的延迟，又够长到一次连点只落一次库。代价见 `persistMockSession` 的 KDoc。
         */
        private const val MOCK_SAVE_DEBOUNCE_MS = 400L

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
     * 本轮生效的调度参数（目标准确率 + 间隔上限），跟着设置页的考试清单与严格度走。
     *
     * 卡片页要拿它算"按这个按钮会排到几天后"并直接印在按钮上，所以是 StateFlow 而不是内部变量。
     *
     * v3.0-B 评审 2：词卡没有学科列，这条库级口径走**学科缝的 null 那一侧**
     * （`schedulingForSubject(s, null, ...)` → `MemoryScheduler.forSubject(subject = null)`）——
     * 计划偏差 D5 与「非关联学科恒 base」的 ⛔ 闸都要求：只有关联学科为空的那场考试才抬词卡。
     * 旧形状 `forSettings(examEpochDayEffective, ...)` 是学科盲的：一场只绑数学的考试会把词卡
     * 一起 ramp，而首页那行（同一条缝选的数）不认它，屏幕与评分器就此各排一套（评审发现 2）。
     */
    private val _scheduling = MutableStateFlow(
        schedulingForSubject(null, null, LocalDate.now()),
    )
    val scheduling: StateFlow<Scheduling> = _scheduling

    init {
        viewModelScope.launch {
            settingsRepository.settings.collect { s ->
                _scheduling.value = schedulingForSubject(s, null, LocalDate.now())
            }
        }
    }

    // ── 学习首页状态 ──────────────────────────────────────────────────────
    // 明日那一格取的是 `observeScheduledMemoryRows()` 一条 SQL 里的那几个字段：
    // 词数与保留率必须出自同一批行，所以这里不能退回 `observeScheduledTimestamps()` +
    // `observeHalfLifeDays()` 两次查询再配对（理由见那条投影查询的 KDoc）。
    // 活跃内核**连同它的参数**一起进 combine：设置页切了内核、或采纳了一份新校准参数，这一格必须
    // **当场**按新的重算，不能等下一次数据变化才跟上 —— 切内核与采纳都不会写 words 表，
    // 只挂数据的流根本不会重发（v3.0-A T2.6：采纳曾经没有任何显示出口，就是漏在这一行只认 id）。
    val homeState: StateFlow<StudyHomeUiState> = combine(
        wordRepository.observeAll(),
        mistakeRepository.observeUnmasteredCount(),
        wordRepository.observeReviewTimestamps(),
        questionRepository.observePracticeTimestamps(),
        wordRepository.observeScheduledMemoryRows()
            // 只跟踪与排期有关的两项（内核 id + 活跃参数）：换主题/改玻璃不该重算整页首页（A-T9 双审 Minor）
            .zip(
                settingsRepository.settings.distinctUntilChanged { old, new ->
                    old.schedulingKernel == new.schedulingKernel &&
                        old.fsrsActiveParams.contentEquals(new.fsrsActiveParams)
                },
            ) { rows, settings -> rows to settings },
    ) { words, unmasteredMistakes, reviewTimestamps, practiceTimestamps, (scheduled, settings) ->
        val now = System.currentTimeMillis()
        // zone/today 各取一次：既用于「今日 0 点」也用于连续天数锚点，避免跨零点时两者不一致
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val dayStart = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val all = reviewTimestamps + practiceTimestamps
        // 「明天」的区间按本地日切，不用 SQL 的 date()（不吃时区）；
        // 这一格只算一次，词数和保留率都从它来
        val tomorrow = TomorrowForecast.of(
            scheduled,
            TomorrowForecast.window(zone, today),
            KernelHub.forId(settings.schedulingKernel, settings),
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

    /**
     * 组一轮学习队列：到期优先，新词按每日目标补位（口径全在 [CardSessionQueue.compose]）。
     *
     * `todayReviewed` 走 word 侧复习时间戳（`word_reviews.reviewed_at`）落在今天那几条，
     * 与首页进度环的 `todayDone` 同一把尺子的词卡分支；它只用来把新词补位卡在剩余目标内，
     * 不改到期复习那一侧的取数。
     */
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
            val zone = ZoneId.systemDefault()
            val dayStart = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
            val todayReviewed = wordRepository.observeReviewTimestamps().first().count { it in dayStart..now }
            val words = wordRepository.getAll()
            _session.value = CardSessionUi(
                queue = CardSessionQueue.compose(
                    words = words,
                    now = now,
                    dailyWordGoal = settingsRepository.current().dailyWordGoal,
                    todayReviewed = todayReviewed,
                    dueCap = SESSION_SIZE,
                ),
            )
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
            // 内核每轮会话现取，且与评分按钮预览同一个来源（同一份 AppSettings：id + 活跃参数）：
            // 按钮上印的间隔与点下去真排出的间隔不能分家（KernelHub 的 KDoc）
            val settings = settingsRepository.current()
            val kernel = KernelHub.forId(settings.schedulingKernel, settings)
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
                confidence = conf?.toStorageInt(),
                fsrsRating = rating.ordinal + 1,
            )
            // v2.9 Task 6 双写 review_log：这一次"已评分复习"追加进事件流，排在上面的状态写
            // （applyReview）与历史写（recordGradedReview）之后——先状态后历史，中间被杀只丢一条日志、
            // 下次复习时刻仍对。富字段这里全部现成：rating=内核档位 1..4、elapsedDays=喂内核的 Δt(gapDays)、
            // scheduledDays=**后向**口径（这次评分**之前**那份排期，即 [word] 快照上的「到期 − lastReviewAt」，
            // 见 ReviewLog.scheduledDays 的 KDoc；T2.5 之前这里误传 `days`=刚排出去的间隔，相对本卡历史串行
            // 一格，已由 log_schema_ver=2 划界）、state=评分前那份卡状态(0New/1Learn/2Review/3Relearn，
            // 稳定度为空即首评→New0)、durationMs=翻面到按分的毫秒(没传记 0)、self_conf=信心 ordinal+1(没选记 null)。
            // 取的是 [word] 这一份**评分前**的行快照（`days` 与 nextReviewAt 都是这次才算出来的，不能拿它们当上次排期）。
            reviewLogRepository.recordGradedReview(
                cardKind = ReviewLog.KIND_WORD,
                cardId = word.id,
                rating = rating.ordinal + 1,
                elapsedDays = gapDays,
                scheduledDays = ReviewLog.prevScheduledDays(word.lastReviewAt, word.nextReviewAt),
                state = if (before.stability == null) 0 else before.cardState.ordinal + 1,
                durationMs = reactionMs ?: 0L,
                origin = "word",
                selfConf = conf?.toStorageInt(),
                sessionId = sessionId,
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

    /**
     * 连续学习满 21 天那条 SIXTY_SIX 贴士（计划 B Task 17）：首页 hero 检测到火焰徽章天数
     * 恰好 [StudyTips.STREAK_MYTH_DAY] 时置真，此后每次安装都不再出现。
     * 与 [markFlipTipSeen]、[markRecallGateHintSeen] 同一条纪律：写失败不提示也不重投。
     */
    fun markSixtySixTipSeen() {
        viewModelScope.launch {
            runCatching { settingsRepository.update { it.copy(sixtySixTipSeen = true) } }
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
            val correct = questionRepository.submitAnswer(question.id, selected, conf?.toStorageInt())
            if (!correct) {
                addMistakeIfAbsent(question, conf)
            }
            _quiz.value = state.copy(
                selected = selected,
                correctCount = state.correctCount + if (correct) 1 else 0,
            )
            // v2.9 Task 6 双写 review_log：练习的即时判定就是"一次已评分复习"，写在 submitAnswer(状态写)之后。
            // 练习不进 FSRS 状态机、也没有逐卡的 Δt / 排出的间隔 / 作答耗时——这几列按 Step 0 定的默认填
            // （elapsed/scheduled/duration=0、state=0New）；rating 由对错映射（判对→GOOD3、判错→AGAIN1），
            // self_conf 与背词同口径（没选信心记 null，不拿 0 冒充"肯定不记得"）。
            reviewLogRepository.recordGradedReview(
                cardKind = ReviewLog.KIND_PRACTICE,
                cardId = question.id,
                rating = if (correct) KernelRating.GOOD.ordinal + 1 else KernelRating.AGAIN.ordinal + 1,
                elapsedDays = 0.0,
                scheduledDays = 0.0,
                state = 0,
                durationMs = 0L,
                origin = "practice",
                selfConf = conf?.toStorageInt(),
                sessionId = sessionId,
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

    // ── 模考会话（v2.7 计划 B Task 13；v3.0-B T11 起可持久化 + 计时）────────────
    private val _mockExam = MutableStateFlow(MockExamUiState())
    val mockExam: StateFlow<MockExamUiState> = _mockExam

    /**
     * 当前这一卷在 `mock_sessions` 里的那一行（内存快照）。
     *
     * 它是**唯一**的落库真值来源：所有写都走 [persistMockSession]，从这一份出发打补丁，
     * 于是"界面上看到的"与"库里存的"不可能分家 —— 前提是 `answers` 一律从 UI 状态现取后传进来
     * （见 [answerMockExam]），绝不从 `mockSessionRow.answers` 反着读第二遍。
     */
    private var mockSessionRow: MockSession? = null

    /** 防抖落库那条唯一在途的写（R4）：新写来了先把还没起跑的那条取消 */
    private var mockSaveJob: Job? = null

    /**
     * 本次暂停的起点（与 `mock_sessions.paused_at` 那一列同一条真值，写库由 [pauseMockExam] 负责）；
     * null = 没在暂停中。恢复时用来推截止时刻，跨进程恢复时它从库里那一列取回（评审 F1）。
     */
    private var mockPausedAt: Long? = null

    /**
     * 组一卷模拟卷并**立刻留下一行**（v3.0-B T11）：与 [startQuiz] 同一取数口径（该学科前
     * [MOCK_EXAM_SIZE] 题、读一次交错开关交给 [interleaveBySubject] 判定），差别只在：
     * 这一卷一次性拿全、作答期间不再回源分页读库，且 `question_ids` 落的是**交错之后**的顺序。
     *
     * 为什么落交错后的顺序而不是原始取数顺序：`question_ids` 就是"发出去的那一份卷"，
     * [loadActiveMockSession] 恢复时按它逐题取回、不再重排（重排会换顺序，恢复出来的卷和用户
     * 答到一半的那卷对不上，`cursor` 会指错题）。
     *
     * 限时来自设置那一格（`examLimitMinutes`，0 = 不限时 → `deadline_ts` 留 null，与"1970 年截止"
     * 是两回事）；卷名取开卷那一刻的 `examForCountdown(today)`，之后清单怎么改都不回写历史行
     * （考试清单住设置、不是表，计划 D2）。
     */
    fun startMockExam(subject: String) {
        viewModelScope.launch {
            val settings = settingsRepository.current()
            val questions = interleaveBySubject(
                questions = questionRepository.getBySubject(subject, MOCK_EXAM_SIZE, 0),
                enabled = settings.interleavingEnabled,
            )
            val now = System.currentTimeMillis()
            val limit = settings.examLimitMinutes
            val row = MockSession(
                subject = subject,
                examName = settings.examForCountdown(LocalDate.now())?.name ?: "",
                questionIds = encodeQuestionIds(questions.map { it.id }),
                answers = "{}",
                startTs = now,
                deadlineTs = if (limit > 0) now + limit * 60_000L else null,
                limitMin = limit,
                state = encodePhase(MockPhase.RUN),
            )
            val id = mockSessionRepository.insert(row)
            mockSessionRow = row.copy(id = id)
            mockPausedAt = null
            _mockExam.value = MockExamUiState(
                subject = subject,
                questions = questions,
                sessionId = id,
                examName = row.examName,
                deadlineTs = row.deadlineTs,
                nowTs = now,
                pauseCountsTime = settings.examPauseCountsTime,
            )
        }
    }

    /**
     * 把界面状态清回"没有卷"。
     *
     * **不删库里的行**（v3.0-B T11 起这一行是用户跨进程留下的东西）：作废整卷走
     * [abandonMockSession]，交卷走 [submitMockExam]（那一行会置 DONE 并被 [loadActiveMockSession]
     * 滤掉）。这里只连同内存快照一起丢掉，并取消还没起跑的防抖写 —— 否则"页面已经清空了、
     * 400ms 后库里却又落一次旧快照"。
     */
    fun resetMockExam() {
        mockSaveJob?.cancel()
        mockSaveJob = null
        mockSessionRow = null
        mockPausedAt = null
        _mockExam.value = MockExamUiState()
    }

    /**
     * 记一次作答：只把"这题选了哪个"写进会话，**不判对错、不产反馈**。
     * 交卷前允许改答案（后写覆盖前写）；已交卷或整卷已跳走则忽略（终态锁死）。
     * 落库走防抖那一条在途写（[persistMockSession]），不逐次 `launch`。
     */
    fun answerMockExam(questionId: Long, selected: Int) {
        val state = _mockExam.value
        if (state.submitted) return
        if (state.questions.none { it.id == questionId }) return
        val answers = state.answers + (questionId to selected)
        _mockExam.value = state.copy(answers = answers)
        persistMockSession { it.copy(answers = encodeAnswers(answers), cursor = state.index) }
    }

    /** 在卷内前后翻题（交卷前自由导航）；越界夹到首 / 末题，不会崩。`cursor` 一并防抖落库 */
    fun gotoMockExam(index: Int) {
        val state = _mockExam.value
        if (state.submitted || state.total == 0) return
        val next = index.coerceIn(0, state.total - 1)
        _mockExam.value = state.copy(index = next)
        persistMockSession { it.copy(cursor = next) }
    }

    /**
     * 防抖落库：取消上一条还没起跑的写，再写**当前**这一份。
     *
     * 为什么不逐次写：一次连点四道题会排四个 `launch`，它们读到的 `mockSessionRow` 有可能不是
     * 同一个版本，最后落库的不一定是最新的那份。取消重排让"在途的写"最多一条，且一定是最新的快照。
     *
     * 代价（照实记在这里，不假装它不存在）：进程**恰好**落在防抖窗口里被杀，最多丢这 400ms 内的
     * 作答 —— 也就是最后那一道题的点击。这一层不是崩溃安全的：真要逐次安全，就得每答一题同步等
     * Room 写完，而模考的作答点是主线程上的点击回调，那是拿手感换保险。交卷那一步不走这条
     * （见 [commitMockSession]：终态写必须落定，且必须排在练习记录与事件流之前）。
     */
    private fun persistMockSession(patch: (MockSession) -> MockSession) {
        val base = mockSessionRow ?: return
        val next = patch(base)
        mockSessionRow = next
        mockSaveJob?.cancel()
        mockSaveJob = viewModelScope.launch {
            delay(MOCK_SAVE_DEBOUNCE_MS)
            mockSessionRepository.update(next)
        }
    }

    /**
     * 同步定格的落库（不打扰防抖那条）：用于**终态**写。
     *
     * 与 [persistMockSession] 的差别只有一处、但要紧：这一条在调用方的协程里 `await` 似地写完才继续，
     * 于是交卷时"会话行 → 练习记录 → 事件流"的先后（R8）是真的，不会变成"400ms 后才补写会话行、
     * 而练习记录已经先落库"。顺带取消还没起跑的防抖写：那一份快照比这里新不了，让它闭嘴。
     */
    private suspend fun commitMockSession(patch: (MockSession) -> MockSession) {
        val base = mockSessionRow ?: return
        val next = patch(base)
        mockSessionRow = next
        mockSaveJob?.cancel()
        mockSaveJob = null
        mockSessionRepository.update(next)
    }

    /**
     * 暂停：状态置 PAUSED，并把暂停起点**连同这一行一起**落库（评审 F1）。
     *
     * 推不推截止是**恢复**那一刻才知道的事（要读 `examPauseCountsTime`、要算暂停了多久），
     * 所以这里只记起点、`deadline_ts` 原样留着。但起点必须进库：只存在内存里的话，进程一死
     * 就退化成"恢复那一刻才暂停"，整段停表被吃掉（见 `MockSession.pausedAt`）。
     *
     * 写走防抖那一条（400ms）：万一恰好卡在窗口里被杀，那一行留在 RUN、`paused_at` 留 null，
     * 恢复时锚点不可信 ⇒ 不补时 —— 少给一段时间，但绝不白送，也不会当场把卷子判掉（那条兜底
     * 见 [resumeMockExam]）。
     */
    fun pauseMockExam() {
        val state = _mockExam.value
        if (!state.started || state.submitted || state.paused) return
        val at = System.currentTimeMillis()
        mockPausedAt = at
        _mockExam.value = state.copy(paused = true, nowTs = at, pausedAtTs = at)
        persistMockSession { it.copy(state = encodePhase(MockPhase.PAUSED), pausedAt = at) }
    }

    /**
     * 继续：按设置决定暂停那段扣不扣时（文档 L1883「暂停计时可配」）。
     *
     * 起点取 [mockPausedAt]（跨进程恢复时它是库里 `paused_at` 那一列，经
     * [pausedAnchorForRestore] 校验过可信度），于是**整段暂停**都补得回来 —— 包括停在
     * 「上次那卷还没交」那张卡上的那一段时间：截止时刻在恢复这一刻才推，不在进页面时推，
     * 因为用户可能在那张卡上坐着不动，进页面就推等于替他按下继续。
     *
     * ⛔ 恢复那一刻就已经过截止的，不许"悄悄"把卷子判掉（评审 F1 的界面守卫）。理由：
     * 这张卡上的表针是**冻住**的（`LaunchedEffect` 的键含 `paused`，暂停中不 tick），
     * 屏幕上写「剩余 00:00」而用户不知道按下去会发生什么；把他放进 RUN 更糟 —— 按下继续后
     * 第一个 tick 就自动交卷，看上去像"这 App 把我卷子交了"。这里选**立刻按截止时刻交卷并标
     * `autoSubmitted = true`**：走的就是时间到那条既有路径，成绩页顶上那句
     * 「时间到，已自动交卷」就是这次判分的说明，判分不再是按下继续的那一瞬无声发生。
     * 暂停卡上又是怎么提前写明剩余 00:00 的，见 [pausedRemainingLabel]。
     */
    fun resumeMockExam() {
        val state = _mockExam.value
        if (!state.paused) return
        viewModelScope.launch {
            val counts = settingsRepository.current().examPauseCountsTime
            val now = System.currentTimeMillis()
            val anchor = pausedAnchorForRestore(mockPausedAt, now)
            val deadline = deadlineAfterResume(now, anchor, state.deadlineTs, counts)
            mockPausedAt = null
            if (isExpired(now, deadline)) {
                // 先把"暂停"这个外观撤掉再交卷：交完要跳成绩页，屏幕上不该留着一张暂停卡
                _mockExam.value = state.copy(paused = false, nowTs = now, pausedAtTs = null)
                submitMockExam(auto = true)
                return@launch
            }
            _mockExam.value = state.copy(paused = false, deadlineTs = deadline, nowTs = now, pausedAtTs = null)
            persistMockSession {
                it.copy(state = encodePhase(MockPhase.RUN), deadlineTs = deadline, pausedAt = null)
            }
        }
    }

    /**
     * 页面每秒喂一次（`nowTs` 由调用方传，测试给假时钟）。
     * 到期就交卷，且标 `autoSubmitted = true` —— 结果页要能说出"这是时间到自动交的"，
     * 用户自己按的交卷和钟替他按的，在分数上不该看起来一样（R3；这一位同时写进 `result` 那一列）。
     */
    fun onMockTick(nowTs: Long) {
        val state = _mockExam.value
        if (!state.started || state.submitted || state.paused) return
        _mockExam.value = state.copy(nowTs = nowTs)
        if (isExpired(nowTs, state.deadlineTs)) submitMockExam(auto = true)
    }

    /**
     * 进页面时问一句：有没有没交完的那一卷。有就恢复成 PAUSED（不替用户把钟偷偷跑起来，R6）。
     *
     * 用 `observeActive()`（"没交卷的那几条，新的在前"）而不是按 id 订阅：进页面时手上没有 id 可查，
     * 而"最新那一卷"正是用户预期被续上的那一卷。仓储里那两个 `observeById` / `observeRecent`
     * 全计划无人调用，已随本任务删除 —— 死 API 不进发布包。
     *
     * **跨进程的暂停也补得回来（评审 F1）**：暂停起点落在 `mock_sessions.paused_at` 那一列，
     * 这里把它经 [pausedAnchorForRestore] 校验后当锚点，**但不在此刻改截止时刻** —— 用户可能在
     * 这张卡上坐着不动，起点要留到他真的按下「继续作答」那一刻才算（[resumeMockExam]）。
     * 那一列为空（作答中崩的 RUN 行：钟本来就一直在跑）或不可信（时钟往回跳）时**不补时**：
     * 宁可少给，白送时间等于允许「关掉 App 睡一觉再回来继续考」。
     *
     * 题库变了、题目取不到时**删行作废**（R9）：`question_ids` 里少一题就少一题的分数与解析，
     * 那种"半张卷"看着完整、其实是拿别人的空缺给用户判错，绝不显示。
     */
    fun loadActiveMockSession() {
        viewModelScope.launch {
            // 先让上一条在途的防抖写闭嘴（评审 F6，口径同 [resetMockExam]）：不取消的话，
            // "上一卷最后一次作答"的那一份快照可能在新一卷读回来**之后**才落库，
            // 把新行的 answers/cursor 覆盖成旧卷的内容（一次瞬时错乱，之后靠下一次作答纠正回来）。
            mockSaveJob?.cancel()
            mockSaveJob = null
            val active = mockSessionRepository.observeActive()
                .firstOrNull { decodePhase(it.state) != MockPhase.DONE }
            if (active == null) {
                resetMockExam()
                return@launch
            }
            val ids = decodeQuestionIds(active.questionIds)
            val questions = ids.mapNotNull { questionRepository.getById(it) }
            if (ids.isEmpty() || questions.size != ids.size) {
                mockSaveJob?.cancel()
                mockSessionRepository.deleteById(active.id)
                mockSessionRow = null
                resetMockExam()
                return@launch
            }
            val now = System.currentTimeMillis()
            val anchor = pausedAnchorForRestore(active.pausedAt, now)
            mockPausedAt = anchor
            mockSessionRow = active.copy(state = encodePhase(MockPhase.PAUSED))
            _mockExam.value = MockExamUiState(
                subject = active.subject,
                questions = questions,          // 按 question_ids 的顺序，不再交错：那一份卷已经发出去了
                answers = decodeAnswers(active.answers),
                index = active.cursor.coerceIn(0, questions.size - 1),
                sessionId = active.id,
                examName = active.examName,
                deadlineTs = active.deadlineTs,  // 原样读回：截止时刻的推移发生在「继续」那一刻
                paused = true,
                nowTs = now,
                pausedAtTs = anchor,
                pauseCountsTime = settingsRepository.current().examPauseCountsTime,
            )
        }
    }

    /** 丢弃这一卷（页面上那枚「丢弃本卷」经确认对话框进来）：删行 + 清空界面，不留半成品 */
    fun abandonMockSession(id: Long) {
        mockSaveJob?.cancel()
        mockSaveJob = null
        mockSessionRow = null
        viewModelScope.launch { mockSessionRepository.deleteById(id) }
        resetMockExam()
    }

    /**
     * 交卷。`auto = true` = 时间到自动交（结果页顶那句"时间到，已自动交卷"要出来，R3）。
     *
     * 四步顺序不许换（R8：会话行 → 练习记录 → 事件流，与 [gradeCard] 的「先状态后历史」同一条方向）。
     * 但**别把这条纪律读成"中间被杀只丢一条日志"**（评审 F3，这里照实写清楚死法）：
     *  ① 纯算分（[submitOutcome]，未答的题出待批改队列而不是判成错）+ UI 终态同步冻结；
     *  ② 会话落库（`state=DONE` + `result` 摘要 + `answers`/`cursor` 定格 + `paused_at` 清空）——走
     *     [commitMockSession] 的**同步**那一支，不挤进 400ms 防抖：② 必须排在 ③④ 之前，
     *     用防抖的话顺序会反过来（行在 400ms 后才落，练习记录已经先写完了）；
     *  ③ 每题写 `practice_records`（既有 `submitAnswer`，答错的幂等入错题本）；
     *  ④ 每题双写 `review_log`。
     *
     * ②③④ 住在**同一个** `viewModelScope.launch` 里，所以进程死亡落点不同、后果不同：
     * - 死在 ① 之后、那个协程起跑之前：**连 ② 的 DONE 都没落**。库里那一行仍是 RUN/PAUSED，
     *   下次进模考页会照常问「上次那卷还没交」，用户重新交一次就能补齐 —— 可恢复，但刚才那一眼
     *   成绩页是**没有落库的一次显示**（③④ 同样一条没写）。
     * - 死在 ② 之后、③/④ 跑到一半：行已经是 DONE（不再被续上，也**没有重放路径**），
     *   而练习记录与事件流只有前几题 —— 缺的那几条永久缺，校准器的训练流就此少了几次评分。
     * 真正"只丢一条日志"的是**背词侧**那种形状（状态与日志分两次落库）；这一处做不到，
     * 因为交卷要写的东西本来就不止两行。要收得更紧得把 ②③④ 包进一个 Room 事务，本切片不做。
     *
     * ④ 是这一笔补的洞（计划 D8）：v2.9 起 `review_log` 是校准器的唯一训练流，
     * 而模考答过的题只进了 `practice_records` —— 在训练流里它们从来没发生过。
     * 口径照 [selectOption]（练习侧）那一处逐列抄：`rating` 由对错映射（判对 GOOD3 / 判错 AGAIN1，
     * 且对错取 `submitAnswer` 的返回值 —— 与练习侧同一份真值，不取机器那份），
     * `elapsed`/`scheduled`/`duration` **成对** 0（模考不进 FSRS 状态机，没有逐卡 Δt），
     * `state=0` New，`selfConf=null`（交卷时没有信心采集，不拿 0 冒充"肯定不记得"），
     * `origin="practice"`、同一个 [sessionId]。
     *
     * 未作答的题（`out.toGrade`）**一步都不写**：没人给出过选择，写下去就是拿 0 冒充一次作答，
     * 而且是在校准器的训练流里冒充。
     */
    fun submitMockExam(auto: Boolean = false) {
        val state = _mockExam.value
        if (!state.started || state.submitted) return
        val answerKey = state.questions.associate { it.id to it.answerIndex }
        val answerCount = state.questions.firstOrNull()?.let { parseOptions(it.optionsJson).size } ?: 0
        // ① 算分：判分算术仍然只在 MockExamState 那一处（R1），这里只把未答的摘出去
        val out = submitOutcome(
            questionIds = state.questions.map { it.id },
            selections = state.answers,
            answerKey = answerKey,
            answerCount = answerCount,
        )
        // 先冻结 UI 终态（同步），成绩页立刻能读 results / toGrade；落库在下面那一个协程里按序补
        _mockExam.value = state.copy(
            submitted = true,
            results = out.graded,
            toGrade = out.toGrade,
            autoSubmitted = auto,
        )
        val questionsById = state.questions.associateBy { it.id }
        viewModelScope.launch {
            // ② 会话行定格（终态写，不等防抖）
            commitMockSession {
                it.copy(
                    state = encodePhase(MockPhase.DONE),
                    answers = encodeAnswers(state.answers),
                    cursor = state.index,
                    result = encodeExamResult(out, autoSubmitted = auto),
                    pausedAt = null,
                )
            }
            // ③④ 逐题：练习记录 → 事件流
            out.graded.forEach { (questionId, verdict) ->
                val selected = verdict.selected ?: return@forEach
                val question = questionsById[questionId] ?: return@forEach
                // 与练习同一 submitAnswer，只是 conf 传 null（模考不采集信心 → confidence 落 NULL）
                val correct = questionRepository.submitAnswer(questionId, selected)
                if (!correct) addMistakeIfAbsent(question, conf = null)
                reviewLogRepository.recordGradedReview(
                    cardKind = ReviewLog.KIND_PRACTICE,
                    cardId = questionId,
                    rating = if (correct) KernelRating.GOOD.ordinal + 1 else KernelRating.AGAIN.ordinal + 1,
                    elapsedDays = 0.0,
                    scheduledDays = 0.0,
                    state = 0,
                    durationMs = 0L,
                    origin = "practice",
                    selfConf = null,
                    sessionId = sessionId,
                )
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
