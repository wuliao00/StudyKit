package com.studykit.ui.study

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.studykit.StudyKitApp
import com.studykit.util.importer.ImportPlan
import com.studykit.util.importer.PackLoad
import com.studykit.util.importer.PackOrigin
import com.studykit.util.importer.QuestionPackCatalog
import com.studykit.util.importer.QuestionPackInfo
import com.studykit.util.importer.QuestionPackStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class QuestionStoreUiState(
    val catalog: QuestionPackCatalog? = null,
    val loading: Boolean = true,
    val downloadingId: String? = null,
    val error: String? = null,
    /**
     * v3.5：阶段存储值 → 本机已有题量。
     *
     * 商店卡片据此显示「已入库 60 题」，避免同一包重复导入之后用户还以为没下过。
     * 导入走的是批量导入那条路，写完库这里会自动跟着变，不需要手动刷新。
     */
    val importedByStage: Map<String, Int> = emptyMap(),
    /**
     * v3.5+：这一包是从哪儿来的（在线 / 本机缓存 / 内置副本）。
     *
     * 下载失败但用缓存或内置兜住时，这句话会带上前一次失败的原因 —— 不能让人以为联网成功了。
     */
    val notice: String? = null,
)

/**
 * 题库商店：目录来自 assets 的 manifest，题包解析复用 [com.studykit.util.importer.QuestionLineParser]。
 *
 * 下载成功后不直接写库，而是把 [ImportPlan] 交给 `ImportViewModel` 走既有预览/去重/结果流程
 * —— 与批量导入、拍照录题同一条入库路，商店不 fork 第二套写库逻辑。
 */
class QuestionStoreViewModel(application: Application) : AndroidViewModel(application) {

    private val store = QuestionPackStore(application)

    private val _state = MutableStateFlow(QuestionStoreUiState())
    val state: StateFlow<QuestionStoreUiState> = _state.asStateFlow()

    init {
        reload()
        observeImported()
    }

    /** v3.5：订阅本机题量（按阶段聚合），页面销毁时随 viewModelScope 一起取消。 */
    private fun observeImported() {
        val container = (getApplication<Application>() as StudyKitApp).container
        viewModelScope.launch {
            container.questionRepository.observeStageCounts().collect { counts ->
                _state.value = _state.value.copy(importedByStage = counts)
            }
        }
    }

    fun reload() {
        _state.value = _state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { store.loadCatalog() } }
                .onSuccess { catalog ->
                    _state.value = _state.value.copy(
                        catalog = catalog,
                        loading = false,
                        error = if (catalog.packs.isEmpty()) "题库目录为空" else null,
                    )
                }
                .onFailure { error ->
                    _state.value = _state.value.copy(
                        loading = false,
                        error = "题库目录读不出来：${error.localizedMessage ?: "未知原因"}",
                    )
                }
        }
    }

    fun download(pack: QuestionPackInfo, onReady: (ImportPlan) -> Unit) {
        if (_state.value.downloadingId != null) return
        _state.value = _state.value.copy(downloadingId = pack.id, error = null, notice = null)
        viewModelScope.launch {
            runCatching { store.loadPack(pack) }
                .onSuccess { load ->
                    _state.value = _state.value.copy(
                        downloadingId = null,
                        notice = noticeFor(load, pack.title),
                    )
                    onReady(load.plan)
                }
                .onFailure { error ->
                    _state.value = _state.value.copy(
                        downloadingId = null,
                        error = "下载失败：${error.localizedMessage ?: "未知原因"}",
                    )
                }
        }
    }

    /**
     * v3.5+：从任意链接导入（不查目录，也不校验 sha256 —— 没有目录就无从校验）。
     * 与商店下载共用预览那条入库路。
     */
    fun downloadFromUrl(rawUrl: String, onReady: (ImportPlan) -> Unit) {
        if (_state.value.downloadingId != null) return
        _state.value = _state.value.copy(downloadingId = "url", error = null, notice = null)
        viewModelScope.launch {
            runCatching { store.loadPackFromUrl(rawUrl) }
                .onSuccess { load ->
                    _state.value = _state.value.copy(
                        downloadingId = null,
                        notice = "已从链接读取 ${load.plan.items.size} 题，先看预览再决定导入哪些",
                    )
                    onReady(load.plan)
                }
                .onFailure { error ->
                    _state.value = _state.value.copy(
                        downloadingId = null,
                        error = "读取失败：${error.localizedMessage ?: "未知原因"}",
                    )
                }
        }
    }

    /** 把「来源 + 若回落的失败原因」拼成一句给界面看的话 */
    private fun noticeFor(load: PackLoad, title: String): String {
        val head = when (load.origin) {
            PackOrigin.REMOTE -> "已从在线地址下载《$title》"
            PackOrigin.CACHE -> "在线不可用，已改用本机缓存《$title》"
            PackOrigin.BUNDLED -> "在线不可用，已改用内置副本《$title》"
            PackOrigin.LINK -> "已从链接读取《$title》"
        }
        val reason = load.warning?.takeIf { it.isNotBlank() } ?: return head
        return "$head（$reason）"
    }
}
