package com.studykit.ui.book

import android.app.Application
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.studykit.StudyKitApp
import com.studykit.data.entity.Book
import com.studykit.data.entity.BookReview
import com.studykit.data.entity.Excerpt
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
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
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BookShelfUiState())

    /** 单书流：供编辑页回填数据 */
    fun observeBook(bookId: Long): Flow<Book?> = repository.observeById(bookId)

    /** 单条书摘流：供书摘编辑页回填 */
    fun observeExcerpt(excerptId: Long): Flow<Excerpt?> = repository.observeExcerpt(excerptId)

    /** 单条书评流：供书评编辑页回填 */
    fun observeReview(reviewId: Long): Flow<BookReview?> = repository.observeReview(reviewId)

    // ── 详情页状态 ──────────────────────────────────────────────────────
    private val _detail = MutableStateFlow<BookDetailUi?>(null)
    val detail: StateFlow<BookDetailUi?> = _detail
    private var detailJob: Job? = null

    fun loadDetail(bookId: Long) {
        if (_detail.value?.book?.id == bookId) return
        detailJob?.cancel()
        detailJob = viewModelScope.launch {
            combine(
                repository.observeById(bookId),
                repository.observeExcerpts(bookId),
                repository.observeReviews(bookId),
            ) { book, excerpts, reviews ->
                if (book == null) null else BookDetailUi(book, excerpts, reviews)
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
    fun saveExcerpt(excerptId: Long?, bookId: Long, content: String, pageNo: Int?, onSaved: () -> Unit) {
        viewModelScope.launch {
            if (excerptId == null) {
                repository.addExcerpt(bookId, content.trim(), pageNo)
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

