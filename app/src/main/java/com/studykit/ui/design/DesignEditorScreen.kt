package com.studykit.ui.design

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.studykit.domain.design.DesignGenerator
import com.studykit.domain.design.DesignMode
import com.studykit.domain.design.DesignPlanCodec
import com.studykit.ui.components.AppButton
import com.studykit.ui.components.AppCard
import com.studykit.ui.components.AppMultilineTextField
import com.studykit.ui.components.AppTextField
import com.studykit.ui.components.ConfirmDialog
import com.studykit.ui.theme.AppTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val FieldsSaver = listSaver<SnapshotStateMap<String, String>, String>(
    save = { map -> map.flatMap { (key, value) -> listOf(key, value) } },
    restore = { flat ->
        mutableStateMapOf<String, String>().apply {
            flat.chunked(2).forEach { pair ->
                if (pair.size == 2) put(pair[0], pair[1])
            }
        }
    },
)

/**
 * 某个模板的编辑页：字段表单 + 实时预览 + 保存/复制/分享/删除。
 *
 * `planId == null` 新建；否则读回方案并覆盖保存。**预览是纯拼装**：
 * 所有必填项填齐后，[DesignGenerator] 立刻给出 Markdown，没有网络请求、没有模型加载，
 * 所以断网、飞行模式、几年后再打开，看到的都是同一份结果。
 *
 * 草稿用 [rememberSaveable] + [FieldsSaver] 保存：转屏或进程被回收后回来，
 * 已经敲进去的字还在。真正落库只发生在点「保存方案」时。
 */
@Composable
fun DesignEditorScreen(
    viewModel: DesignViewModel,
    mode: DesignMode,
    planId: Long?,
    onBack: () -> Unit,
) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    val context = LocalContext.current
    // `observeById` 每次调用都给一条新 Flow；不 remember 的话每次重组都会退订再订阅，
    // 编辑时的每一次击键都白跑一趟查询。
    val planFlow = remember(planId) { viewModel.plan(planId ?: 0L) }
    val existing by planFlow.collectAsStateWithLifecycle(initialValue = null)

    var title by rememberSaveable(planId) { mutableStateOf("") }
    val values = rememberSaveable(planId, saver = FieldsSaver) { mutableStateMapOf<String, String>() }
    var loaded by rememberSaveable(planId) { mutableStateOf(planId == null) }
    var saving by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var showDiscard by remember { mutableStateOf(false) }

    LaunchedEffect(existing?.id) {
        val plan = existing ?: return@LaunchedEffect
        if (!loaded) {
            title = plan.title
            values.clear()
            values.putAll(DesignPlanCodec.decode(plan.fieldsJson))
            loaded = true
        }
    }

    val snapshot = values.toMap()
    val missing = mode.fields.count { it.required && snapshot[it.key].orEmpty().isBlank() }
    val effectiveTitle = title.trim().ifBlank {
        mode.label + " · " + SimpleDateFormat("MM-dd", Locale.CHINA).format(Date())
    }
    val content = remember(mode, effectiveTitle, snapshot, missing) {
        if (missing == 0) DesignGenerator.generate(mode, effectiveTitle, snapshot) else ""
    }
    val canSave = missing == 0 && !saving
    // 与已保存行比较，而不是“只要填过就算脏”：打开旧方案看一眼就返回不该被拦。
    val dirty = if (planId == null) {
        title.isNotBlank() || snapshot.values.any { it.isNotBlank() }
    } else {
        existing?.let {
            it.title != title || DesignPlanCodec.decode(it.fieldsJson) != snapshot
        } ?: false
    }

    // 保存中禁止返回：否则保存协程完成时的 onBack 会把已经回到的 Hub 再弹一层。
    BackHandler(enabled = saving || dirty) {
        if (!saving) showDiscard = true
    }

    fun requestBack() {
        if (dirty) showDiscard = true else onBack()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppTheme.space.pageH, vertical = AppTheme.space.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = { requestBack() },
                enabled = !saving,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = colors.accentInk,
                )
            }
            Spacer(Modifier.width(AppTheme.space.xs))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = mode.label, style = texts.pageTitle)
                Text(text = if (planId == null) "新建方案" else "编辑方案", style = texts.caption)
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = AppTheme.space.pageH),
        ) {
            Text(text = mode.tagline, style = texts.aux)
            Spacer(Modifier.height(AppTheme.space.xs))
            Text(text = "依据：${mode.basis}", style = texts.caption)
            Spacer(Modifier.height(AppTheme.space.lg))

            AppTextField(
                value = title,
                onValueChange = { title = it },
                label = "方案名称",
                placeholder = "留空则用「${mode.label} · 日期」",
            )
            Spacer(Modifier.height(AppTheme.space.md))

            mode.fields.forEach { field ->
                if (field.multiline) {
                    AppMultilineTextField(
                        value = snapshot[field.key].orEmpty(),
                        onValueChange = { values[field.key] = it },
                        label = if (field.required) "${field.label} *" else field.label,
                        placeholder = field.hint,
                        minLines = if (field.key in setOf("sample", "activity", "steps", "sessionPlan")) 5 else 3,
                    )
                } else {
                    AppTextField(
                        value = snapshot[field.key].orEmpty(),
                        onValueChange = { values[field.key] = it },
                        label = if (field.required) "${field.label} *" else field.label,
                        placeholder = field.hint,
                    )
                }
                Spacer(Modifier.height(AppTheme.space.md))
            }

            Spacer(Modifier.height(AppTheme.space.sm))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = "方案预览", style = texts.cardTitle, modifier = Modifier.weight(1f))
                if (missing > 0) {
                    Text(text = "还差 $missing 个必填项", style = texts.caption)
                }
            }
            Spacer(Modifier.height(AppTheme.space.sm))

            if (content.isBlank()) {
                AppCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "把所有带 * 的字段填完，这里会按模板生成完整的 Markdown 方案。",
                        style = texts.caption,
                    )
                }
            } else {
                AppCard(modifier = Modifier.fillMaxWidth()) {
                    SelectionContainer {
                        Text(
                            text = content,
                            style = texts.body.copy(fontSize = 14.sp, lineHeight = 22.sp),
                        )
                    }
                }
                Spacer(Modifier.height(AppTheme.space.sm))
                Row(horizontalArrangement = Arrangement.spacedBy(AppTheme.space.sm)) {
                    TextButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                                as? ClipboardManager
                            clipboard?.setPrimaryClip(
                                ClipData.newPlainText("学习设计方案", content),
                            )
                            Toast.makeText(context, "整份方案已复制", Toast.LENGTH_SHORT).show()
                        },
                    ) {
                        Text(text = "复制全文", style = texts.aux.copy(color = colors.accentInk))
                    }
                    TextButton(
                        onClick = {
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, effectiveTitle)
                                putExtra(Intent.EXTRA_TEXT, content)
                            }
                            context.startActivity(Intent.createChooser(send, "分享方案"))
                        },
                    ) {
                        Text(text = "分享", style = texts.aux.copy(color = colors.accentInk))
                    }
                }
            }

            Spacer(Modifier.height(AppTheme.space.md))
            AppButton(
                text = if (planId == null) "保存方案" else "保存修改",
                enabled = canSave,
                onClick = {
                    saving = true
                    viewModel.save(
                        id = planId,
                        mode = mode,
                        title = effectiveTitle,
                        fields = snapshot,
                        content = content,
                        onSaved = {
                            saving = false
                            Toast.makeText(context, "已保存", Toast.LENGTH_SHORT).show()
                            onBack()
                        },
                        onError = { message ->
                            saving = false
                            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                        },
                    )
                },
            )
            if (planId != null) {
                Spacer(Modifier.height(AppTheme.space.xs))
                TextButton(
                    onClick = { showDelete = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(text = "删除这份方案", style = texts.caption.copy(color = colors.warningInk))
                }
            }
            Spacer(Modifier.height(AppTheme.space.xl))
        }
    }

    if (showDiscard) {
        ConfirmDialog(
            title = "放弃这次编辑？",
            body = "还没保存的字段会丢失。",
            confirmLabel = "放弃",
            danger = true,
            onConfirm = {
                showDiscard = false
                onBack()
            },
            onDismiss = { showDiscard = false },
        )
    }

    if (showDelete && planId != null) {
        ConfirmDialog(
            title = "删除这份方案？",
            body = "方案正文与已填字段会一起删掉，不能恢复。",
            confirmLabel = "删除",
            danger = true,
            onConfirm = {
                showDelete = false
                viewModel.delete(
                    id = planId,
                    onDeleted = {
                        Toast.makeText(context, "已删除", Toast.LENGTH_SHORT).show()
                        onBack()
                    },
                    onError = { message ->
                        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                    },
                )
            },
            onDismiss = { showDelete = false },
        )
    }
}
