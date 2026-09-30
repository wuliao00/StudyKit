package com.studykit.ui.mistake

import android.app.Application
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.studykit.StudyKitApp
import com.studykit.data.entity.Mistake
import com.studykit.data.entity.MistakeReview
import com.studykit.srs.Confidence
import com.studykit.srs.Fsrs
import com.studykit.srs.MemoryState
import com.studykit.srs.Rating
import com.studykit.srs.ReviewPlanner
import com.studykit.srs.SchedulingResult
import com.studykit.tips.Tip
import com.studykit.tips.TipEvent
import com.studykit.tips.StudyTips
import com.studykit.util.MistakeImageStore
import com.studykit.util.Time
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

/**
 * 一次重做的算法决策结果（纯函数输出，不触库，便于单元测试）。
 *
 * keptPinned = true 表示用户钉住了复习日，本次不写回算法时间。
 */
data class RedoPlan(
    val correctStreak: Int,
    val lastGapDays: Int,
    val mastered: Boolean,
    val state: MemoryState,
    val reviewAt: Long,
    val intervalDays: Int,
    val desiredRetention: Double,
    val highConfidenceError: Boolean,
    val keptPinned: Boolean,
)

/**
 * 错题复习的纯逻辑：重做遮罩、隔天数、掌握状态转移、引擎排期、队列优先级与变体标记。
 *
 * 全部无 Android 依赖，抽出来的目的是让「遮解析→重做→跨间隔连对→自动排期」
 * 这套决策可测，Compose 只负责把结果画出来。
 */
object MistakeReviewLogic {

    /** 重做时要遮住的行前缀：答案与解析属于「重复暴露」，未重做完不能先看到 */
    private val hiddenPrefixes = listOf("正确答案：", "答案：", "解析：", "详解：")

    /** 变体重练标记前缀，写在 note 里 */
    const val VARIANT_PREFIX = "变体重练｜"

    /** 由 Mistake 的 FSRS 列构造记忆状态 */
    fun memoryState(mistake: Mistake): MemoryState = MemoryState(
        stability = mistake.stability,
        difficulty = mistake.difficulty,
        lastReviewAt = mistake.lastReviewAt,
        reps = mistake.reps,
        lapses = mistake.lapses,
    )

    /**
     * 两次重做的间隔天数（按日历日而不是 24 小时整数倍）。
     *
     * 首次重做或同一天内再做都是 0 天——同天连对只是短期表现，不算跨间隔。
     */
    fun gapDays(lastRedoAt: Long?, now: Long): Int {
        if (lastRedoAt == null || lastRedoAt <= 0L || now < lastRedoAt) return 0
        val previous = Time.localDate(lastRedoAt)
        val today = Time.localDate(now)
        return ChronoUnit.DAYS.between(previous, today).toInt().coerceAtLeast(0)
    }

    /**
     * 重做结果到 FSRS 四档的映射：答错一律 Again；提示越深越低；
     * 零提示且自评非常确定才给 Easy。
     */
    fun ratingFor(correct: Boolean, confidence: Confidence, hintLevel: Int): Rating = when {
        !correct -> Rating.AGAIN
        hintLevel >= MistakeMastery.HINT_LEVELS -> Rating.HARD
        confidence == Confidence.SURE && hintLevel <= 0 -> Rating.EASY
        else -> Rating.GOOD
    }

    /**
     * 队列优先级（越大越该先做）：欠账越深越靠前，高置信答错的额外加权重。
     * 未进入调度的新错题（reps=0）保留率被视为 0，因此天然排在队列最前。
     */
    fun queuePriority(mistake: Mistake, now: Long): Double {
        val confidence = if (mistake.highConfidenceError) Confidence.SURE else Confidence.VAGUE
        return ReviewPlanner.priority(memoryState(mistake), confidence, correct = false, now = now)
    }

    /** 待复习队列排序：优先级降序，同分时先做到期时间更早的（未排期放最后） */
    fun queueOrder(mistakes: List<Mistake>, now: Long): List<Mistake> =
        mistakes.sortedWith(
            compareByDescending<Mistake> { queuePriority(it, now) }
                .thenBy { it.reviewAt ?: Long.MAX_VALUE },
        )

    /** 是否已进入今日重做窗口 */
    fun isDue(mistake: Mistake, now: Long): Boolean =
        !mistake.mastered && mistake.reviewAt != null && mistake.reviewAt <= now

    /** 预测保留率：未调度返回 null，UI 显示占位而非 0% */
    fun predictedRetention(mistake: Mistake, now: Long): Double? {
        val state = memoryState(mistake)
        return if (state.stability <= 0.0 || state.reps <= 0) null else Fsrs.retrievability(state, now)
    }

    /**
     * 欠账天数：有确定到期时间（无论算法还是手动钉的）按它算，
     * 否则由 FSRS 状态推算。
     */
    fun overdueDays(mistake: Mistake, now: Long): Int {
        mistake.reviewAt?.let { due ->
            return ((now - due) / Fsrs.DAY_MS).toInt().coerceAtLeast(0)
        }
        val state = memoryState(mistake)
        return if (state.stability <= 0.0 || state.reps <= 0) 0 else ReviewPlanner.overdueDays(state, now)
    }

    /** 保留率转整数百分比（四舍五入，钳到 0..100） */
    fun retentionPercent(retention: Double): Int =
        (retention * 100.0).roundToInt().coerceIn(0, 100)

    /** 挤牙膏下一档：已到顶档就停在第三档 */
    fun nextHintLevel(level: Int): Int = MistakeMastery.nextHint(level).level

    /** 还能继续挤下一档 */
    fun canRevealMoreHints(hintLevel: Int): Boolean = hintLevel < MistakeMastery.HINT_LEVELS

    /** 已揭示的提示（按档位顺序）：重做时同样只给方向，不给最终答案 */
    fun revealedHints(hintLevel: Int): List<MistakeMastery.Hint> =
        (0 until hintLevel.coerceIn(0, MistakeMastery.HINT_LEVELS)).map { MistakeMastery.nextHint(it) }

    /** 先给把握度再提交重做：没有自评就认不出「高置信答错」这个超纠正信号 */
    fun canSubmitRedo(confidence: Confidence?): Boolean = confidence != null

    /** 重做模式的说明文案（为什么默认是重做而不是再看一遍解析） */
    fun modeCaption(mode: MistakeMastery.ReviewMode): String = when (mode) {
        MistakeMastery.ReviewMode.REDO ->
            "先遮住答案与解析，自己把这道题做一遍，再对照。检索一遍比再读一遍管用。"
        MistakeMastery.ReviewMode.SOLUTION ->
            "精读模式：直接看完整内容。适合刚收录、还没有思路的新错题。"
    }

    /** 重做模式下未提交前一律遮解析；精读模式不遮 */
    fun solutionIsHidden(mode: MistakeMastery.ReviewMode, attempted: Boolean): Boolean =
        mode == MistakeMastery.ReviewMode.REDO && !MistakeMastery.reviewFlow(attempted).canRevealSolution

    /** 当前可见的题目正文 */
    fun visibleContent(
        mode: MistakeMastery.ReviewMode,
        attempted: Boolean,
        content: String,
    ): String = if (solutionIsHidden(mode, attempted)) maskedContent(content) else content

    /** 去掉答案与解析行，只留下题干与选项 */
    fun maskedContent(content: String): String = content.lines()
        .filterNot { line -> hiddenPrefixes.any { line.trimStart().startsWith(it) } }
        .joinToString("\n")

    /** 新错题的初始复习时间：次日回到队列（默认自动排期，用户未钉住） */
    fun firstReviewAt(now: Long): Long = now + Fsrs.DAY_MS

    /** 手动钉住的日期交还给算法时的推算时间（高置信答错排得更紧） */
    fun autoReviewAt(mistake: Mistake, now: Long): Long {
        val retention = MistakeMastery.retentionFor(mistake.highConfidenceError)
        val state = memoryState(mistake)
        val base = if (state.stability > 0.0 && state.reps > 0) {
            state
        } else {
            Fsrs.firstRating(Rating.GOOD, now)
        }
        return maxOf(Fsrs.dueAt(base, retention), firstReviewAt(now))
    }

    /**
     * 重做一次后的完整决策：连对次数、掌握标记、FSRS 状态与下次复习时间。
     *
     * 用户钉过日期时（shouldAutoSchedule=false）保留原时间，只更新记忆状态；
     * 首次进入调度（reps=0 或 stability=0）用 firstRating 起步，其余交给 ReviewPlanner。
     */
    fun planRedo(
        mistake: Mistake,
        correct: Boolean,
        confidence: Confidence,
        hintLevel: Int,
        lastRedoAt: Long?,
        now: Long,
    ): RedoPlan {
        val gap = gapDays(lastRedoAt, now)
        val streak = MistakeMastery.nextStreak(mistake.correctStreak, correct)
        val mastered = MistakeMastery.isMastered(streak, gap)
        val rating = ratingFor(correct, confidence, hintLevel)
        val highConfidenceError =
            mistake.highConfidenceError || (!correct && confidence == Confidence.SURE)
        val state = memoryState(mistake)
        val retention = ReviewPlanner.nextRetention(confidence, correct)
        val result: SchedulingResult = if (state.reps <= 0 || state.stability <= 0.0) {
            val first = Fsrs.firstRating(rating, now)
            val due = if (rating == Rating.AGAIN) now + Fsrs.RELEARN_MS else Fsrs.dueAt(first, retention)
            val days = if (rating == Rating.AGAIN) 0 else Fsrs.intervalDays(first.stability, retention)
            SchedulingResult(first, due, days, retention)
        } else {
            ReviewPlanner.review(state, rating, confidence, correct, now)
        }
        val keepPinned = !MistakeMastery.shouldAutoSchedule(mistake.pinned) && mistake.reviewAt != null
        return RedoPlan(
            correctStreak = streak,
            lastGapDays = gap,
            mastered = mastered,
            state = result.state,
            reviewAt = if (keepPinned) mistake.reviewAt!! else result.dueAt,
            intervalDays = result.intervalDays,
            desiredRetention = result.desiredRetention,
            highConfidenceError = highConfidenceError,
            keptPinned = keepPinned,
        )
    }

    /** 变体标记行：考点不变、外壳换成用户输入的新情境 */
    fun variantNoteLine(conceptTag: String, surface: String): String {
        val variant = MistakeMastery.variantOf(conceptTag, surface.trim())
        if (!variant.isVariant) return ""
        return "${VARIANT_PREFIX}考点=${variant.conceptTag}｜情境=${variant.surface}"
    }

    /** 已标记过多少次变体重练 */
    fun variantCount(note: String): Int =
        note.lines().count { it.trimStart().startsWith(VARIANT_PREFIX) }

    /** 错因标签对应的自我解释提示；未归因返回 null */
    fun selfExplainPrompt(cause: String): String? =
        MistakeMastery.CAUSES.firstOrNull { it.label == cause }?.selfExplainPrompt

    /** 重做后该挂哪条贴士：高置信答错优先讲超纠正，否则讲必要难度 */
    fun tipForRedo(correct: Boolean, confidence: Confidence): Tip? {
        if (correct) return null
        val event = if (confidence == Confidence.SURE) {
            TipEvent.HighConfidenceMistake
        } else {
            TipEvent.StrugglingReview
        }
        return StudyTips.forEvent(event)
    }
}

/**
 * 重做页的本地状态：模式、自评信心、提示档、是否已提交、自己的解法、错因选择与上次结果。
 */
data class RedoUiState(
    val mode: MistakeMastery.ReviewMode = MistakeMastery.defaultMode,
    val confidence: Confidence? = null,
    val hintLevel: Int = 0,
    val attempted: Boolean = false,
    val ownSolution: String = "",
    val causeChoice: String = "",
    val outcome: RedoOutcome? = null,
)

/** 一次重做完成后的反馈：跨间隔连对、是否转掌握、下次到期 */
data class RedoOutcome(
    val correct: Boolean,
    val streak: Int,
    val gapDays: Int,
    val mastered: Boolean,
    val intervalDays: Int,
    val keptPinned: Boolean,
    val reviewAt: Long,
    val tip: Tip?,
)

/**
 * 错题模块 ViewModel：待复习/已掌握分组（掌握判定交给算法）、重做会话、
 * 错因归因、自动排期与手动钉住、变体标记、拍照录入。
 */
class MistakeViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as StudyKitApp).container
    private val repository = container.mistakeRepository

    // ── 列表与筛选 ────────────────────────────────────────────────────────
    val mistakes: StateFlow<List<Mistake>> = repository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 当前学科筛选；null 表示全部 */
    private val _subjectFilter = MutableStateFlow<String?>(null)
    val subjectFilter: StateFlow<String?> = _subjectFilter

    /** 数据中 distinct 出的学科列表 */
    val subjects: StateFlow<List<String>> = mistakes
        .map { list -> list.map { it.subject }.distinct() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 累计重做次数（列表页概览） */
    val reviewTotal: StateFlow<Int> = repository.observeReviewCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private fun applyFilter(list: List<Mistake>, filter: String?): List<Mistake> =
        if (filter == null) list else list.filter { it.subject == filter }

    /**
     * 待复习队列：按引擎优先级排序（欠账 + 高置信答错靠前）。
     * 掌握状态不再由手动按钮决定，只看算法写回的 mastered 列。
     */
    val pendingQueue: StateFlow<List<Mistake>> = combine(mistakes, _subjectFilter) { list, filter ->
        MistakeReviewLogic.queueOrder(applyFilter(list, filter).filterNot { it.mastered }, System.currentTimeMillis())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 已掌握（跨间隔连对达标）：按创建时间倒序，仅作历史留存 */
    val masteredList: StateFlow<List<Mistake>> = combine(mistakes, _subjectFilter) { list, filter ->
        applyFilter(list, filter).filter { it.mastered }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 今日到期队列（SQL 下推取，进页时刷一次，只用于概览计数） */
    private val _dueQueue = MutableStateFlow<List<Mistake>>(emptyList())
    val dueQueue: StateFlow<List<Mistake>> = _dueQueue

    fun refreshDueQueue() {
        viewModelScope.launch {
            _dueQueue.value = repository.getDueSorted(System.currentTimeMillis())
        }
    }

    fun selectSubject(subject: String?) {
        _subjectFilter.value = subject
    }

    // ── 详情与重做会话 ──────────────────────────────────────────────
    private val _detailId = MutableStateFlow<Long?>(null)
    val detail: StateFlow<Mistake?> = _detailId
        .flatMapLatest { id ->
            if (id == null) flowOf(null) else repository.observeById(id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 该题的重做历史（按时间倒序） */
    val reviews: StateFlow<List<MistakeReview>> = _detailId
        .flatMapLatest { id ->
            if (id == null) flowOf(emptyList()) else repository.observeReviews(id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _redo = MutableStateFlow(RedoUiState())
    val redo: StateFlow<RedoUiState> = _redo

    fun openDetail(id: Long) {
        // 同一道错题重复进入（如旋转屏幕重建组合）不重置本轮重做进度
        if (_detailId.value == id) return
        _detailId.value = id
        _redo.value = RedoUiState()
        viewModelScope.launch {
            val cause = repository.getById(id)?.cause.orEmpty()
            _redo.value = _redo.value.copy(causeChoice = cause)
        }
    }

    /** 切换复习方式：默认重做，精读模式由用户主动选定 */
    fun setRedoMode(mode: MistakeMastery.ReviewMode) {
        _redo.value = _redo.value.copy(mode = mode)
    }

    /** 重做前先选自评信心（已提交结果后锁定） */
    fun selectRedoConfidence(confidence: Confidence) {
        val state = _redo.value
        if (state.outcome != null) return
        _redo.value = state.copy(confidence = confidence)
    }

    /** 挤一档提示：到第三档就停止，不直接给最终答案 */
    fun revealRedoHint() {
        val state = _redo.value
        _redo.value = state.copy(hintLevel = MistakeReviewLogic.nextHintLevel(state.hintLevel))
    }

    fun setOwnSolution(text: String) {
        _redo.value = _redo.value.copy(ownSolution = text)
    }

    /** 提交重做：先有自评信心才放开解析（没有把握度就认不出超纠正信号） */
    fun submitRedo() {
        val state = _redo.value
        if (!MistakeReviewLogic.canSubmitRedo(state.confidence)) return
        _redo.value = state.copy(attempted = true)
    }

    /** 对照答案后自评本次重做结果：写记录 + 连对/掌握状态 + 自动排期 */
    fun recordRedoResult(correct: Boolean) {
        val id = _detailId.value ?: return
        val state = _redo.value
        if (!state.attempted || state.outcome != null) return
        val confidence = state.confidence ?: Confidence.VAGUE
        viewModelScope.launch {
            val mistake = repository.getById(id) ?: return@launch
            val now = System.currentTimeMillis()
            val plan = MistakeReviewLogic.planRedo(
                mistake = mistake,
                correct = correct,
                confidence = confidence,
                hintLevel = state.hintLevel,
                lastRedoAt = repository.lastRedoAt(id),
                now = now,
            )
            repository.recordRedo(
                mistakeId = id,
                correct = correct,
                confidence = confidence.level,
                hintLevel = state.hintLevel,
                gapDays = plan.lastGapDays,
                reviewedAt = now,
            )
            repository.applyRedo(
                id = id,
                correctStreak = plan.correctStreak,
                lastGapDays = plan.lastGapDays,
                stability = plan.state.stability,
                difficulty = plan.state.difficulty,
                reps = plan.state.reps,
                lapses = plan.state.lapses,
                lastReviewAt = plan.state.lastReviewAt,
                highConfidenceError = plan.highConfidenceError,
                reviewAt = plan.reviewAt,
                mastered = plan.mastered,
            )
            _redo.value = state.copy(
                outcome = RedoOutcome(
                    correct = correct,
                    streak = plan.correctStreak,
                    gapDays = plan.lastGapDays,
                    mastered = plan.mastered,
                    intervalDays = plan.intervalDays,
                    keptPinned = plan.keptPinned,
                    reviewAt = plan.reviewAt,
                    tip = MistakeReviewLogic.tipForRedo(correct, confidence),
                ),
            )
        }
    }

    /** 再做一次：清空本轮作答痕迹，保留模式与错因选择 */
    fun restartRedo() {
        _redo.value = _redo.value.copy(
            confidence = null,
            hintLevel = 0,
            attempted = false,
            ownSolution = "",
            outcome = null,
        )
    }

    /** 选定错因标签（仅本地选择，需显式保存） */
    fun pickCause(label: String) {
        _redo.value = _redo.value.copy(causeChoice = label)
    }

    /** 保存错因归因 */
    fun saveCause() {
        val id = _detailId.value ?: return
        val cause = _redo.value.causeChoice
        viewModelScope.launch {
            repository.updateCause(id, cause)
            toast("已记录错因")
        }
    }

    /** 标记变体重练：考点不变、外壳换成用户输入的新情境 */
    fun markVariant(surface: String) {
        val id = _detailId.value ?: return
        if (surface.isBlank()) return
        viewModelScope.launch {
            val mistake = repository.getById(id) ?: return@launch
            val line = MistakeReviewLogic.variantNoteLine(
                conceptTag = mistake.cause.ifBlank { mistake.subject },
                surface = surface,
            )
            if (line.isEmpty()) {
                toast("先填一个新情境")
                return@launch
            }
            val merged = if (mistake.note.isBlank()) line else "${mistake.note}\n$line"
            repository.update(mistake.copy(note = merged))
            toast("已标记变体重练")
        }
    }

    // ── 拍照录入 ──────────────────────────────────────────────────────────
    /** 拍照返回后暂存的临时图片文件，录入页读取；保存或放弃时清理 */
    private val _pendingCapture = MutableStateFlow<File?>(null)
    val pendingCapture: StateFlow<File?> = _pendingCapture

    fun setPendingCapture(file: File?) {
        _pendingCapture.value = file
    }

    fun discardPendingCapture() {
        _pendingCapture.value?.delete()
        _pendingCapture.value = null
    }

    /** 保存拍照错题：压缩图片入 mistake_images/，Room 只存相对路径；错因可选，录入即自动排期 */
    fun savePhotoMistake(
        subject: String,
        title: String,
        note: String,
        cause: String = "",
        onSaved: () -> Unit,
    ) {
        val captured = _pendingCapture.value
        if (captured == null || captured.exists().not()) {
            toast("未获取到照片")
            return
        }
        val finalSubject = subject.ifBlank { "未分类" }
        val finalTitle = title.ifBlank { "拍照错题" }
        viewModelScope.launch {
            val relativePath = withContext(Dispatchers.IO) {
                MistakeImageStore.processCaptured(getApplication(), captured)
            }
            _pendingCapture.value = null
            if (relativePath == null) {
                toast("图片处理失败，请重新拍照")
                return@launch
            }
            val id = repository.add(
                source = Mistake.SOURCE_PHOTO,
                subject = finalSubject.trim(),
                title = finalTitle.trim(),
                content = note.trim(),
                imagePath = relativePath,
            )
            if (cause.isNotBlank()) {
                repository.updateCause(id, cause)
            }
            // 默认自动排期：未经用户钉住，新错题次日回到队列
            repository.schedule(id, MistakeReviewLogic.firstReviewAt(System.currentTimeMillis()), pinned = false)
            toast("错题已保存")
            onSaved()
        }
    }

    // ── 排期与归类 ────────────────────────────────────────────────────
    /** 钉住复习日（时间戳毫秒）：钉住后算法不再覆盖该时间 */
    fun pinReviewAt(id: Long, reviewAt: Long) {
        viewModelScope.launch {
            repository.schedule(id, reviewAt, pinned = true)
            toast("已钉住这一天，算法不再改动")
        }
    }

    /** 取消钉住，把复习时间交还给算法按记忆状态推算 */
    fun releasePin(id: Long) {
        viewModelScope.launch {
            val mistake = repository.getById(id) ?: return@launch
            val auto = MistakeReviewLogic.autoReviewAt(mistake, System.currentTimeMillis())
            repository.schedule(id, auto, pinned = false)
            toast("已交还给自动排期")
        }
    }

    /** 修改学科归类 */
    fun updateSubject(id: Long, subject: String) {
        if (subject.isBlank()) return
        viewModelScope.launch {
            val mistake = repository.observeById(id).first() ?: return@launch
            repository.update(mistake.copy(subject = subject.trim()))
            toast("学科已更新")
        }
    }

    /** 删除错题（含图片文件清理）；按 id 从数据库查询，避免依赖可能过期的 UI 缓存 */
    fun delete(id: Long, onDeleted: () -> Unit) {
        viewModelScope.launch {
            val mistake = repository.getById(id)
            mistake?.imagePath?.let {
                withContext(Dispatchers.IO) { MistakeImageStore.delete(getApplication(), it) }
            }
            repository.delete(id)
            onDeleted()
        }
    }

    /** 图片相对路径 → 本地文件（Coil 加载用） */
    fun resolveImage(relativePath: String): File =
        MistakeImageStore.resolve(getApplication(), relativePath)

    fun resolveThumb(relativePath: String): File? =
        MistakeImageStore.resolveThumb(getApplication(), relativePath)

    private fun toast(message: String) {
        Toast.makeText(getApplication(), message, Toast.LENGTH_SHORT).show()
    }
}

