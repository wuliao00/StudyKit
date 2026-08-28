package com.studykit.ui.mistake

import android.app.Application
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.studykit.StudyKitApp
import com.studykit.data.entity.Mistake
import com.studykit.util.MistakeImageStore
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

/** 学科分组条目 */
data class SubjectGroup(val subject: String, val items: List<Mistake>)

/**
 * 错题模块 ViewModel：列表（学科筛选 + 分组）、拍照录入、详情操作。
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

    /** 按学科分组（应用筛选后），组内按创建时间倒序（DAO 已保证） */
    val groups: StateFlow<List<SubjectGroup>> = combine(mistakes, _subjectFilter) { list, filter ->
        val filtered = if (filter == null) list else list.filter { it.subject == filter }
        filtered.groupBy { it.subject }
            .map { (subject, items) -> SubjectGroup(subject, items) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun selectSubject(subject: String?) {
        _subjectFilter.value = subject
    }

    // ── 详情 ──────────────────────────────────────────────────────────────
    private val _detailId = MutableStateFlow<Long?>(null)
    val detail: StateFlow<Mistake?> = _detailId
        .flatMapLatest { id ->
            if (id == null) flowOf(null) else repository.observeById(id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

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
    fun savePhotoMistake(subject: String, title: String, note: String, onSaved: () -> Unit) {
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
            repository.add(
                source = Mistake.SOURCE_PHOTO,
                subject = finalSubject.trim(),
                title = finalTitle.trim(),
                content = note.trim(),
                imagePath = relativePath,
            )
            toast("错题已保存")
            onSaved()
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

    /** 图片相对路径 → 本地文件（Coil 加载用） */
    fun resolveImage(relativePath: String): File =
        MistakeImageStore.resolve(getApplication(), relativePath)

    fun resolveThumb(relativePath: String): File? =
        MistakeImageStore.resolveThumb(getApplication(), relativePath)

    private fun toast(message: String) {
        Toast.makeText(getApplication(), message, Toast.LENGTH_SHORT).show()
    }
}

