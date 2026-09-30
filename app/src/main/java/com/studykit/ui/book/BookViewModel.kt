package com.studykit.ui.book

import android.app.Application
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.studykit.StudyKitApp
import com.studykit.data.entity.Book
import com.studykit.data.entity.BookRecall
import com.studykit.data.entity.BookReview
import com.studykit.data.entity.Excerpt
import com.studykit.srs.Fsrs
import com.studykit.srs.MemoryState
import com.studykit.srs.Rating
import com.studykit.srs.SchedulingResult
import com.studykit.tips.StudyTips
import com.studykit.tips.Tip
import com.studykit.tips.TipEvent
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 精加工提问模板：合书回忆时默认自问的问题（纯数据，供 UI 与测试复用） */
data class RecallQuestionTemplate(val label: String, val text: String)

/**
 * 检索式笔记的纯逻辑：自评档位映射、间隔排期、章末判定、书摘入队判定与展示格式化。
 *
 * 全部为无 Android 依赖的纯函数，可直接在 JVM 单元测试里跑；FSRS 的数学部分
 * 复用 [Fsrs]，这里只负责「用户语义 → 引擎语义」的映射与业务判定。
 */
object BookRecallLogic {

    /** 单次翻页达到这个页数，视为跨越一章 */
    const val CHAPTER_STEP_PAGES = 10

    /** 合书回忆默认自问的精加工提问模板（Dunlosky 2013 列为中等效用的自我提问） */
    val RECALL_QUESTION_TEMPLATES: List<RecallQuestionTemplate> = listOf(
        RecallQuestionTemplate("作者为何这么说", "这一章作者为什么要这么说？"),
        RecallQuestionTemplate("这节解决什么", "这一节究竟在解决什么问题？"),
        RecallQuestionTemplate("用自己的话复述", "合上书，用自己的话把这一章的主张复述一遍。"),
        RecallQuestionTemplate("例子说明什么", "刚才那个例子到底想说明什么？"),
        RecallQuestionTemplate("与上一章的关系", "这一章和上一章之间是什么关系？"),
    )

    /** 自评三档 → FSRS 评分：0 没想起来=AGAIN，1 部分=HARD，2 完整=GOOD */
    fun ratingForSelfScore(selfScore: Int): Rating = when (selfScore.coerceIn(0, 2)) {
        0 -> Rating.AGAIN
        1 -> Rating.HARD
        else -> Rating.GOOD
    }

    /** 先答后评：没写下回忆内容就不允许自评 */
    fun canSelfAssess(answer: String): Boolean = answer.isNotBlank()

    /**
     * 首次检索练习的下次到期时刻。
     *
     * AGAIN 走当天重学（[Fsrs.RELEARN_MS] 后回到队列，仍是当天）；其余按初始稳定性
     * 排到未来若干天，因此完整想起（GOOD）比部分想起（HARD）更远。
     */
    fun firstReviewDueAt(rating: Rating, now: Long, retention: Double = Fsrs.DEFAULT_RETENTION): Long =
        if (rating == Rating.AGAIN) {
            now + Fsrs.RELEARN_MS
        } else {
            Fsrs.dueAt(Fsrs.firstRating(rating, now), retention)
        }

    /** 由自评档位直接算下次到期 */
    fun nextReviewAt(selfScore: Int, now: Long): Long =
        firstReviewDueAt(ratingForSelfScore(selfScore), now)

    /** 对一条已有记忆状态做复习排期；从未入过队（reps==0）时走首次路径 */
    fun scheduleExcerpt(state: MemoryState, rating: Rating, now: Long): SchedulingResult {
        if (state.reps > 0) return Fsrs.review(state, rating, now)
        val first = Fsrs.firstRating(rating, now)
        val interval = if (rating == Rating.AGAIN) 0 else Fsrs.intervalDays(first.stability, Fsrs.DEFAULT_RETENTION)
        return SchedulingResult(
            state = first,
            dueAt = firstReviewDueAt(rating, now),
            intervalDays = interval,
            desiredRetention = Fsrs.DEFAULT_RETENTION,
        )
    }

    /** 翻页是否跨越一章：单次前进 ≥ [CHAPTER_STEP_PAGES]，或到达书的末尾 */
    fun crossedChapter(fromPage: Int, toPage: Int, totalPages: Int): Boolean {
        val forward = toPage - fromPage
        val reachedEnd = totalPages > 0 && toPage >= totalPages
        return forward >= CHAPTER_STEP_PAGES || reachedEnd
    }

    /** 书摘入队判定：勾选加入复习队列、且内容非空，才真正入队 */
    fun shouldEnqueueExcerpt(enqueue: Boolean, content: String): Boolean = enqueue && content.isNotBlank()

    /** 检索练习次数的展示文案，取代旧的「书摘总数」指标 */
    fun recallCountLabel(count: Int): String =
        if (count <= 0) "还没有检索练习" else "$count 次检索练习"

    /** 跨越一章时的事件 */
    fun chapterEvent(crossed: Boolean): TipEvent? = if (crossed) TipEvent.ChapterFinished else null

    /** 有书摘却零检索时，提示「划线不等于保留」 */
    fun excerptOnlyEvent(excerptCount: Int, recallCount: Int): TipEvent? =
        if (excerptCount > 0 && recallCount == 0) TipEvent.ExcerptOnlyNoRecall else null

    /** 便捷：事件对应的提示文案（无匹配事件返回 null） */
    fun tipFor(event: TipEvent?): Tip? = event?.let { StudyTips.forEvent(it) }
}

/** 书架页单项展示模型 */
data class BookItemUi(val book: Book) {
    /** 阅读进度：当前页 / 总页数（总页数为 0 时记 0，防除零） */
    val progress: Float
        get() = if (book.totalPages > 0) {
            (book.currentPage.toFloat() / book.totalPages).coerceIn(0f, 1f)
        } else 0f

    val percent: Int
        get() = (progress * 100).toInt()

    val isFinished: Boolean
        get() = book.status == Book.STATUS_FINISHED
}

/** 书架页状态 */
data class BookShelfUiState(
    val items: List<BookItemUi> = emptyList(),
    val readingCount: Int = 0,
    val finishedCount: Int = 0,
    /** 全库检索练习（合书回忆）总数，取代旧的「书摘总数」展示指标 */
    val recallCount: Int = 0,
)

/** 详情页展示模型：书籍 + 书摘 + 书评 + 检索练习计数 */
data class BookDetailUi(
    val book: Book,
    val excerpts: List<Excerpt>,
    val reviews: List<BookReview>,
    val recallCount: Int = 0,
    /** 快照时刻，用于判定哪些书摘已进入复习到期窗口 */
    val nowMs: Long = System.currentTimeMillis(),
) {
    val progress: Float
        get() = if (book.totalPages > 0) {
            (book.currentPage.toFloat() / book.totalPages).coerceIn(0f, 1f)
        } else 0f

    val percent: Int
        get() = (progress * 100).toInt()

    val isFinished: Boolean
        get() = book.status == Book.STATUS_FINISHED

    /** 已进入复习队列且当前到期的书摘 */
    val dueExcerpts: List<Excerpt>
        get() = excerpts.filter { it.nextReviewAt in 1L..nowMs }

    val dueExcerptCount: Int
        get() = dueExcerpts.size

    /** 检索练习次数展示文案 */
    val recallLabel: String
        get() = BookRecallLogic.recallCountLabel(recallCount)

    /** 有书摘却零检索：进度百分比不等于理解程度，挂提示 */
    val showRecallNotesTip: Boolean
        get() = BookRecallLogic.excerptOnlyEvent(excerpts.size, recallCount) != null
}

class BookViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = (application as StudyKitApp).container.bookRepository

    // ── 书架页状态：书籍流 × 检索练习总数流 ─────────────────────────────
    val shelfState: StateFlow<BookShelfUiState> =
        combine(repository.observeAll(), repository.observeRecallCount()) { books, recallCount ->
            val items = books.map(::BookItemUi)
            BookShelfUiState(
                items         = items,
                readingCount  = items.count { !it.isFinished },
                finishedCount = items.count { it.isFinished },
                recallCount   = recallCount,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BookShelfUiState())

    /** 单书流：供编辑页回填数据 */
    fun observeBook(bookId: Long): Flow<Book?> = repository.observeById(bookId)

    /** 单条书摘流：供书摘编辑页回填 */
    fun observeExcerpt(excerptId: Long): Flow<Excerpt?> = repository.observeExcerpt(excerptId)

    /** 单条书评流：供书评编辑页回填 */
    fun observeReview(reviewId: Long): Flow<BookReview?> = repository.observeReview(reviewId)

    /** 某本书的合书回忆列表：供详情页展示 */
    fun observeRecalls(bookId: Long): Flow<List<BookRecall>> = repository.observeRecalls(bookId)

    // ── 详情页状态 ──────────────────────────────────────────────────────
    private val _detail = MutableStateFlow<BookDetailUi?>(null)
    val detail: StateFlow<BookDetailUi?> = _detail
    private var detailJob: Job? = null

    // ── 章末提示：一次性的瞬时 Tip，读完一段可手动收起 ──────────────────
    private val _chapterTip = MutableStateFlow<Tip?>(null)
    val chapterTip: StateFlow<Tip?> = _chapterTip

    fun dismissChapterTip() {
        _chapterTip.value = null
    }

    fun loadDetail(bookId: Long) {
        if (_detail.value?.book?.id == bookId) return
        detailJob?.cancel()
        detailJob = viewModelScope.launch {
            combine(
                repository.observeById(bookId),
                repository.observeExcerpts(bookId),
                repository.observeReviews(bookId),
                repository.observeRecallCount(bookId),
            ) { book, excerpts, reviews, recallCount ->
                if (book == null) {
                    null
                } else {
                    BookDetailUi(
                        book        = book,
                        excerpts    = excerpts,
                        reviews     = reviews,
                        recallCount = recallCount,
                        nowMs       = System.currentTimeMillis(),
                    )
                }
            }.collect { _detail.value = it }
        }
    }

    // ── 书籍操作 ────────────────────────────────────────────────────────
    /** 新建或更新书籍 */
    fun saveBook(bookId: Long?, title: String, author: String, totalPages: Int, onSaved: () -> Unit) {
        viewModelScope.launch {
            if (bookId == null) {
                repository.add(title.trim(), author.trim(), totalPages)
            } else {
                val book = repository.getById(bookId)
                if (book != null) {
                    repository.update(
                        book.copy(
                            title       = title.trim(),
                            author      = author.trim(),
                            totalPages  = totalPages,
                            currentPage = book.currentPage.coerceAtMost(totalPages),
                        ),
                    )
                }
            }
            onSaved()
        }
    }

    /** 页码步进（+/-），越界自动夹取到 0..totalPages；到达总页数时标记读完 */
    fun stepProgress(delta: Int) {
        val book = _detail.value?.book ?: return
        val target = (book.currentPage + delta).coerceIn(0, book.totalPages)
        // 跨越一章：挂一次「精加工提问」提示，引导把刚读的内容变成一次自测
        if (BookRecallLogic.crossedChapter(book.currentPage, target, book.totalPages)) {
            _chapterTip.value = BookRecallLogic.tipFor(BookRecallLogic.chapterEvent(crossed = true))
        }
        viewModelScope.launch {
            if (target >= book.totalPages && book.status == Book.STATUS_READING) {
                repository.markFinished(book.id)
                toast("已读完《${book.title}》")
            } else {
                repository.updateProgress(book.id, target)
            }
        }
    }

    /** 直接标记读完 */
    fun markFinished() {
        val book = _detail.value?.book ?: return
        if (book.status == Book.STATUS_FINISHED) return
        viewModelScope.launch {
            repository.markFinished(book.id)
            toast("已读完《${book.title}》")
        }
    }

    // ── 书摘操作 ────────────────────────────────────────────────────────
    /**
     * 保存书摘。新建时若勾选「加入复习队列」，会给一个次日到期的初始排期。
     */
    fun saveExcerpt(
        excerptId: Long?,
        bookId: Long,
        content: String,
        pageNo: Int?,
        enqueue: Boolean = false,
        onSaved: () -> Unit,
    ) {
        viewModelScope.launch {
            if (excerptId == null) {
                repository.addExcerpt(bookId, content.trim(), pageNo, enqueue)
            } else {
                repository.getExcerpt(excerptId)?.let {
                    repository.updateExcerpt(it.copy(content = content.trim(), pageNo = pageNo))
                }
            }
            onSaved()
        }
    }

    fun deleteExcerpt(excerptId: Long, onDeleted: () -> Unit) {
        viewModelScope.launch {
            repository.getExcerpt(excerptId)?.let { repository.deleteExcerpt(it) }
            onDeleted()
        }
    }

    /**
     * 复习一条书摘：按自评档位映射 FSRS 评分，走间隔调度写回书摘记忆状态。
     *
     * 书摘第一次复习（reps==0）走首次排期，之后按 [Fsrs.review] 演化稳定性。
     */
    fun reviewExcerpt(excerpt: Excerpt, selfScore: Int) {
        val rating = BookRecallLogic.ratingForSelfScore(selfScore)
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val state = MemoryState(
                stability = excerpt.stability,
                difficulty = excerpt.difficulty,
                lastReviewAt = if (excerpt.lastReviewAt > 0L) excerpt.lastReviewAt else now,
                reps = excerpt.reps,
                lapses = excerpt.lapses,
            )
            repository.scheduleExcerptReview(excerpt.id, BookRecallLogic.scheduleExcerpt(state, rating, now))
        }
    }

    // ── 合书回忆（检索练习）操作 ────────────────────────────────────────
    /**
     * 记录一次合书回忆：先写下回忆、对照原文自评后再排期。
     *
     * 未写回忆（answer 空白）时直接拒绝——遵循「先答后评」，避免看着答案给自己打分。
     */
    fun saveRecall(
        bookId: Long,
        pageNo: Int?,
        question: String,
        answer: String,
        selfScore: Int,
        onSaved: () -> Unit = {},
    ) {
        if (!BookRecallLogic.canSelfAssess(answer)) {
            toast("先合上书写下回忆，再对照原文自评")
            return
        }
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            repository.addRecall(
                bookId = bookId,
                pageNo = pageNo,
                question = question.trim(),
                answer = answer.trim(),
                selfScore = selfScore,
                nextReviewAt = BookRecallLogic.nextReviewAt(selfScore, now),
            )
            toast("已记一次检索练习")
            onSaved()
        }
    }

    // ── 书评操作 ────────────────────────────────────────────────────────
    fun saveReview(reviewId: Long?, bookId: Long, rating: Int, content: String, onSaved: () -> Unit) {
        viewModelScope.launch {
            if (reviewId == null) {
                repository.addReview(bookId, rating, content.trim())
            } else {
                repository.getReview(reviewId)?.let {
                    repository.updateReview(it.copy(rating = rating, content = content.trim()))
                }
            }
            onSaved()
        }
    }

    private fun toast(message: String) {
        Toast.makeText(getApplication(), message, Toast.LENGTH_SHORT).show()
    }
}

