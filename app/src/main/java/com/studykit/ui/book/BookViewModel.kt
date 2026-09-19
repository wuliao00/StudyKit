package com.studykit.ui.book

import android.app.Application
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.studykit.StudyKitApp
import com.studykit.data.entity.Book
import com.studykit.data.entity.BookReview
import com.studykit.data.entity.Excerpt
import com.studykit.util.OneShotGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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
    val excerptCount: Int = 0,
)

/** 详情页展示模型：书籍 + 书摘 + 书评 */
data class BookDetailUi(
    val book: Book,
    val excerpts: List<Excerpt>,
    val reviews: List<BookReview>,
) {
    val progress: Float
        get() = if (book.totalPages > 0) {
            (book.currentPage.toFloat() / book.totalPages).coerceIn(0f, 1f)
        } else 0f

    val percent: Int
        get() = (progress * 100).toInt()

    val isFinished: Boolean
        get() = book.status == Book.STATUS_FINISHED
}

class BookViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = (application as StudyKitApp).container.bookRepository

    // ── 书架页状态：书籍流 × 书摘总数流 ─────────────────────────────────
    val shelfState: StateFlow<BookShelfUiState> =
        combine(repository.observeAll(), repository.observeExcerptCount()) { books, excerptCount ->
            val items = books.map(::BookItemUi)
            BookShelfUiState(
                items         = items,
                readingCount  = items.count { !it.isFinished },
                finishedCount = items.count { it.isFinished },
                excerptCount  = excerptCount,
            )
        }
            // 整表 map + 两趟 count：进度每写一次都要重算，别落在主线（终审 I7）
            .flowOn(context = Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BookShelfUiState())

    /** 单书流：供编辑页回填数据 */
    fun observeBook(bookId: Long): Flow<Book?> = repository.observeById(bookId)

    /** 单条书摘流：供书摘编辑页回填 */
    fun observeExcerpt(excerptId: Long): Flow<Excerpt?> = repository.observeExcerpt(excerptId)

    /** 单条书评流：供书评编辑页回填 */
    fun observeReview(reviewId: Long): Flow<BookReview?> = repository.observeReview(reviewId)

    // ── 详情页状态 ──────────────────────────────────────────────────────
    /** 页面请求的书本 id（route 上的 `bookId`），由页面自己的 LaunchedEffect 写入 */
    private val _detailId = MutableStateFlow<Long?>(null)
    val detailId: StateFlow<Long?> = _detailId

    /**
     * 详情页状态。
     *
     * 旧写法是 `viewModelScope.launch { combine(...).collect { _detail.value = it } }`
     * （终审 I12）：订阅挂在 `viewModelScope` 上，离开详情页后它照样跟着书摘/书评的每次
     * 写库重算一遍。改成 flatMapLatest + `stateIn(WhileSubscribed(5_000))`，形态与
     * `MistakeViewModel.detailState`、`HabitViewModel.detail` 一致：无人订阅即退订。
     */
    // flatMapLatest 仍是实验 API：opt-in 只挂在调用点
    @OptIn(ExperimentalCoroutinesApi::class)
    val detail: StateFlow<BookDetailUi?> = _detailId
        .flatMapLatest { id ->
            if (id == null) {
                flowOf(null)
            } else {
                combine(
                    repository.observeById(id),
                    repository.observeExcerpts(id),
                    repository.observeReviews(id),
                ) { book, excerpts, reviews ->
                    if (book == null) null else BookDetailUi(book, excerpts, reviews)
                }
            }
        }
        // 这一段只是把三条流拼成一个不可变快照，没有 transform/分组/解析，故不加 flowOn
        // （终审 I7 的自查口径：纯组装直传不加）
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun loadDetail(bookId: Long) {
        _detailId.value = bookId
    }

    // ── 书籍操作 ────────────────────────────────────────────────────────
    // 三个「写完即 pop」的入口各一枚门（终审 C4）：连点会双插库 / 双改行 + 双 pop。
    // 门开在 VM 侧，六个保存入口因此共用同一套机制，页面不再各写一份 enabled 态。
    private val savingBook = OneShotGate()
    private val savingExcerpt = OneShotGate()
    private val savingReview = OneShotGate()
    private val deletingExcerpt = OneShotGate()

    /** 新建或更新书籍 */
    fun saveBook(bookId: Long?, title: String, author: String, totalPages: Int, onSaved: () -> Unit) {
        if (!savingBook.tryEnter()) return
        viewModelScope.launch {
            try {
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
            } finally {
                savingBook.leave()
            }
        }
    }

    /**
     * 页码步进（+/-），越界自动夹取到 0..totalPages；到达总页数时标记读完。
     *
     * 以路由上的 [bookId] 为准绳（终审 C1）：只有「已加载的书就是本书」时才写库，否则直接返回。
     * 早退不会误报失败——页面此时正显示加载态，按钮也还没出现。
     */
    fun stepProgress(bookId: Long, delta: Int) {
        val book = detail.value?.book?.takeIf { it.id == bookId } ?: return
        val target = (book.currentPage + delta).coerceIn(0, book.totalPages)
        viewModelScope.launch {
            if (target >= book.totalPages && book.status == Book.STATUS_READING) {
                repository.markFinished(book.id)
                toast("已读完《${book.title}》")
            } else {
                repository.updateProgress(book.id, target)
            }
        }
    }

    /** 直接标记读完；同样只认 [bookId] 对应的那一行 */
    fun markFinished(bookId: Long) {
        val book = detail.value?.book?.takeIf { it.id == bookId } ?: return
        if (book.status == Book.STATUS_FINISHED) return
        viewModelScope.launch {
            repository.markFinished(book.id)
            toast("已读完《${book.title}》")
        }
    }

    // ── 书摘操作 ────────────────────────────────────────────────────────
    fun saveExcerpt(excerptId: Long?, bookId: Long, content: String, pageNo: Int?, onSaved: () -> Unit) {
        if (!savingExcerpt.tryEnter()) return
        viewModelScope.launch {
            try {
                if (excerptId == null) {
                    repository.addExcerpt(bookId, content.trim(), pageNo)
                } else {
                    repository.getExcerpt(excerptId)?.let {
                        repository.updateExcerpt(it.copy(content = content.trim(), pageNo = pageNo))
                    }
                }
                onSaved()
            } finally {
                savingExcerpt.leave()
            }
        }
    }

    /**
     * 删除书摘。
     *
     * 与三个保存入口同一把尺子的**同类站点**（终审 C4）：`ExcerptEditScreen` 的「删除书摘」
     * 既没有确认对话框也没有页面侧的一次性返回门，连点两次会走两遍 `onDeleted()` → 多 pop 一层。
     */
    fun deleteExcerpt(excerptId: Long, onDeleted: () -> Unit) {
        if (!deletingExcerpt.tryEnter()) return
        viewModelScope.launch {
            try {
                repository.getExcerpt(excerptId)?.let { repository.deleteExcerpt(it) }
                onDeleted()
            } finally {
                deletingExcerpt.leave()
            }
        }
    }

    // ── 书评操作 ────────────────────────────────────────────────────────
    fun saveReview(reviewId: Long?, bookId: Long, rating: Int, content: String, onSaved: () -> Unit) {
        if (!savingReview.tryEnter()) return
        viewModelScope.launch {
            try {
                if (reviewId == null) {
                    repository.addReview(bookId, rating, content.trim())
                } else {
                    repository.getReview(reviewId)?.let {
                        repository.updateReview(it.copy(rating = rating, content = content.trim()))
                    }
                }
                onSaved()
            } finally {
                savingReview.leave()
            }
        }
    }

    private fun toast(message: String) {
        Toast.makeText(getApplication(), message, Toast.LENGTH_SHORT).show()
    }
}

