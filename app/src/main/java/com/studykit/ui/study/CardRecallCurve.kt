package com.studykit.ui.study

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.studykit.data.entity.Word
import com.studykit.data.memory.FsrsModel
import com.studykit.data.memory.Scheduling
import com.studykit.ui.components.AppCard
import com.studykit.ui.motion.MotionSpec
import com.studykit.ui.theme.AppTheme
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 单卡遗忘曲线的**纯数学**（v3.0-A Task 9）：零 Android 依赖，JVM 直测。
 *
 * ## ⛔ 到期标记与调度器同式（一票否决级约定）
 * [dueMarkerDays] **必须**保持为 [FsrsModel.nextIntervalDays] 的直委托 —— 那正是
 * [FsrsKernel.nextIntervalDays]（调度器真实排期间隔的入口）在非 AGAIN 档内部调用的同一个函数。
 * **禁止**在这里另写一份反解式/半衰期近似：一旦分叉，「曲线说的那天」与「调度器排的那天」
 * 就各说一套，而这张图的全部可信度恰恰在于它是调度器自己的口径。
 * 等值性由 `CardRecallCurveTest.到期标记与调度器给的间隔逐值相等` 钉死：FSRS 内核 ×
 * 多档稳定度 × 多档目标保留率 × {HARD, GOOD, EASY} 三档评分逐值扫描（delta 0.0，比特级相等）；
 * 上限口径与脏稳定度的镜像各另有扫描用例。
 * 两条诚实边界：① [FsrsKernel.nextIntervalDays] 对 AGAIN 走的是它自己的 10 分钟专支
 * （[FsrsModel.TEN_MINUTES_IN_DAYS]），**不在**相等性主张内，测试里有单独用例钉住这个差别；
 * ② 这里说的是"对**当前**状态直接问调度器"——评分按钮上印的间隔是先 `review()` 把稳定度
 * 长上去之后再问，两个量本来就不同（曲线回答的是"从今往后不复习能撑多久"）。
 *
 * [sanitizeStability] 与 [DEFAULT_MAX_INTERVAL_DAYS] 是 FsrsKernel 私有夹取与其兜底的**镜像**：
 * 改动任何一处而忘了另一处，⛔ 扫描会红。
 */
object CardRecallCurve {

    /**
     * 兜底排期上限（天）：这个默认值只服务"没有政策上下文"的纯调用与测试；
     * 页面接线时应显式传 `Scheduling.maxIntervalDays`，与评分按钮同一套排期政策。
     */
    const val DEFAULT_MAX_INTERVAL_DAYS = 3650.0

    /**
     * 与 [FsrsKernel] 私有 `sanitizeStability` 同一规则：null/非有限/≤0 落到稳定性地板
     * [FsrsModel.MIN_STABILITY_FLOOR]，正数封顶 [FsrsModel.MAX_STABILITY]。
     * 这是**镜像**不是引用（那边的夹取是内核私有函数）；两边必须同改，⛔ 测试的脏值用例守着。
     */
    fun sanitizeStability(raw: Double?): Double =
        if (raw == null || !raw.isFinite() || raw <= 0.0) FsrsModel.MIN_STABILITY_FLOOR
        else raw.coerceAtMost(FsrsModel.MAX_STABILITY)

    /**
     * 第 [elapsedDays] 天的预测保留率：与调度器读 R 的同一个 [FsrsModel.recall]，
     * 稳定度先过 [sanitizeStability]。
     */
    fun recallAt(w: DoubleArray, stabilityDays: Double, elapsedDays: Double): Double =
        FsrsModel.recall(w, sanitizeStability(stabilityDays), elapsedDays.coerceAtLeast(0.0))

    /**
     * 第 1..[days] 天各一点的 `(天数, 保留率)` 序列；`days <= 0` 返回空表。
     * 幂律衰减对 t 单调不增（测试钉住），视图只画不重算。
     */
    fun points(w: DoubleArray, stabilityDays: Double, days: Int): List<Pair<Double, Double>> {
        if (days <= 0) return emptyList()
        val s = sanitizeStability(stabilityDays)
        return (1..days).map { t -> t.toDouble() to FsrsModel.recall(w, s, t.toDouble()) }
    }

    /**
     * ⛔ 曲线的「到期标记」= 调度器对同一状态会给出的间隔天数。
     * 直委托 [FsrsModel.nextIntervalDays]，与 [FsrsKernel.nextIntervalDays] 的
     * HARD/GOOD/EASY 支逐比特同式（AGAIN 的 10 分钟专支除外，见类 KDoc 边界①）。
     */
    fun dueMarkerDays(
        w: DoubleArray,
        stabilityDays: Double,
        targetRetention: Double,
        maxIntervalDays: Double = DEFAULT_MAX_INTERVAL_DAYS,
    ): Double = FsrsModel.nextIntervalDays(w, sanitizeStability(stabilityDays), targetRetention, maxIntervalDays)
}

/**
 * 翻卡页底部的「这条记忆还会留多久」折叠区（默认收起，v3.0-A Task 9）。
 *
 * 只在**活跃内核是 FSRS** 时渲染：曲线与到期标记用的都是 FSRS 那条幂律公式，
 * 半衰期内核活跃时调度器走的是另一条曲线（[com.studykit.data.memory.MemoryModel]），
 * 这时候画这张图就是"图与排期两张皮"，宁可整区不出现。
 *
 * 参数口径（与页面既有约定一一对齐，不留第二套解释）：
 *  - 到期标记 ⛔ 与调度器同式（见 [CardRecallCurve] 的 KDoc），不另写反解式；
 *  - 目标保留率与上限取**页面同一份** [Scheduling]（`scheduling.targetRecall` /
 *    `scheduling.maxIntervalDays`），写死 0.9 就等于在看板侧另起一份排期政策；
 *  - FSRS 参数向量的取法与看板/提醒侧同一约定（`settings.fsrsActiveParams` 非空用它，
 *    没校准过才取发布默认）；评分按钮预览那侧走 [KernelHub.forId] 单参口径，未注入校准参数时两者同源。
 *
 * 空态：新卡（`fsrsStability == null`，FSRS 还没写过这条）没有曲线可画 —— 如实说明，
 * 不拿地板稳定度画一条"看着像"的线（[com.studykit.data.memory.recallForDisplay] 的
 * "不许拿空状态硬算"同一纪律）。
 *
 * 画法是仓内既有约定（[com.studykit.ui.stats.LoadBandChart] 同款）：Canvas 折线，
 * 色值只取 `colors.accent`（曲线）/ `colors.divider`（坐标轴与今天线）/
 * `colors.warning`（目标虚线与交点圆点），不新增色值 token；整张图一条带数字的
 * `contentDescription`，同一组数字另有可见行；图下 caption 讲「图怎么读」，
 * 照 StatsScreen「你的遗忘曲线」那块「图怎么读」的写法。
 */
@Composable
fun RecallCurveSection(
    word: Word,
    scheduling: Scheduling,
    elapsedDays: Double,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    val kernel = KernelHub.forId(AppTheme.settings.schedulingKernel)
    if (kernel.id != "FSRS") return
    val w = AppTheme.settings.fsrsActiveParams ?: FsrsModel.DEFAULT_PARAMS

    var expanded by rememberSaveable { mutableStateOf(false) }
    // (稳定度, 到期标记) 捆在一起：非空即"有曲线可画"，展开分支里不用再判 null 后硬取
    val curve = word.fsrsStability?.let { s ->
        s to CardRecallCurve.dueMarkerDays(w, s, scheduling.targetRecall, scheduling.maxIntervalDays)
    }
    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = MotionSpec.snap,
        label = "curveFoldArrow",
    )

    Column(modifier = modifier.fillMaxWidth()) {
        Spacer(Modifier.height(AppTheme.space.sm))
        AppCard(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(text = "这条记忆还会留多久", style = texts.cardTitle)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        // 折叠着也要能判断"值不值得点开"（HabitListScreen 折叠头同口径）
                        text = curve?.let { (_, marker) ->
                            "按现在的稳定度，${formatIntervalLabel(marker)}就掉到目标线"
                        } ?: "还没评过分，暂时没有曲线",
                        style = texts.caption,
                        color = colors.secondaryText,
                    )
                }
                Text(
                    text = if (expanded) "收起" else "展开",
                    style = texts.caption.copy(color = colors.accentInk),
                )
                Spacer(Modifier.width(AppTheme.space.xs))
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (expanded) "收起" else "展开",
                    tint = colors.accentInk,
                    modifier = Modifier.graphicsLayer(rotationZ = arrowRotation),
                )
            }
            if (expanded) {
                Spacer(Modifier.height(AppTheme.space.md))
                if (curve == null) {
                    Text(
                        text = "这张卡还没被排期内核记过稳定度，画不出遗忘曲线；评一次分之后这里就会出现。",
                        style = texts.caption,
                        color = colors.secondaryText,
                    )
                } else {
                    val (stabilityDays, markerDays) = curve
                    RecallCurveChart(
                        w = w,
                        stabilityDays = stabilityDays,
                        targetRetention = scheduling.targetRecall,
                        markerDays = markerDays,
                        elapsedDays = elapsedDays,
                    )
                }
            }
        }
    }
}

/**
 * 曲线本体：折线 + 今天竖线 + 目标保留率横虚线 + 交点圆点。
 * 数学全部来自 [CardRecallCurve]（⛔ 同式），这里只画不算第二套。
 *
 * 横轴取"到期标记的两倍"起步、至少两周，封顶 730 天（交点超出时贴右缘，可见行仍报真实数字），
 * 并保证今天那条竖线落在图内；纵轴下沿取"曲线末端与目标线的较低者"再留 0.06 余量，上沿 1.0。
 */
@Composable
private fun RecallCurveChart(
    w: DoubleArray,
    stabilityDays: Double,
    targetRetention: Double,
    markerDays: Double,
    elapsedDays: Double,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    val plotDays = ceil(
        max(max(markerDays * 2.0, elapsedDays + 3.0), 14.0),
    ).coerceAtMost(730.0).toInt()
    val points = CardRecallCurve.points(w, stabilityDays, plotDays)
    val nowRecall = CardRecallCurve.recallAt(w, stabilityDays, elapsedDays)
    val targetPct = (targetRetention * 100).roundToInt()
    val markerLabel = formatIntervalLabel(markerDays)
    val nowPct = (nowRecall * 100).roundToInt()
    // Canvas 对读屏等于壁纸，描述里必须有内容数字（LoadBandChart 的无障碍裁定）
    val description = "遗忘曲线：今天大约还记得 $nowPct%，不复习的话会在${markerLabel}掉到" +
        "目标保留率 $targetPct%；橙虚线与蓝线的交点就是调度器给这张卡排的下一次复习。"

    Column(modifier = modifier.fillMaxWidth()) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
                .semantics { contentDescription = description },
        ) {
            val axisH = size.height - 14.dp.toPx() // 底部留一格横轴条
            val xMax = plotDays.toDouble()
            val yMin = (minOf(points.last().second, targetRetention) - 0.06).coerceAtLeast(0.0)
            fun x(t: Double) = size.width * (t / xMax).toFloat().coerceIn(0f, 1f)
            fun y(r: Double) = axisH * ((1.0 - r) / (1.0 - yMin)).toFloat().coerceIn(0f, axisH)

            // 坐标轴（divider）：底 + 左
            drawLine(colors.divider, Offset(0f, axisH), Offset(size.width, axisH), 1.dp.toPx())
            drawLine(colors.divider, Offset(0f, 0f), Offset(0f, axisH), 1.dp.toPx())

            // 目标保留率横虚线（warning）
            drawLine(
                color = colors.warning,
                start = Offset(0f, y(targetRetention)),
                end = Offset(size.width, y(targetRetention)),
                strokeWidth = 1.5.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)),
            )

            // 曲线折线（accent）：从 (0, R=1) 起，逐日采样点走线
            drawPath(
                path = Path().apply {
                    moveTo(0f, y(1.0))
                    points.forEach { (t, r) -> lineTo(x(t), y(r)) }
                },
                color = colors.accent,
                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
            )

            // 今天竖线（divider）：逾期时它落在交点右边 —— 这正是要如实看见的
            if (elapsedDays in 0.0..xMax) {
                drawLine(
                    color = colors.divider.copy(alpha = 0.9f),
                    start = Offset(x(elapsedDays), 0f),
                    end = Offset(x(elapsedDays), axisH),
                    strokeWidth = 1.dp.toPx(),
                )
            }

            // ⛔ 交点圆点（warning）：横坐标与调度器给的间隔同值；超出横轴封顶时贴右缘
            drawCircle(
                color = colors.warning,
                radius = 4.dp.toPx(),
                center = Offset(x(minOf(markerDays, xMax)), y(targetRetention)),
            )
        }
        Spacer(Modifier.height(AppTheme.space.xs))
        // 与 description 同一组数字的**可见**版：数字不许只给读屏用户（LoadBandChart 裁定）
        Text(
            text = "现在大约还记得 $nowPct%，照这条线${markerLabel}就会掉到目标保留率 $targetPct%（交点那颗点）。",
            style = texts.caption,
            color = colors.primaryText,
        )
        Spacer(Modifier.height(AppTheme.space.xs))
        Text(
            text = "图怎么读：横轴是距上次复习的天数（封顶 730 天，交点超出时贴在右缘），" +
                "纵轴是预测的保留率；蓝线是“不再复习”时的下滑，橙虚线是设置里的目标保留率，" +
                "两线交点就是调度器给这张卡排的下一次复习（和排期同一条公式，不是另算的）。" +
                "评一次分之后稳定度会更新，这条曲线跟着重画。",
            style = texts.caption,
            color = colors.secondaryText,
        )
    }
}
