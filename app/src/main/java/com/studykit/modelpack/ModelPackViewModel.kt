package com.studykit.modelpack

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 包根目录名（相对 `filesDir`）；生产与单测同一个常量，避免两处字面量漂移。 */
internal const val MODEL_PACK_ROOT_DIR = "model-packs"

/**
 * 设置页"模型包（可选）"分区的编排层（计划 T7 / §3.8）。
 */
class ModelPackViewModel(
    app: Application,
    private val store: ModelPackStore = ModelPackStore(File(app.filesDir, MODEL_PACK_ROOT_DIR)),
    private val downloader: PackDownloader = PackDownloader(),
    private val registry: List<PackSeed> = PackRegistry.all,
    private val log: (String, Throwable?) -> Unit = { message, error ->
        if (error == null) Log.i(TAG, message) else Log.w(TAG, message, error)
    },
) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(ModelPackUiState())

    /** 界面只读这一条流。 */
    val state: StateFlow<ModelPackUiState> = _state.asStateFlow()

    private val downloadMutex = Mutex()
    private val cancelRequested = AtomicBoolean(false)
    private var downloadJob: Job? = null
    private var currentDownloadId: String? = null

    /** 重新扫一遍目录并刷新六行与总账；进入分区时调一次。 */
    fun refresh() {
        viewModelScope.launch { refreshNow() }
    }

    /** 下载并安装一个包。已有下载在进行时本次点击被吃掉（不排队，见类 KDoc）。 */
    fun download(id: String) {
        if (downloadJob?.isActive == true) return
        cancelRequested.set(false)
        currentDownloadId = id
        downloadJob = viewModelScope.launch {
            try {
                downloadNow(id)
            } finally {
                currentDownloadId = null
            }
        }
    }

    /** 取消当前下载；不是当前 id 的取消请求直接忽略（防止误点别的行把当前下载掐掉）。 */
    fun cancel(id: String) {
        cancelRequested.set(true)
        val job = downloadJob
        if (job != null && job.isActive && currentDownloadId == id) job.cancel()
    }

    /** 删除一个已装包（不可逆，调用方必须已经过用户确认）。 */
    fun delete(id: String) {
        viewModelScope.launch { deleteNow(id) }
    }

    /** 启用/停用一个已装包（写包目录的 state.json，不动 app_setting）。 */
    fun setEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch { setEnabledNow(id, enabled) }
    }

    /** 关闭预算拒装弹层（取消 = 什么都不删）。 */
    fun dismissBudgetReject() {
        _state.value = _state.value.copy(budgetReject = null)
    }

    // ── 可单测的 suspend 编排 ───────────────────────────────────────────

    /**
     * 扫目录 → 拼六行 → 更新总账。
     *
     * 扫描失败（极端 IO 错误）只降级成一句可读提示，不清空已有行：界面上"上次的数据 + 一句原因"
     * 比"整块变空"更接近事实。
     */
    suspend fun refreshNow() {
        val scan = runCatching { store.scan() }.getOrElse { error ->
            log("扫描模型包目录失败", error)
            _state.value = _state.value.withMessage(ModelPackCopy.readFailed())
            return
        }
        val rows = ModelPackRows.build(
            registry = registry,
            installed = scan.packs.associateBy { it.manifest.id },
            brokenIds = scan.brokenIds.toSet(),
        )
        _state.value = _state.value.copy(
            rows = rows,
            installedBytes = scan.packs.sumOf { it.manifest.sizeBytes },
        )
    }

    /**
     * 下载 → 校验 → 安装一条龙，返回是否真的装上。
     *
     * 顺序里有三处"先收紧、再动手"：
     * 1. 未上架（manifest 为空）直接拒绝，一个网络请求都不发；
     * 2. 预算判定在下载**之前**（装不下就别浪费用户的流量与时间）；
     * 3. staging 校验通过后才 commit（store 自己会再校验一遍 sha256）。
     *
     * 取消/失败/校验不过都会删 staging 并把行恢复成未安装态（文件的清理在下载器与 store 里，
     * 这里只重建界面状态）。
     */
    suspend fun downloadNow(id: String): Boolean = downloadMutex.withLock {
        val seed = registry.firstOrNull { it.id == id }
        val manifest = seed?.manifest
        if (seed == null || manifest == null) {
            setMessage(ModelPackCopy.messageNotPublished())
            return@withLock false
        }

        if (_state.value.rows.isEmpty()) refreshNow()

        // 预算：已装口径只认 store.usages()，与顶部总账同一份 scan
        val decision = runCatching {
            BudgetGuard.check(
                installed = store.usages(),
                incoming = PackUsage(manifest.id, seed.displayName, manifest.sizeBytes),
            )
        }.getOrElse { error ->
            log("预算判定失败：$id", error)
            setMessage(ModelPackCopy.readFailed())
            return@withLock false
        }
        if (!decision.allowed) {
            val ui = BudgetRejectUi(
                incomingId = id,
                removalId = decision.suggestRemove?.id,
                title = ModelPackCopy.budgetDialogTitle(),
                body = ModelPackCopy.budgetReject(decision, seed.displayName),
            )
            _state.value = _state.value.copy(budgetReject = ui, message = null)
            return@withLock false
        }

        // 上一次崩溃残留的 .partial 在这里清（不能放在 refresh：会删掉正在下载的 staging）
        runCatching { store.cleanupPartials() }
            .onFailure { log("清理残留暂存目录失败", it) }

        cancelRequested.set(false)
        _state.value = _state.value
            .copy(budgetReject = null, message = null)
            .withRowStatus(id, PackRowStatus.Downloading(0f))

        val result = try {
            downloader.download(
                manifest = manifest,
                stagingDir = store.stagingDirOf(id),
                onProgress = { progress ->
                    _state.value = _state.value.withRowStatus(id, PackRowStatus.Downloading(progress))
                },
                isCancelled = { cancelRequested.get() },
            )
        } catch (e: CancellationException) {
            // 协程被 cancel()：staging 已在下载器的 finally 里删掉，这里只把界面收回来
            withContext(NonCancellable) {
                refreshNow()
                setMessage(ModelPackCopy.messageCancelled())
            }
            throw e
        } catch (t: Throwable) {
            log("下载模型包异常：$id", t)
            refreshNow()
            setMessage(ModelPackCopy.downloadFailed(DownloadFailure.NETWORK))
            return@withLock false
        }

        return@withLock when (result) {
            is DownloadResult.Cancelled -> {
                refreshNow()
                setMessage(ModelPackCopy.messageCancelled())
                false
            }
            is DownloadResult.Failed -> {
                val detail = result.detail?.let { " / $it" }.orEmpty()
                log("下载失败：$id（${result.reason}）$detail", null)
                refreshNow()
                setMessage(ModelPackCopy.downloadFailed(result.reason))
                false
            }
            is DownloadResult.Success -> {
                _state.value = _state.value.withRowStatus(id, PackRowStatus.Verifying)
                try {
                    store.commit(id, manifest)
                } catch (t: Throwable) {
                    log("安装模型包失败：$id", t)
                    refreshNow()
                    setMessage(ModelPackCopy.actionFailed())
                    return@withLock false
                }
                refreshNow()
                setMessage(ModelPackCopy.messageInstalled(seed.displayName))
                true
            }
        }
    }

    /** 删除并返回释放字节；失败给可读提示，不抛。 */
    suspend fun deleteNow(id: String): Long = withContext(Dispatchers.IO) {
        val title = registry.firstOrNull { it.id == id }?.displayName ?: id
        try {
            val freed = store.delete(id)
            refreshNow()
            setMessage(ModelPackCopy.messageDeleted(title, freed))
            freed
        } catch (t: Throwable) {
            log("删除模型包失败：$id", t)
            setMessage(ModelPackCopy.actionFailed())
            0L
        }
    }

    /** 启用/停用；失败给可读提示，不抛。 */
    suspend fun setEnabledNow(id: String, enabled: Boolean): Boolean = withContext(Dispatchers.IO) {
        try {
            store.setEnabled(id, enabled)
            refreshNow()
            true
        } catch (t: Throwable) {
            log("改模型包启用状态失败：$id -> $enabled", t)
            setMessage(ModelPackCopy.actionFailed())
            refreshNow()
            false
        }
    }

    override fun onCleared() {
        cancelRequested.set(true)
        downloadJob?.cancel()
        downloadJob = null
        super.onCleared()
    }

    private fun setMessage(text: String?) {
        _state.value = _state.value.withMessage(text)
    }

    companion object {
        private const val TAG = "ModelPackViewModel"

        /** viewModel(factory = ModelPackViewModel.Factory(app)) 用的工厂（写法照 CalibrateViewModel）。 */
        class Factory(
            private val application: Application,
        ) : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(ModelPackViewModel::class.java)) {
                    "未知 ViewModel 类型：${modelClass.name}"
                }
                return ModelPackViewModel(application) as T
            }
        }

        /** 与 [Factory] 等价的函数形态（计划 §3.8 写的是 factory(app)）。 */
        fun factory(app: Application): ViewModelProvider.Factory = Factory(app)
    }
}
