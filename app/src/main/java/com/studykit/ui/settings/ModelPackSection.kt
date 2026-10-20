package com.studykit.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.studykit.modelpack.BudgetRejectUi
import com.studykit.modelpack.ModelPackCopy
import com.studykit.modelpack.ModelPackUiState
import com.studykit.modelpack.PackRowStatus
import com.studykit.modelpack.PackRowUi
import com.studykit.ui.components.AppCard
import com.studykit.ui.components.ConfirmDialog
import com.studykit.ui.theme.AppTheme

/**
 * 模型包分区里每个可交互节点的测试标签（渲染守卫按 id 精确命中，不靠文案找按钮）。
 *
 * 用对象集中管理而不是让测试自己拼字符串：标签是测试与界面的接口，散在两边就会漂。
 */
object ModelPackTags {
    fun row(id: String): String = "model_pack_row_$id"
    fun download(id: String): String = "model_pack_download_$id"
    fun cancel(id: String): String = "model_pack_cancel_$id"
    fun redownload(id: String): String = "model_pack_redownload_$id"
    fun delete(id: String): String = "model_pack_delete_$id"
    fun toggle(id: String): String = "model_pack_toggle_$id"
}

/**
 * 设置页"模型包（可选）"分区（计划 T6 / §3.7）。
 *
 * ## 独立性（为什么这么写）
 * 只吃 [ModelPackUiState] + 回调，**不依赖 SettingsViewModel/Room/AppContainer**：
 * 整页设置要 Room 才能起，JVM 侧的 Robolectric 渲染不起来；而"六态各画对没有、按钮接没接对线"
 * 恰好是纯逻辑测试与整页测试都覆盖不到的一格（先例：SettingsSemanticRowRenderTest 只摆一簇）。
 *
 * ## 纪律
 * - 所有文案取自 [ModelPackCopy]，本文件**没有第二份用户可见中文字面量**；
 * - 颜色只取 `AppTheme.colors.*`，不新增色值（背景/卡面/强调色/次要文字/警告墨色）；
 * - 行状态的六种分支由 `when` 穷尽，新增状态时编译器会逼着补渲染；
 * - 预算拒装用既有 [ConfirmDialog]：取消 = 什么都不删，确认 = 只做"跳到要卸的那一行"，
 *   真正的删除是那一行上的「删除」按钮（不可逆动作必须一次一个确认）。
 *
 * ## 由 T8 挂载
 * 本文件不接入 SettingsScreen；T8 在「数据管理」之后按 `BuildConfig.MODEL_PACK_UI` 决定是否摆出
 * `SectionHeader(ModelPackCopy.sectionTitle()) + ModelPackSection(...)`（默认关，发版不露出空壳）。
 *
 * @param state 六行 + 总账 + 提示 + 预算弹层（VM 给出）
 * @param onDownload 下载（[PackRowStatus.NotInstalled] / [PackRowStatus.Corrupt] 的按钮）
 * @param onCancel 取消下载
 * @param onDelete 删除已装包（调用方负责二次确认；本组件不弹删除确认）
 * @param onToggle 启用/停用（开关）
 * @param onGoToRemove 预算拒装弹层确认后滚到建议卸载的那一行
 * @param onDismissBudgetReject 关闭预算拒装弹层
 */
@Composable
fun ModelPackSection(
    state: ModelPackUiState,
    onDownload: (String) -> Unit,
    onCancel: (String) -> Unit,
    onDelete: (String) -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onGoToRemove: (String) -> Unit,
    onDismissBudgetReject: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = ModelPackCopy.sectionHint(),
            style = texts.caption.copy(color = colors.secondaryText),
        )
        Spacer(Modifier.height(AppTheme.space.sm))
        // 顶部一行总账：与 BudgetGuard 同一口径（数字由 VM 从 store.usages() 算）
        Text(
            text = state.summary,
            style = texts.caption.copy(color = colors.secondaryText),
        )
        Spacer(Modifier.height(AppTheme.space.sm))
        AppCard(modifier = Modifier.fillMaxWidth()) {
            if (state.rows.isEmpty()) {
                Text(
                    text = ModelPackCopy.emptyState(),
                    style = texts.caption.copy(color = colors.secondaryText),
                )
            } else {
                state.rows.forEachIndexed { index, row ->
                    if (index > 0) HorizontalDivider(color = colors.divider)
                    PackRow(
                        row = row,
                        onDownload = onDownload,
                        onCancel = onCancel,
                        onDelete = onDelete,
                        onToggle = onToggle,
                    )
                }
            }
        }
        state.message?.let { message ->
            Spacer(Modifier.height(AppTheme.space.sm))
            Text(
                text = message,
                style = texts.caption.copy(color = colors.accentInk),
            )
        }
    }

    state.budgetReject?.let { reject ->
        BudgetRejectDialog(
            reject = reject,
            onGoToRemove = onGoToRemove,
            onDismiss = onDismissBudgetReject,
        )
    }
}

/** 一行：标题（+ 已装开关）+ 说明 + 状态行/操作按钮。 */
@Composable
private fun PackRow(
    row: PackRowUi,
    onDownload: (String) -> Unit,
    onCancel: (String) -> Unit,
    onDelete: (String) -> Unit,
    onToggle: (String, Boolean) -> Unit,
) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(ModelPackTags.row(row.id))
            .padding(vertical = AppTheme.space.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = row.title, style = texts.cardTitle, modifier = Modifier.weight(1f))
            if (row.status is PackRowStatus.Installed) {
                Switch(
                    checked = row.status.enabled,
                    onCheckedChange = { enabled -> onToggle(row.id, enabled) },
                    modifier = Modifier.testTag(ModelPackTags.toggle(row.id)),
                    colors = packSwitchColors(),
                )
            }
        }
        Spacer(Modifier.height(AppTheme.space.xs))
        Text(
            text = row.hint,
            style = texts.caption.copy(color = colors.secondaryText),
        )
        Spacer(Modifier.height(AppTheme.space.sm))
        Row(verticalAlignment = Alignment.CenterVertically) {
            when (val status = row.status) {
                PackRowStatus.Planned -> {
                    Text(
                        text = row.statusLine ?: ModelPackCopy.plannedBadge(),
                        style = texts.caption.copy(color = colors.secondaryText),
                        modifier = Modifier.weight(1f),
                    )
                }
                PackRowStatus.NotInstalled -> {
                    Spacer(Modifier.weight(1f))
                    PackActionButton(
                        label = ModelPackCopy.actionDownload(),
                        tag = ModelPackTags.download(row.id),
                        onClick = { onDownload(row.id) },
                    )
                }
                is PackRowStatus.Downloading -> {
                    Text(
                        text = ModelPackCopy.downloadProgress(status.progress),
                        style = texts.caption.copy(color = colors.accentInk),
                        modifier = Modifier.weight(1f),
                    )
                    PackActionButton(
                        label = ModelPackCopy.actionCancel(),
                        tag = ModelPackTags.cancel(row.id),
                        onClick = { onCancel(row.id) },
                    )
                }
                PackRowStatus.Verifying -> {
                    Text(
                        text = ModelPackCopy.verifying(),
                        style = texts.caption.copy(color = colors.accentInk),
                    )
                }
                is PackRowStatus.Installed -> {
                    Text(
                        text = row.statusLine ?: ModelPackCopy.installedSize(row.sizeBytes),
                        style = texts.caption.copy(color = colors.secondaryText),
                        modifier = Modifier.weight(1f),
                    )
                    PackActionButton(
                        label = ModelPackCopy.actionDelete(),
                        tag = ModelPackTags.delete(row.id),
                        onClick = { onDelete(row.id) },
                        danger = true,
                    )
                }
                PackRowStatus.Corrupt -> {
                    Text(
                        text = ModelPackCopy.corrupt(),
                        style = texts.caption.copy(color = colors.warningInk),
                        modifier = Modifier.weight(1f),
                    )
                    PackActionButton(
                        label = ModelPackCopy.actionRedownload(),
                        tag = ModelPackTags.redownload(row.id),
                        onClick = { onDownload(row.id) },
                    )
                }
            }
        }
    }
}

/** 行内文字按钮：48dp 最小可点目标 + 统一墨色（危险动作走 warningInk）。 */
@Composable
private fun PackActionButton(
    label: String,
    tag: String,
    onClick: () -> Unit,
    danger: Boolean = false,
) {
    val colors = AppTheme.colors
    TextButton(
        onClick = onClick,
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .testTag(tag),
    ) {
        Text(
            text = label,
            style = AppTheme.texts.aux.copy(color = if (danger) colors.warningInk else colors.accentInk),
        )
    }
}

/** 预算拒装弹层：有可跳转的行时确认按钮是「去卸载」，否则只给「知道了」。 */
@Composable
private fun BudgetRejectDialog(
    reject: BudgetRejectUi,
    onGoToRemove: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val removalId = reject.removalId
    ConfirmDialog(
        title = reject.title,
        body = reject.body,
        confirmLabel = if (removalId == null) ModelPackCopy.actionKnown() else ModelPackCopy.actionGoUninstall(),
        danger = false,
        onConfirm = {
            if (removalId != null) onGoToRemove(removalId)
            onDismiss()
        },
        onDismiss = onDismiss,
    )
}

/** 开关配色与设置页既有开关同一套令牌（不新增色值）。 */
@Composable
private fun packSwitchColors(): SwitchColors {
    val colors = AppTheme.colors
    return SwitchDefaults.colors(
        checkedTrackColor = colors.accentInk,
        checkedBorderColor = colors.accentInk,
        checkedThumbColor = colors.card,
        uncheckedTrackColor = colors.divider,
        uncheckedBorderColor = colors.divider,
        uncheckedThumbColor = colors.card,
    )
}
