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
 * 单卡遗忘曲线的**纯数学 + 摆位**（v3.0-A Task 9）：零 Android 依赖，JVM 直测。
 *
 * ## ⛔ 到期标记与调度器同式（一票否决级约定）
 * [dueMarkerDays] **必须**保持为 [FsrsModel.nextIntervalDays] 的直委托 —— 那正是
 * `FsrsKernel.nextIntervalDays`（调度器真实排期间隔的入口）在非 AGAIN 档内部调用的同一个函数。
 * **禁止**在这里另写一份反解式/半衰期近似：一旦分叉，「曲线说的那天」与「调度器排的那天」
 * 就各说一套，而这张图的全部可信度恰恰在于它是调度器自己的口径。
 * 等值性由两条扫描钉死（delta 0.0，比特级相等，FSRS 内核 × 多档稳定度 × 多档目标保留率 ×
 * {HARD, GOOD, EASY} 三档评分）：
 *  - `到期标记与调度器给的间隔逐值相等`：稳定度一端扫到 {0, −4, NaN}（落到地板
 *    [FsrsModel.MIN_STABILITY_FLOOR]），另一端扫到 **40000**（越过封顶 [FsrsModel.MAX_STABILITY]）。
 *  - `到期标记与调度器在任意排期上限下逐值相等`：排期上限 {3, 21, 365, 3650, **40000**}。
 *    ⚠ 封顶那条支**只有把上限一起放到 40000 才咬得住**：上限还是 3650 时，"夹到 36500"与
 *    "忘了夹、直接拿 40000"都会被排期上限再压成 3650，扫描照样绿。修复前那句"改封顶扫描会红"
 *    就是这么空着的（扫描只到 S=900/800，天花板根本没被碰到）。
 * 两条诚实边界：① `FsrsKernel.nextIntervalDays` 对 AGAIN 走的是它自己的 10 分钟专支
 * （[FsrsModel.TEN_MINUTES_IN_DAYS]），**不在**相等性主张内，测试里有单独用例钉住这个差别；
 * ② 这里说的是"对**当前**状态直接问调度器"——评分按钮上印的间隔是先 `review()` 把稳定度
 * 长上去之后再问，两个量本来就不同（曲线回答的是"从今往后不复习能撑多久"）。
 *
 * [sanitizeStability] 自修复轮起**不是镜像，而是直委托** [FsrsModel.sanitizeStability]：全仓只剩这一份
 * 夹取（`FsrsKernel` 那份私有函数与 `FsrsModelCore` 那份同批收拢，NaN/负/零/超上限四种输入在
 * 推理、回测、曲线三处同落点由 `稳定度夹取全仓只有一份` 钉住）。于是上面那两条扫描保证的是
 * "**曲线与调度器用的仍是同一份夹取**"——任何一处换回自己抄的地板/封顶就红；它**不**保证
 * [FsrsModel.MAX_STABILITY] 这个数值本身不许改（那是 py-fsrs 口径的事，改它得连黄金值夹具一起改，
 * 归 `FsrsModelParityTest`）。
 *
 * [DEFAULT_MAX_INTERVAL_DAYS] 与内核的兜底**无关**：内核遇到非有限上限走的是
 * [FsrsModel.MAX_INTERVAL_FALLBACK]（365 天）；这个 3650 只是"没有政策上下文"的纯调用默认值。
 */
object CardRecallCurve {

    /**
     * 纯调用与测试用的排期上限（天）：⛔ **不是默认参数** —— 有政策上下文的调用点必须显式传
     * `Scheduling.maxIntervalDays`，忘了传就该编译不过，而不是拿这个数悄悄绿过去。
     * 它也**不是**内核的兜底（那条是 [FsrsModel.MAX_INTERVAL_FALLBACK] = 365 天，只在非有限上限时生效）。
     */
    const val DEFAULT_MAX_INTERVAL_DAYS = 3650.0

    /** 横轴最多往后画这么多天：再远的下滑肉眼分不出，可见行照旧报真实数字（交点贴右缘） */
    const val MAX_WINDOW_DAYS = 730.0

    /** 横轴最少往后画这么多天：刚评过分的卡不给它一条挤成一条缝的线 */
    const val MIN_WINDOW_DAYS = 14.0

    /** "距上次复习"超过这个天数（约一百年）只可能是脏时间戳：如实走空态，不硬算一条曲线 */
    const val MAX_ELAPSED_DAYS = 36500.0

    /**
     * ⛔ 全仓唯一的稳定度夹取 = [FsrsModel.sanitizeStability]（null/非有限/≤0 落到地板
     * [FsrsModel.MIN_STABILITY_FLOOR]，正数封顶 [FsrsModel.MAX_STABILITY]）。这里只留个名字给
     * 纯数学面，**别再在这里抄一份规则** —— 修复轮之前它是 `FsrsKernel` 私有那份的镜像，三处镜像
     * 谁改都发现不了，现在曲线与调度器用的是同一个函数，结构上不可能分叉。
     */
    fun sanitizeStability(raw: Double?): Double = FsrsModel.sanitizeStability(raw)

    /**
     * 第 [elapsedDays] 天的预测保留率：与调度器读 R 的同一个 [FsrsModel.recall]，
     * 稳定度先过 [sanitizeStability]。
     */
    fun recallAt(w: DoubleArray, stabilityDays: Double, elapsedDays: Double): Double =
        FsrsModel.recall(w, sanitizeStability(stabilityDays), elapsedDays.coerceAtLeast(0.0))

    /**
     * 第 [fromDay]..[toDay] 天各一点的 `(天数, 保留率)` 序列；`fromDay > toDay` 返回空表。
     * 幂律衰减对 t 单调不增（测试钉住），视图只画不重算。
     * 区间两端都**必填**：[placement] 要把窗口挪到"今天往后"，漏一个起点就会把过去那段画进来。
     */
    fun points(w: DoubleArray, stabilityDays: Double, fromDay: Int, toDay: Int): List<Pair<Double, Double>> {
        if (fromDay > toDay) return emptyList()
        val s = sanitizeStability(stabilityDays)
        return (fromDay..toDay).map { t -> t.toDouble() to FsrsModel.recall(w, s, t.toDouble()) }
    }

    /**
     * ⛔ 曲线的「到期标记」= 调度器对同一状态会给出的间隔天数（**距上次复习**口径）。
     * 直委托 [FsrsModel.nextIntervalDays]，与 `FsrsKernel.nextIntervalDays` 的
     * HARD/GOOD/EASY 支逐比特同式（AGAIN 的 10 分钟专支除外，见类 KDoc 边界①）。
     *
     * @param maxIntervalDays 排期上限**必填**：页面传 `Scheduling.maxIntervalDays`，与评分按钮/实排
     *   同一套政策；纯调用与测试要默认口径就显式写 [DEFAULT_MAX_INTERVAL_DAYS]。
     */
    fun dueMarkerDays(
        w: DoubleArray,
        stabilityDays: Double,
        targetRetention: Double,
        maxIntervalDays: Double,
    ): Double = FsrsModel.nextIntervalDays(w, sanitizeStability(stabilityDays), targetRetention, maxIntervalDays)

    /**
     * 图上要摆的全部数字，⛔ **一次算在同一个时间原点里**（v3.0-A T9 修复轮把它从 Composable
     * 抽出来 —— 摆位留在视图里就没有 JVM 回归测试，而修复前的 Important 正是"点画在
     * 『距上次复习』的刻度上、话却说成『距今天』"）。
     *
     * 横轴一律**「距今天」**：第 u 个点（u = 今天往后 u 天）读的是 `R(elapsedDays + u)`，
     * 于是曲线与目标保留率线的交点横坐标就是 [markerDaysFromToday] =
     * [dueMarkerDays] − [elapsedDays]，也就是文案里"再过 N 天就掉到目标线"的那个 N。
     * 图与话同源，靠的是**结构**（两处都从这一个函数取数），不靠注释提醒。
     *
     * 返回 null = 这份状态画不出曲线（没记过稳定度 / 稳定度是脏值 / "距上次复习"脏或荒谬 /
     * 参数向量脏到标记为非有限）。调用方一律走"暂时没有曲线"空态：本仓的规矩是
     * 展示层遇脏数据降级、**不崩** —— 修复前非有限的标记会把窗口算成 0 天、采样表算成空表，
     * 然后在 Canvas 绘制里 `points.last()` 抛 `NoSuchElementException`。
     */
    fun placement(
        w: DoubleArray,
        stabilityDays: Double,
        targetRetention: Double,
        elapsedDays: Double,
        maxIntervalDays: Double,
    ): RecallCurvePlacement? {
        if (!stabilityDays.isFinite() || stabilityDays <= 0.0) return null
        if (!elapsedDays.isFinite() || elapsedDays < 0.0 || elapsedDays > MAX_ELAPSED_DAYS) return null
        val markerDays = dueMarkerDays(w, stabilityDays, targetRetention, maxIntervalDays)
        if (!markerDays.isFinite()) return null
        val todayRecall = recallAt(w, stabilityDays, elapsedDays)
        if (!todayRecall.isFinite()) return null
        val markerDaysFromToday = markerDays - elapsedDays
        // 窗口：交点留在图里并再留 3 天的余量，最短两周（刚评过的卡），最长封顶 730 天
        val windowDays = ceil(
            max(markerDaysFromToday + 3.0, MIN_WINDOW_DAYS),
        ).coerceAtMost(MAX_WINDOW_DAYS).toInt()
        // 采样从"今天或今天之后的第一个整天"起：过去那段不属于这条轴，画进来就是把 R=1 顶到今天
        val startDay = ceil(elapsedDays).toInt()
        return RecallCurvePlacement(
            windowDays = windowDays,
            todayRecall = todayRecall,
            markerDaysFromToday = markerDaysFromToday,
            points = points(w, stabilityDays, fromDay = startDay, toDay = startDay + windowDays)
                .map { (t, r) -> (t - elapsedDays) to r },
        )
    }
}

/**
 * [CardRecallCurve.placement] 的产物（纯数据，零 Android 依赖）。
 *
 * ⛔ 四个量的横坐标一律是"**距今天**"：[points] 的 u 与 [markerDaysFromToday] 同一个原点，
 * 所以文案报的数与画在图上的位置不可能再各说一套。
 *
 * @param windowDays 横轴往后画多少天（[CardRecallCurve.MIN_WINDOW_DAYS]..[CardRecallCurve.MAX_WINDOW_DAYS]）
 * @param todayRecall 今天的预测保留率 = 曲线在左缘（u=0）的高度
 * @param markerDaysFromToday 到期标记在今天之后几天（= 调度器那个间隔里**还没走完**的部分）；
 *   负数表示交点已经在今天左边（这张卡到期晚了/逾期了）
 * @param points `(距今天的 u 天, 保留率)` 序列，非空
 */
data class RecallCurvePlacement(
    val windowDays: Int,
    val todayRecall: Double,
    val markerDaysFromToday: Double,
    val points: List<Pair<Double, Double>>,
) {
    /**
     * 交点还在今天右边 → 画那颗圆点；已经到今天或今天左边 → **不画**：
     * 把过去的交点夹到左缘就是在画一颗图线上不存在的点，那种情况由文案如实说"N 天前就掉破了"。
     */
    val markerIsFuture: Boolean get() = markerDaysFromToday >= 0.0
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
 *    `scheduling.maxIntervalDays`），写死 0.9 就等于在看板侧另起一份排期政策；上限是
 *    [CardRecallCurve.dueMarkerDays] / [CardRecallCurve.placement] 的**必填**参数，漏传编译不过；
 *  - 图上的时间原点只有**一个**（今天）：横轴、今天线、交点、文案里的数全从
 *    [CardRecallCurve.placement] 一处取（修复轮的 Important 就断在这儿，见其 KDoc）；
 *  - FSRS 参数向量的取法与看板/提醒侧同一约定（`settings.fsrsActiveParams` 非空用它，
 *    没校准过才取发布默认）；评分按钮预览那侧走 [KernelHub.forId] 的**双参**口径（同一份
 *    `AppSettings`），两者读的就是同一份向量（v3.0-A T2.6：按钮那侧曾经只交 id，两处会各说一套）。
 *
 * 空态：新卡（`fsrsStability == null`，FSRS 还没写过这条）没有曲线可画 —— 如实说明，
 * 不拿地板稳定度画一条"看着像"的线（[com.studykit.data.memory.recallForDisplay] 的
 * "不许拿空状态硬算"同一纪律）；稳定度/已拖天数是脏值时同样走这条空态（[CardRecallCurve.placement]
 * 返回 null），而不是到 Canvas 里崩。
 *
 * 画法是仓内既有约定（[com.studykit.ui.stats.LoadBandChart] 同款）：Canvas 折线，
 * 色值只取 `colors.accent`（曲线）/ `colors.divider`（坐标轴）/
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
    val settings = AppTheme.settings
    val kernel = KernelHub.forId(settings.schedulingKernel, settings)
    if (kernel.id != "FSRS") return
    val w = settings.fsrsActiveParams ?: FsrsModel.DEFAULT_PARAMS

    var expanded by rememberSaveable { mutableStateOf(false) }
    // 摆位（含到期标记）一次算完：非空即"有曲线可画"，展开分支里不用再判 null 后硬取
    val placement = word.fsrsStability?.let { s ->
        CardRecallCurve.placement(w, s, scheduling.targetRecall, elapsedDays, scheduling.maxIntervalDays)
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
                        text = placement?.let { "按现在的稳定度，${dropPhrase(it)}" } ?: "还没评过分，暂时没有曲线",
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
                if (placement == null) {
                    Text(
                        text = "这张卡还没有可用的稳定度读数（没评过分，或那份读数不可信），画不出遗忘曲线；" +
                            "评一次分之后这里就会出现。",
                        style = texts.caption,
                        color = colors.secondaryText,
                    )
                } else {
                    RecallCurveChart(placement = placement, targetRetention = scheduling.targetRecall)
                }
            }
        }
    }
}

/**
 * 「什么时候掉到目标线」的人话（v3.0-A T9 修复轮）。
 *
 * ⛔ 这里的数**就是** [RecallCurvePlacement.markerDaysFromToday]：横轴原点（今天）与文案原点同一个，
 * 所以"看到的那颗点"与"读到的那句话"是同一个坐标（测试
 * `到期标记与今天线同源 文案报的数就是画在图上的位置` 钉住它，而不是"调度器那个间隔"）。
 */
internal fun dropPhrase(placement: RecallCurvePlacement): String =
    if (placement.markerDaysFromToday < 0.0) {
        "${daysAgoLabel(-placement.markerDaysFromToday)}就掉破了目标线"
    } else {
        "${formatIntervalLabel(placement.markerDaysFromToday)}就掉到目标线"
    }

/** 「N 天前」：不足一天说「今天」——逾期几小时的卡，"今天前"是病句而"今天就掉破了"读得通 */
private fun daysAgoLabel(days: Double): String =
    if (!days.isFinite() || days < 1.0) "今天" else "${days.roundToInt()} 天前"

/**
 * 曲线本体：折线 + 目标保留率横虚线 + 交点圆点。
 * 数学与摆位全部来自 [CardRecallCurve.placement]（⛔ 同式、同原点），这里只画不算第二套。
 *
 * 横轴左缘就是**今天**（u=0，与纵轴重合，所以不再另画一条"今天竖线"），往右是"今天往后多少天"，
 * 窗口由 [CardRecallCurve.placement] 定（交点留 3 天余量、最短两周、封顶 730 天）；
 * 蓝线在左缘的高度就是今天的预测保留率（[RecallCurvePlacement.todayRecall]）。
 * 交点超出封顶时贴在右缘，可见行仍报真实数字；交点落在今天左边（逾期）时**不画那颗点**
 * （[RecallCurvePlacement.markerIsFuture]），改由文案说"N 天前就掉破了"。
 */
@Composable
private fun RecallCurveChart(
    placement: RecallCurvePlacement,
    targetRetention: Double,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    // 非空由 placement 保证（脏输入一律回 null 走空态），所以这里可以直接取端点
    val points = placement.points
    val xMax = placement.windowDays.toDouble()
    val targetPct = (targetRetention * 100).roundToInt()
    val nowPct = (placement.todayRecall * 100).roundToInt()
    val phrase = dropPhrase(placement)
    val markerIsFuture = placement.markerIsFuture
    // Canvas 对读屏等于壁纸，描述里必须有内容数字（LoadBandChart 的无障碍裁定）
    val description = "遗忘曲线：今天大约还记得 $nowPct%，不再复习的话${phrase}；" +
        "横轴从今天往后数（左缘就是今天），橙虚线是目标保留率 $targetPct%，" +
        if (markerIsFuture) {
            "两线交点那颗橙点就是这张卡下一次该复习的日子。"
        } else {
            "交点落在今天左边，也就是这张卡已经该复习了，所以图上不画那颗点。"
        }

    Column(modifier = modifier.fillMaxWidth()) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
                .semantics { contentDescription = description },
        ) {
            val axisH = size.height - 14.dp.toPx() // 底部留一格横轴条
            val yMin = (minOf(points.last().second, targetRetention) - 0.06).coerceAtLeast(0.0)
            fun x(u: Double) = size.width * (u / xMax).toFloat().coerceIn(0f, 1f)
            fun y(r: Double) = axisH * ((1.0 - r) / (1.0 - yMin)).toFloat().coerceIn(0f, axisH)

            // 坐标轴（divider）：底 + 左（左缘那条就是"今天"）
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

            // 曲线折线（accent）：左缘从今天的保留率起，逐日采样点走线
            drawPath(
                path = Path().apply {
                    moveTo(0f, y(placement.todayRecall))
                    points.forEach { (u, r) -> lineTo(x(u), y(r)) }
                },
                color = colors.accent,
                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
            )

            // ⛔ 交点圆点（warning）：横坐标与文案那个数同一个原点（距今天）；
            // 超出横轴封顶时贴右缘，落在今天左边时不画（画出去就是假点）
            if (markerIsFuture) {
                drawCircle(
                    color = colors.warning,
                    radius = 4.dp.toPx(),
                    center = Offset(x(minOf(placement.markerDaysFromToday, xMax)), y(targetRetention)),
                )
            }
        }
        Spacer(Modifier.height(AppTheme.space.xs))
        // 与 description 同一组数字的**可见**版：数字不许只给读屏用户（LoadBandChart 裁定）
        Text(
            text = "现在大约还记得 $nowPct%，不再复习的话$phrase" +
                if (markerIsFuture) {
                    "（图上那颗橙点就是它）。"
                } else {
                    "（交点在今天左边，图上不画点）。"
                },
            style = texts.caption,
            color = colors.primaryText,
        )
        Spacer(Modifier.height(AppTheme.space.xs))
        Text(
            text = "图怎么读：横轴是距今天的天数（左缘那条竖线就是今天，往后封顶 " +
                "${CardRecallCurve.MAX_WINDOW_DAYS.toInt()} 天，交点超出时贴在右缘），" +
                "纵轴是预测的保留率；蓝线是“不再复习”时的下滑，它在左缘的高度就是今天的保留率，" +
                "橙虚线是设置里的目标保留率，两线交点就是这张卡下一次该复习的日子。" +
                "这个日子由调度器同一条公式给出（不是另算的），只是换算到“距今天”这把尺子上：" +
                "它是上次评分排出的间隔里还没走完的那一段。" +
                "交点落到今天左边就说明已经逾期，这时候上面那行会说它几天前就掉破了。" +
                "评一次分之后稳定度会更新，这条曲线跟着重画。",
            style = texts.caption,
            color = colors.secondaryText,
        )
    }
}
