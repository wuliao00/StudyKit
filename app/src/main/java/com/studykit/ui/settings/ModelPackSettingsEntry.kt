package com.studykit.ui.settings

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.studykit.modelpack.ModelPackCopy
import com.studykit.modelpack.ModelPackViewModel
import com.studykit.ui.components.ConfirmDialog

/**
 * 设置页「模型包（可选）」分区的挂载层（v3.1 地基 T8）。
 *
 * 为什么单独一层：[ModelPackSection] 刻意只吃 [com.studykit.modelpack.ModelPackUiState]
 * 与回调 —— 它要能在没有 Room / 没有 SettingsViewModel 的 JVM 渲染测试里独立摆出来。
 * 真正需要 Application 的只有 ViewModel 的构造，所以把这一小块隔离在这里：
 * [SettingsScreen] 里就只剩「开关 + 一行标题 + 一次调用」。
 *
 * ## 默认不露出
 * 五个模型包都还没上架（注册表里的 manifest 全是 null），所以分区默认**不挂进设置页**；
 * 构建开关是 `BuildConfig.MODEL_PACK_UI`（默认 false，本地预览用
 * `./gradlew assembleDebug -PmodelPackUi=true`）。发版构建不会看见这一块空壳。
 *
 * ## 删除必须二次确认（计划 §3.7）
 * [ModelPackSection] 的 `onDelete` 语义是「用户已经点过删除」（组件本身不弹确认）；
 * 不可逆动作在这里包一层 [ConfirmDialog]：标题/正文来自 [ModelPackCopy]，
 * 正文明确写「只删模型文件、不碰学习数据」。
 *
 * ## 「去卸载」的射程（说清楚，别让按钮承诺做不到的事）
 * 预算拒装弹层的「去卸载」只关闭弹层并把视线留在这个分区里：六个包行同在一张卡内、
 * 本机一屏基本放得下，没有做跨滚动位置的定位。真正的删除仍然只发生在那一行自己的
 * 「删除」按钮上（前面还有一次确认）。
 */
@Composable
fun ModelPackSettingsEntry(modifier: Modifier = Modifier) {
    val app = LocalContext.current.applicationContext as Application
    val viewModel: ModelPackViewModel = viewModel(factory = ModelPackViewModel.factory(app))
    val state by viewModel.state.collectAsStateWithLifecycle()

    // 待确认删除的包 id；null = 没有对话框。删除确认框的数据只在内存里，转屏要重弹。
    var pendingDeleteId by remember { mutableStateOf<String?>(null) }

    // 进入分区时扫一次目录：装/删都发生在别处，界面不替它缓存旧结果。
    LaunchedEffect(Unit) { viewModel.refresh() }

    ModelPackSection(
        state = state,
        onDownload = viewModel::download,
        onCancel = viewModel::cancel,
        onDelete = { id -> pendingDeleteId = id },
        onToggle = viewModel::setEnabled,
        onGoToRemove = { viewModel.dismissBudgetReject() },
        onDismissBudgetReject = viewModel::dismissBudgetReject,
        modifier = modifier,
    )

    val deletingId = pendingDeleteId
    if (deletingId != null) {
        val title = state.rows.firstOrNull { it.id == deletingId }?.title ?: deletingId
        ConfirmDialog(
            title = ModelPackCopy.deleteConfirmTitle(),
            body = ModelPackCopy.deleteConfirmBody(title),
            confirmLabel = ModelPackCopy.actionDelete(),
            danger = true,
            onConfirm = {
                pendingDeleteId = null
                viewModel.delete(deletingId)
            },
            onDismiss = { pendingDeleteId = null },
        )
    }
}
