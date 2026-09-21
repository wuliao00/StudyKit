package com.studykit.ui.study

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.studykit.StudyKitApp
import com.studykit.data.entity.WordList
import com.studykit.data.remote.DictRemote
import com.studykit.data.remote.DictRemoteException
import com.studykit.ui.bulkimport.DictSourceMeta
import com.studykit.ui.bulkimport.ImportViewModel
import com.studykit.util.importer.DictBookInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 取最深一层带 message 的异常。外层包装只会说"词表下载失败"，
 * 真正能判断的是 `UnknownHostException` / `SSLException` / `FileNotFoundException` 这类根因。
 */
private fun Throwable.rootMessage(): String {
    var cursor: Throwable? = this
    var best: String? = message
    while (cursor != null) {
        cursor.message?.takeIf { it.isNotBlank() }?.let { best = it }
        cursor = cursor.cause
    }
    return best ?: javaClass.simpleName
}

enum class DictStorePhase { LOADING, READY, FAILED }

data class DictStoreUiState(
    val phase: DictStorePhase = DictStorePhase.LOADING,
    val books: List<DictBookInfo> = emptyList(),
    val imported: List<WordList> = emptyList(),
    /** 词库 id → 下载进度（0f..1f）；没有键表示未在下载 */
    val progress: Map<String, Float> = emptyMap(),
    val query: String = "",
    val error: String? = null,
    /** 目录来源：null 还没拉过，true 在线，false 回落到内置快照（界面必须区分，见 DictStoreScreen） */
    val online: Boolean? = null,
) {
    /** 搜索命中书名或任一标签；空查询返回全部 */
    val visibleBooks: List<DictBookInfo>
        get() = if (query.isBlank()) books else books.filter {
            it.title.contains(query, ignoreCase = true) ||
                it.tags.any { tag -> tag.contains(query, ignoreCase = true) }
        }

    val availableBooks: List<DictBookInfo>
        get() = visibleBooks.filterNot { book -> imported.any { it.sourceId == book.id } }
}

/**
 * 词库商店。三态（加载中 / 就绪 / 失败可重试）+ 每本独立进度。
 *
 * 下载完成后把解析计划交给 [ImportViewModel] 走统一的预览-入库流程 —— 商店自己**不直接写 words 表**，
 * 这样「3000 个词里有一行脏数据」和「重复词」的处理只有一份实现。
 */
class DictStoreViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as StudyKitApp).container
    private val remote = DictRemote(application)
    private val wordListRepository = container.wordListRepository

    private val _books = MutableStateFlow(DictStoreUiState())
    private val _progress = MutableStateFlow<Map<String, Float>>(emptyMap())
    private val _query = MutableStateFlow("")

    val state: StateFlow<DictStoreUiState> =
        combine(_books, wordListRepository.observeAll(), _progress, _query) { base, imported, progress, query ->
            base.copy(imported = imported, progress = progress, query = query)
        }.flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DictStoreUiState())

    init {
        refresh()
    }

    fun setQuery(query: String) {
        _query.value = query
    }

    fun refresh() {
        _books.value = _books.value.copy(phase = DictStorePhase.LOADING, error = null)
        viewModelScope.launch {
            runCatching { remote.loadCatalogue() }
                .onSuccess { result ->
                    _books.value = _books.value.copy(
                        phase = DictStorePhase.READY,
                        books = result.books,
                        online = result.fromNetwork,
                        error = null,
                    )
                }
                .onFailure { error ->
                    _books.value = _books.value.copy(
                        phase = DictStorePhase.FAILED,
                        error = if (error is DictRemoteException) error.message else "网络异常，稍后再试",
                    )
                }
        }
    }

    /** 下载 + 解析；完成后由界面导航到预览页。方法名不能叫 `import`（Kotlin 关键字） */
    fun importBook(book: DictBookInfo, importViewModel: ImportViewModel, onReady: () -> Unit) {
        if (_progress.value.containsKey(book.id)) return
        _progress.value = _progress.value + (book.id to 0f)
        viewModelScope.launch {
            try {
                val plan = remote.downloadBook(book) { ratio ->
                    _progress.value = _progress.value + (book.id to ratio)
                }
                importViewModel.loadPlan(
                    plan,
                    DictSourceMeta(sourceId = book.id, title = book.title, wordNum = book.wordNum),
                )
                onReady()
            } catch (error: Exception) {
                // 失败必须带原因：只说"可重试"的话，用户不知道该等网络、还是这本根本没有词表、
                // 还是我们的解析器挂了 —— 而这三种只有第一种值得重试
                _books.value = _books.value.copy(
                    error = "《${book.title}》导入失败：${error.rootMessage()}",
                )
            } finally {
                _progress.value = _progress.value - book.id
            }
        }
    }

    /** 整表撤销：删词库记录 + 它带进来的所有单词，回条数给界面提示 */
    fun deleteList(list: WordList, onDone: (Int) -> Unit) {
        viewModelScope.launch {
            onDone(withContext(Dispatchers.IO) { wordListRepository.deleteAlongWithWords(list) })
        }
    }
}
