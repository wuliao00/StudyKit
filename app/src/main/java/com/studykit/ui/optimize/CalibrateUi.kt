package com.studykit.ui.optimize

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.studykit.optimize.Bin
import com.studykit.optimize.Decision
import com.studykit.optimize.FsrsGate
import com.studykit.optimize.ParamSnapshot
import com.studykit.ui.components.AppButton
import com.studykit.ui.theme.AppTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * 设置页「排期内核」的校准区（v2.9 计划 Task 12）。
 *
 * 文案**逐字取自文档 §③「设置页『排期内核』卡片文案终稿」**（`studykit最终开发文档.txt` 499–537 行），
 * 含 `%` 占位符在这里用真实的 `String.format` / 字符串模板填上；三处 `contentDescription` 与
 * tooltip「全部计算在本机完成，记录不出设备」一并落到位。视觉只用 [AppTheme] 的既有 token，
 * 不新增任何色值（散点在弹窗小图里走 `accent`，对角线走 `divider`）。
 *
 * 渲染守卫见 [com.studykit.ui.optimize.CalibrateRenderTest]：这些中文串一旦写丢一个占位符、
 * 或按钮被 `if` 隐藏而不是置灰，那条测试当场红。
 */

/**
 * 校准卡主体：状态行 + [重新校准] 按钮 + tooltip + 运行中轻提示 + 失败原话 + 快照列表 + 页脚恢复默认，
 * 并在编排层交回一份 [CalibrateState.Result] 时自动摆出结果弹窗。
 *
 * @param state 校准编排对界面暴露的唯一状态
 * @param ratingCount 本机已有的可校准评分条数（决定"数据不足 / 满 1000 可校准"这一档，
 *   以及"还差几次"的那个差值——这是文档 §③ 需要、但 [CalibrateState] 本身不带的一份输入）
 * @param snapshots 参数快照列表（按 ts 降序；非空即"已校准"，取第一份的 nTerms/-ts 写状态行与列表）
 * @param now 时间源，相对"优化过"的天数差据此算；生产取 [System.currentTimeMillis]，测试可注入
 */
@Composable
fun CalibrationCard(
    state: CalibrateState,
    ratingCount: Int,
    snapshots: List<ParamSnapshot>,
    onCalibrate: () -> Unit,
    onAdopt: () -> Unit,
    onRestoreDefault: () -> Unit,
    onExport: (ParamSnapshot) -> Unit,
    now: Long = System.currentTimeMillis(),
) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    val calibrated = snapshots.isNotEmpty()
    val running = state is CalibrateState.Running
    val enoughData = calibrated || ratingCount >= FsrsGate.MIN_TERMS

    Column(verticalArrangement = Arrangement.spacedBy(AppTheme.space.sm)) {
        // 信息说明（文档 §③ tooltip 全文；这里直接摆成一行白话，让"记录不出设备"这句始终可见）
        Text(
            text = "校准 = 用你自己的复习记录拟合记忆曲线，排期不再套通用值。全部计算在本机完成，记录不出设备。",
            style = texts.caption.copy(color = colors.secondaryText),
        )

        // 状态行（三档之一）
        Text(text = calibrationStatusLine(ratingCount, snapshots, now), style = texts.aux)

        // 运行中：不阻塞 UI 的轻提示
        if (running) {
            Text(
                text = "正在用你的 $ratingCount 条记录拟合，大约 $RUNNING_EST_SECONDS 秒。" +
                    "可以继续用 App，好了会告诉你。",
                style = texts.caption.copy(color = colors.secondaryText),
            )
        }

        // 失败：把"没通过体检"的原话摆到卡上（文档 §③ 失败 toast 的同一段文案，这里常驻可见更好读）
        (state as? CalibrateState.Failed)?.let { failed ->
            Text(
                text = "这次校准没通过体检（${failed.reason}），已保留原参数。",
                style = texts.caption.copy(color = colors.warningInk),
            )
        }

        AppButton(
            text = "重新校准",
            onClick = onCalibrate,
            enabled = enoughData && !running,
            secondary = true,
            modifier = Modifier
                .semantics { contentDescription = "用本机复习记录重新校准排期曲线" },
        )

        if (calibrated) {
            HorizontalDivider(color = colors.divider)
            Text(text = "参数快照", style = texts.cardTitle)
            snapshots.forEach { snap ->
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(
                        text = snapshotRowLabel(snap),
                        style = texts.caption.copy(color = colors.secondaryText),
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = { onExport(snap) },
                        modifier = Modifier.semantics { contentDescription = "导出当前参数快照用于反馈问题" },
                    ) {
                        Text(text = "导出（贴 issue 用）", color = colors.accentInk)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(AppTheme.space.xs))
        TextButton(onClick = onRestoreDefault) {
            Text(text = "恢复默认参数", color = colors.secondaryText)
        }
    }

    // 结果弹窗：编排层交出可确认的报告（非干跑已采纳）时自动摆出，"先不改"就本次收起。
    val result = state as? CalibrateState.Result
    var dismissedFor by remember { mutableStateOf<CalibrateState.Result?>(null) }
    if (result != null && !result.adopted && result != dismissedFor) {
        CalibrateResultDialog(
            result = result,
            onAdopt = onAdopt,
            onDismiss = { dismissedFor = result },
        )
    }
}

/**
 * 结果弹窗（Adopt / Manual 共用框架，文档 §③）：三行数字 + 校准曲线小图 + [采用新参数]/[先不改]；
 * [Decision.Manual] 换标题并追加"提醒你：…"一行。
 */
@Composable
fun CalibrateResultDialog(
    result: CalibrateState.Result,
    onAdopt: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    val isManual = result.decision is Decision.Manual
    val maxDevPercent = resultMaxDeviationPercent(result.candidate.bins)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (isManual) "校准结果想让你看一眼" else "校准完成，要采用新曲线吗？",
                style = texts.cardTitle,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(AppTheme.space.sm)) {
                Text(
                    text = "预测误差：${fmt3(result.current.logloss.toDouble())} → " +
                        "${fmt3(result.candidate.logloss.toDouble())}（越低越准）",
                    style = texts.aux,
                )
                Text(
                    text = "校准曲线最大偏差：${fmt1(maxDevPercent.toDouble())}%" +
                        "（预测和实际对得越齐越好）",
                    style = texts.aux,
                )
                Text(
                    text = "预计平均间隔：${fmt1(result.intervalBefore.toDouble())} 天 → " +
                        "${fmt1(result.intervalAfter.toDouble())} 天",
                    style = texts.aux,
                )
                if (isManual) {
                    Text(
                        text = "提醒你：${result.reasons.joinToString("；")}。" +
                            "采用后到期日会重新分布，已完成的复习不受影响。",
                        style = texts.caption.copy(color = colors.warningInk),
                    )
                }
                Text(text = "预测 vs 实际（越贴对角线越准）", style = texts.caption)
                CalibrationMiniChart(
                    bins = result.candidate.bins,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                        .semantics { contentDescription = "校准曲线图：横轴预测召回率，纵轴实际召回率" },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onAdopt) { Text(text = "采用新参数", color = colors.accentInk) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(text = "先不改", color = colors.secondaryText) }
        },
    )
}

/**
 * 校准曲线迷你图：横轴预测召回率、纵轴实测召回率的 10 桶散点 + 一条对角参考线。
 * 只画非空桶（[Bin.isEmpty] 的哨兵值不参与），散点走 `accent`、对角线走 `divider`，不新增色值。
 */
@Composable
fun CalibrationMiniChart(
    bins: List<Bin>,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        drawLine(colors.divider, Offset(0f, h), Offset(w, 0f), strokeWidth = 1.5.dp.toPx())
        val radius = 3.dp.toPx()
        bins.filter { !it.isEmpty }.forEach { b ->
            val x = b.pred.coerceIn(0f, 1f) * w
            val y = h - b.obs.coerceIn(0f, 1f) * h
            drawCircle(colors.accent, radius = radius, center = Offset(x, y))
        }
    }
}

// ── 纯函数：状态行 / 相对时间 / 快照行 / 最大偏差，逐字对齐文档 §③，也便于逐档断言 ──

/** 卡片状态行三档（文档 §③）：已校准 / 数据不足 / 满量未校准。 */
internal fun calibrationStatusLine(ratingCount: Int, snapshots: List<ParamSnapshot>, now: Long): String {
    val latest = snapshots.firstOrNull()
    return when {
        latest != null -> "已用你的 ${latest.nTerms} 次评分校准 · ${calibrationAgoLabel(latest.ts, now)}优化过"
        ratingCount < FsrsGate.MIN_TERMS -> "还差 ${FsrsGate.MIN_TERMS - ratingCount} 次评分就能校准"
        else -> "还在用通用曲线 · 满 ${FsrsGate.MIN_TERMS} 次评分后可校准"
    }
}

/** 相对"上次优化"的人话（文档 §③ %2$s：今天 / N 天前）。 */
internal fun calibrationAgoLabel(ts: Long, now: Long): String {
    val days = ((now - ts) / MILLIS_PER_DAY).toInt().coerceAtLeast(0)
    return if (days == 0) "今天" else "$days 天前"
}

/** 快照行（文档 §③）：`%1$s · %2$d 条记录 · 误差 %3$.3f`。 */
internal fun snapshotRowLabel(snap: ParamSnapshot): String {
    val date = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(snap.ts))
    return "$date · ${snap.nTerms} 条记录 · 误差 ${fmt3(snap.logloss)}"
}

/** 弹窗"校准曲线最大偏差"：样本够多的桶里 |预测−实测| 的最大值，换算成百分数（文档 §③ %3$.1f%%）。 */
internal fun resultMaxDeviationPercent(bins: List<Bin>): Float {
    val devs = bins.filter { !it.isEmpty && it.n >= FsrsGate.BIN_MIN_N }.map { abs(it.pred - it.obs) }
    return if (devs.isEmpty()) 0f else devs.max() * 100f
}

private fun fmt3(v: Double): String = String.format(Locale.US, "%.3f", v)

private fun fmt1(v: Double): String = String.format(Locale.US, "%.1f", v)

/** 运行中轻提示里"大约几秒"的名义值：设备训练是秒级，这里给一个不吓人的粗估。 */
private const val RUNNING_EST_SECONDS = 8

private const val MILLIS_PER_DAY = 24L * 60L * 60L * 1000L
