package com.studykit.ui.mistake

import android.app.Application
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.studykit.StudyKitApp
import com.studykit.data.entity.Mistake
import com.studykit.util.MistakeImageStore
import com.studykit.util.OneShotGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 学科分组条目 */
data class SubjectGroup(val subject: String, val items: List<Mistake>)

/**
 * 详情页的一次数据应答。
 *
 * [id] 是这条应答所属的错题 id —— 页面必须拿 route 上的 `mistakeId` 与它比对，比对不过就还在
 * 加载态（否则「上一道题的行」会被当成当前页的内容渲染，见 [MistakeViewModel.detailState]）。
 * [answered] 标记数据库是否已就这个 id 完成过一次读取：只有它是 true 时
 * [mistake] == null 才读得出「库里确实没有这一行」，否则只是「还没回话」。
 */
data class MistakeDetailState(
    val id: Long? = null,
    val mistake: Mistake? = null,
    val answered: Boolean = false,
)

/** 错题详情页面相：由「route id」与「VM 应答」两者的比对唯一决定 */
internal sealed interface MistakeDetailRender {

    /** 还没答到这一道（首次读取在飞，或 VM 仍在答上一道）→ 只出加载态，页面上没有任何写动作 */
    internal data object Loading : MistakeDetailRender

    /** 已答完且库里确实没有这一行（被别处删掉了）→ 出「不存在」文案 */
    internal data object Missing : MistakeDetailRender

    /** 应答的行就是 `mistakeId` 那道题：渲染与动作都以它为准 */
    internal data class Ready(val mistake: Mistake) : MistakeDetailRender
}

/**
 * 页相判定（纯函数，零 Android 依赖，可在 JVM 单测里直取）：
 * `id` 不同或还没答完 → [MistakeDetailRender.Loading]；答完且没有行 →
 * [MistakeDetailRender.Missing]；否则 [MistakeDetailRender.Ready]。
 *
 * 顺序不能反：不匹配时 [MistakeDetailState.mistake] 里躺着的可能是**另一道**题的行，
 * 直接判「非空即渲染」就是终审 C1 的写错目标行。
 */
internal fun renderMistakeDetail(
    state: MistakeDetailState,
    mistakeId: Long,
): MistakeDetailRender = when {
    state.id != mistakeId || !state.answered -> MistakeDetailRender.Loading
    state.mistake == null -> MistakeDetailRender.Missing
    else -> MistakeDetailRender.Ready(state.mistake)
}

/**
 * 错题模块 ViewModel：列表（学科筛选 + 分组）、拍照录入、详情操作。
 */
class MistakeViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as StudyKitApp).container
    private val repository = container.mistakeRepository

    // ── 列表与筛选 ────────────────────────────────────────────────────────
    /**
     * 两条掌握态各一条源（DAO 已保证按 `created_at DESC`）。
     *
     * spec「标记已掌握后划线消失」：默认列表只看**未掌握**，`markMastered` 后该行从「待复习」
     * 当场退场（列表侧 `animateItem()` 播退场）；已掌握清单由 [showMastered] 这枚 chip 保住入口，
     * 否则用户再也回不到那些错题，复习入口就断了。
     */
    private val unmastered: Flow<List<Mistake>> = repository.observeUnmastered()
    private val mastered: Flow<List<Mistake>> = repository.observeMastered()

    /** 当前掌握态筛选：false = 待复习（默认），true = 已掌握 */
    private val _showMastered = MutableStateFlow(false)
    val showMastered: StateFlow<Boolean> = _showMastered

    /** 当前学科筛选；null 表示全部 */
    private val _subjectFilter = MutableStateFlow<String?>(null)
    val subjectFilter: StateFlow<String?> = _subjectFilter

    /** 当前 chip 组合下的列表（**学科筛选前**）：计数文案与 [groups] 都吃这一份 */
    val mistakes: StateFlow<List<Mistake>> =
        combine(unmastered, mastered, _showMastered) { todo, done, masteredOnly ->
            if (masteredOnly) done else todo
        }
            // 与 `StudyViewModel.homeState` 同一口径（终审 I7）：combine 的变换跑在
            // stateIn 的收集线程（Main.immediate）上，显式切到 Default 再交给状态流
            .flowOn(context = Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * 两态并集，只用于派生「学科 chips」与「库里到底有没有错题」。
     * 二者都**不随 [showMastered] 变**：否则切到「已掌握」时学科行会整排重排、
     * 选中的学科 chip 可能凭空消失（筛选态与可见 chip 不一致）。
     */
    private val allMistakes: StateFlow<List<Mistake>> =
        combine(unmastered, mastered) { todo, done -> todo + done }
            // `todo + done` 每次写入都整表复制一份，题量大时不该压在主线（终审 I7）
            .flowOn(context = Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 数据中 distinct 出的学科列表 */
    val subjects: StateFlow<List<String>> = allMistakes
        .map { list -> list.map { it.subject }.distinct() }
        .flowOn(context = Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 一道错题都没有时为 false —— 用来区分空态文案「错题本还是空的」与「全部已掌握」 */
    val hasAnyMistake: StateFlow<Boolean> = allMistakes
        .map { it.isNotEmpty() }
        .flowOn(context = Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** 按学科分组（应用筛选后），组内按创建时间倒序（DAO 已保证） */
    val groups: StateFlow<List<SubjectGroup>> = combine(mistakes, _subjectFilter) { list, filter ->
        val filtered = if (filter == null) list else list.filter { it.subject == filter }
        filtered.groupBy { it.subject }
            .map { (subject, items) -> SubjectGroup(subject, items) }
    }
        // 筛选 + groupBy + 建组：本模块最重的一段映射，留出 Default 线程跑（终审 I7）
        .flowOn(context = Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun selectSubject(subject: String?) {
        _subjectFilter.value = subject
    }

    fun selectShowMastered(masteredOnly: Boolean) {
        _showMastered.value = masteredOnly
    }

    // ── 详情 ──────────────────────────────────────────────────────────────
    /** 页面请求的错题 id（route 上的 `mistakeId` 是唯一准绳，由页面自己写进来） */
    private val _detailId = MutableStateFlow<Long?>(null)
    val detailId: StateFlow<Long?> = _detailId

    /**
     * 详情页的**应答**：每条都带上「它回答的是哪个 id」与「数据库是否已就这个 id 完成过一次读取」。
     *
     * 老写法是 `StateFlow<Mistake?>` + 「非空就直接渲染」：`openDetail(B)` 之后 Room 还没回话，
     * 流里留着的仍是 A 的行，于是页面在 B 的 route 上渲染 A，此时点「标记掌握 / 删除 / 设复习时间」
     * 全部写到 A 行（终审 C1）。现在页面必须拿 route 上的 id 与 [MistakeDetailState.id] 比过才敢渲染，
     * 见 [renderMistakeDetail]。
     */
    // flatMapLatest 仍是实验 API：opt-in 只挂在调用点，不给整个类加
    @OptIn(ExperimentalCoroutinesApi::class)
    val detailState: StateFlow<MistakeDetailState> = _detailId
        .flatMapLatest { id ->
            if (id == null) {
                flowOf(MistakeDetailState(answered = true))
            } else {
                repository.observeById(id).map { row ->
                    MistakeDetailState(id = id, mistake = row, answered = true)
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MistakeDetailState())

    fun openDetail(id: Long) {
        _detailId.value = id
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

    /** 保存拍照错题：压缩图片入 mistake_images/，Room 只存相对路径 */
    // 一次性门（终审 C4 的第六个入口）：图片压缩要跑几百毫秒，这段窗口里连点会插两条错题、
    // 也会 pop 两次。
    private val savingMistake = OneShotGate()

    fun savePhotoMistake(subject: String, title: String, note: String, onSaved: () -> Unit) {
        if (!savingMistake.tryEnter()) return
        val captured = _pendingCapture.value
        if (captured == null || captured.exists().not()) {
            toast("未获取到照片")
            // 同步早退也要当场放行，否则用户重拍一张后再点会永久没反应
            savingMistake.leave()
            return
        }
        val finalSubject = subject.ifBlank { "未分类" }
        val finalTitle = title.ifBlank { "拍照错题" }
        viewModelScope.launch {
            try {
                val relativePath = withContext(Dispatchers.IO) {
                    MistakeImageStore.processCaptured(getApplication(), captured)
                }
                _pendingCapture.value = null
                if (relativePath == null) {
                    toast("图片处理失败，请重新拍照")
                    return@launch
                }
                repository.add(
                    source = Mistake.SOURCE_PHOTO,
                    subject = finalSubject.trim(),
                    title = finalTitle.trim(),
                    content = note.trim(),
                    imagePath = relativePath,
                )
                toast("错题已保存")
                onSaved()
            } finally {
                savingMistake.leave()
            }
        }
    }

    // ── 详情操作 ──────────────────────────────────────────────────────────
    /** 设置复习时间（时间戳毫秒） */
    fun setReviewAt(id: Long, reviewAt: Long) {
        viewModelScope.launch {
            val mistake = repository.observeById(id).first() ?: return@launch
            repository.update(mistake.copy(reviewAt = reviewAt))
            toast("已设置复习时间")
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

    /** 标记已掌握 */
    fun markMastered(id: Long) {
        viewModelScope.launch {
            repository.markMastered(id)
            toast("已标记掌握")
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

    /**
     * 图片相对路径 → 本地文件（Coil 加载用）。
     *
     * 纯路径拼接，**不碰磁盘**，可以在组合期调用；「在不在」由调用方在 IO 线程判（终审 I8）。
     */
    fun resolveImage(relativePath: String): File =
        MistakeImageStore.resolve(getApplication(), relativePath)

    /** 缩略图相对路径 → 候选文件（同样是纯拼接，不 stat；缺失时调用方回退大图） */
    fun thumbFile(relativePath: String): File =
        MistakeImageStore.thumbFile(getApplication(), relativePath)

    private fun toast(message: String) {
        Toast.makeText(getApplication(), message, Toast.LENGTH_SHORT).show()
    }
}

