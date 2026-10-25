package com.studykit.ui.design

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.studykit.StudyKitApp
import com.studykit.data.entity.DesignPlan
import com.studykit.domain.design.DesignMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 「学习设计」的读写门面。
 *
 * 生成发生在 UI 侧的纯函数 [com.studykit.domain.design.DesignGenerator]，这里只负责落库：
 * 保存时把**生成时刻的 content** 一并存下，之后模板再改也不影响旧方案导出。
 *
 * 列表用 `WhileSubscribed(5s)`：离开设计 Tab 后停止观察，回来时重新订阅 —— 这是一张会增长的
 * 本地表，没有必要在后台一直举着查询。
 */
class DesignViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = (application as StudyKitApp).container.designPlanRepository

    val plans: StateFlow<List<DesignPlan>> = repository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun plan(id: Long): Flow<DesignPlan?> = repository.observeById(id)

    /**
     * `id == null` 新建，否则覆盖。成功回调带回落库后的 id（新建后可用于替换路由），
     * 失败只给一句人话 —— 不把异常类名甩给用户。
     */
    fun save(
        id: Long?,
        mode: DesignMode,
        title: String,
        fields: Map<String, String>,
        content: String,
        onSaved: (Long) -> Unit,
        onError: (String) -> Unit,
    ) {
        if (content.isBlank()) {
            onError("方案还是空的，先把必填项补上")
            return
        }
        viewModelScope.launch {
            runCatching {
                repository.save(id = id, mode = mode, title = title, fields = fields, content = content)
            }.onSuccess(onSaved).onFailure {
                onError("没能保存：${it.localizedMessage ?: it.javaClass.simpleName}")
            }
        }
    }

    fun delete(id: Long, onDeleted: () -> Unit, onError: (String) -> Unit) {
        viewModelScope.launch {
            runCatching { repository.deleteById(id) }
                .onSuccess { onDeleted() }
                .onFailure {
                    onError("没能删除：${it.localizedMessage ?: it.javaClass.simpleName}")
                }
        }
    }
}
