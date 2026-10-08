package com.studykit.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.studykit.data.memory.DayBand
import com.studykit.data.memory.SprintSurge
import com.studykit.ui.components.AppCard
import com.studykit.ui.components.EmptyState
import com.studykit.ui.components.SectionHeader
import com.studykit.ui.components.StatTile
import com.studykit.ui.theme.AppColors
import com.studykit.ui.theme.AppTheme
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

/** 少于这个样本数就不画"你的实测"：三四个点连成的折线会被当成结论 */
private const val MinCurveSamples = 20

/**
 * 记忆看板：把半衰期 / FSRS 模型已经算得出来、但用户看不见的那几件事摊开。
 *
 * 从上到下是"照这个练法会堆成什么样 → 接下来一周原本排了多少 → 已经存下多少长期记忆 →
 * 这些卡按模型口径有多稳 → 这一年实际练了哪几天 → 我到底忘多快 → 模型说记得的那批，我当场真记得吗"。
 * 顺序是刻意的：先看负担，再看资产，然后才是对过去的一年点名，最后才是那两条最抽象的曲线。
 *
 * 头两块挨在一起但不是同一张图（口径区别写在 [StatsUiState.loadBand] 的 KDoc 上）：
 * 负荷带由稳定度**反解**到期日，所以"今天没练"会如实变成"明天堆积"；
 * 下面那根 7 天柱只数已经落库的到期时间戳，读的是"原本排了多少"。
 *
 * 根 Column 只有一处 `verticalScroll` + 一处 `padding(pageH)`，**不碰 insets** ——
 * `AppNav` 根 Scaffold 的 `innerPadding` 是全仓唯一的键盘/系统栏消费者，
 * 这里再补一份就会把整页顶偏（设置页踩过两份 imePadding 的坑，同理）。
 */
@Composable
fun StatsScreen(viewModel: StatsViewModel, onBack: () -> Unit) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = AppTheme.space.pageH),
    ) {
        Spacer(Modifier.height(AppTheme.space.sm))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = colors.accentInk,
                )
            }
            Spacer(Modifier.width(AppTheme.space.xs))
            Text(text = "记忆看板", style = texts.pageTitle)
        }
        Spacer(Modifier.height(AppTheme.space.md))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AppTheme.space.sm),
        ) {
            StatTile(value = "${state.totalWords}", label = "单词总数", modifier = Modifier.weight(1f))
            StatTile(value = "${state.plannedCount}", label = "有排期的词", modifier = Modifier.weight(1f))
            StatTile(value = "${state.sampleCount}", label = "复习记录", modifier = Modifier.weight(1f))
        }

        Spacer(Modifier.height(AppTheme.space.lg))
        SectionHeader(title = "接下来两周的负荷")
        Spacer(Modifier.height(AppTheme.space.md))
        AppCard(modifier = Modifier.fillMaxWidth()) {
            LoadBandSection(bands = state.loadBand, surge = state.sprintSurge)
        }

        Spacer(Modifier.height(AppTheme.space.lg))
        SectionHeader(title = "未来 7 天要复习多少")
        Spacer(Modifier.height(AppTheme.space.md))
        AppCard(modifier = Modifier.fillMaxWidth()) {
            if (state.forecast.all { it.count == 0 }) {
                EmptyState(
                    title = "接下来一周没有排期",
                    caption = "背完一轮之后才会排出来。",
                )
            } else {
                ForecastBars(forecast = state.forecast)
            }
        }

        Spacer(Modifier.height(AppTheme.space.lg))
        SectionHeader(title = "这些记忆能扛多久")
        Spacer(Modifier.height(AppTheme.space.md))
        AppCard(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "半衰期扛过一档的词有多少：过 10 天不算白背，过 90 天才叫真记住了。",
                style = texts.caption,
                color = colors.secondaryText,
            )
            Spacer(Modifier.height(AppTheme.space.sm))
            state.durability.forEach { bucket ->
                DurabilityRow(
                    label = "≥ ${bucket.minHalfLifeDays} 天",
                    count = bucket.count,
                    share = bucket.shareLabel,
                    fraction = bucket.sharePercent / 100f,
                )
            }
        }

        Spacer(Modifier.height(AppTheme.space.lg))
        SectionHeader(title = "稳定度全景")
        Spacer(Modifier.height(AppTheme.space.md))
        AppCard(modifier = Modifier.fillMaxWidth()) {
            StabilityPanoramaSection(bins = state.stability, cardCount = state.stabilityCardCount)
        }

        Spacer(Modifier.height(AppTheme.space.lg))
        SectionHeader(title = "这一年练了哪几天")
        Spacer(Modifier.height(AppTheme.space.md))
        AppCard(modifier = Modifier.fillMaxWidth()) {
            ReviewHeatmapSection(cells = state.heatmap)
        }

        Spacer(Modifier.height(AppTheme.space.lg))
        SectionHeader(title = "你的遗忘曲线")
        Spacer(Modifier.height(AppTheme.space.md))
        AppCard(modifier = Modifier.fillMaxWidth()) {
            val thinData = state.sampleCount < MinCurveSamples
            if (thinData) {
                EmptyState(
                    title = "复习记录还不够画你的曲线",
                    caption = "攒够 $MinCurveSamples 次复习就会出现在这里。",
                )
            }
            RetentionChart(
                ebbinghaus = MemoryHealth.EbbinghausPoints,
                modelCurve = state.modelCurve,
                curve = state.curve,
                showModel = thinData,
            )
            Spacer(Modifier.height(AppTheme.space.sm))
            ChartLegend(showModel = thinData)
            Spacer(Modifier.height(AppTheme.space.xs))
            Text(
                text = if (thinData) {
                    "蓝线是按全库平均半衰期算的模型预测，不是拟合你个人的曲线；" +
                        "橙线是艾宾浩斯 1885 年的经典实验数值（被后世反复转述），只当参照，不是你的数据。"
                } else {
                    "绿点是你自己每次复习的间隔与结果分桶算出来的实测回忆率；" +
                        "橙线只是参照 —— 你的点越靠右上，说明排期越保守。"
                },
                style = texts.caption,
                color = colors.secondaryText,
            )
        }

        // 「延迟后测」卡（v2.7 spec §9 / D7）：放在最后，因为它读的是上面那张曲线的同一个原料，
        // 但回答的是另一个问题——不是"我忘多快"，而是"模型说我会记得，我真记得吗"
        Spacer(Modifier.height(AppTheme.space.lg))
        SectionHeader(title = "延迟后测")
        Spacer(Modifier.height(AppTheme.space.md))
        RetentionCard(state.retention)
        Spacer(Modifier.height(AppTheme.space.xl))
    }
}

/**
 * 「接下来两周的负荷」那一节：负荷带本体 + 考前涌量预警那一行（v3.0-B Task 7）。
 *
 * 图的画法、图注、读屏描述全在 [LoadBandChart]，这一层一个字都没改；它只多干一件事——
 * 读到涌量时在那张图下面补一句人话。与 `ReviewHeatmapSection` / `StabilityPanoramaSection`
 * 同样是 `internal` 的整节，为的是这一行能被无 ViewModel 的渲染测试真组合一次
 * （[com.studykit.ui.stats.LoadBandRenderTest]），而不是只活在整页里、只有真机才看得见。
 *
 * ## 那一行为什么既判 `warn` 又判空
 * [SprintSurgeState.warn] 里已经含「ratio 非 null 且严格大于 2.0」两道判定（住在
 * [SprintSurge.shouldWarn]，此处不重述阈值）。这里的显式判空只为让编译器把非空那一份交给文案函数
 * （不给 `!!`）——顺手也就堵死「把 null 当 0 渲染」那条路：那句会印成「峰值约是中位的 0.0 倍」，
 * 而 null 的本意是**没有读数**（格子不够 / 中位为 0），不是"涌量为零"。
 *
 * 文案住在 [SprintSurge.warningLine] 这个纯函数里，不在这里现写：本仓把屏幕上那句话都放进纯函数，
 * 好让单测逐字钉住（`MistakeScheduling` / `RedoFlow` 同一条约定）。颜色取现成的 `warningInk`
 * （本族文字专用色，压白卡 5.60:1），不新增色值、不加新的 section。
 */
@Composable
internal fun LoadBandSection(
    bands: List<DayBand>,
    surge: SprintSurgeState,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    val ratio = surge.ratio
    Column(modifier = modifier.fillMaxWidth()) {
        LoadBandChart(bands = bands)
        if (ratio != null && surge.warn) {
            Spacer(Modifier.height(AppTheme.space.xs))
            Text(
                text = SprintSurge.warningLine(ratio),
                style = texts.caption,
                color = colors.warningInk,
            )
        }
    }
}

/**
 * 延迟后测卡：三个间隔桶各自的"实测 vs 模型预测"与样本量（计划 B Task 19）。
 *
 * 三行永远按 [RetentionBuckets.CardLabels] 的顺序在，**没数据的档也占一行**（写"还没记录"），
 * 因为少了那一行用户会以为这个档不存在，而不是自己还没攒到。
 *
 * [RetentionBuckets] 已经算完全部数字，这里只排版；分桶、达标、"样本还少"的阈值
 * 都不允许在本文件再判一遍。
 */
@Composable
private fun RetentionCard(buckets: List<RetentionBucket>) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    val byLabel = buckets.associateBy { it.label }
    val legacy = byLabel[RetentionBuckets.LABEL_UNKNOWN]
    Column {
        Text(
            text = "到期那天模型说你还能记得多少，你当场答对了多少：按间隔分档对一次账。",
            style = texts.caption,
            color = colors.secondaryText,
        )
        Spacer(Modifier.height(AppTheme.space.sm))
        RetentionBuckets.CardLabels.forEach { label ->
            RetentionBucketRow(label = label, bucket = byLabel[label])
        }
        if (legacy != null) {
            // spec §9：迁移前回填的 grade=-1 "单列不计入"——它们只在这里报到，不进上面三行
            Spacer(Modifier.height(AppTheme.space.xs))
            Text(
                text = "另有 ${legacy.n} 条没记评分的旧数据（迁移前的记录），不参与上面的对照。",
                style = texts.caption,
                color = colors.secondaryText,
            )
        }
        Spacer(Modifier.height(AppTheme.space.xs))
        Text(
            text = "同一个口径的原始流水就在备份包里（review_history.csv），想自己算一遍随时拿得出。" +
                "本应用不上传任何东西，这张卡只能上线前后自己跟自己比。",
            style = texts.caption,
            color = colors.secondaryText,
        )
    }
}

/**
 * 一行一个档。与 [DurabilityRow] 同构（标签 + 右侧数字 + 比例条），但进度的语义不一样：
 * 条子装的是**实测**，预测值只写在文字里不给第二条，免得图例变成四条颜色没人读得懂。
 */
@Composable
private fun RetentionBucketRow(label: String, bucket: RetentionBucket?) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    val n = bucket?.n ?: 0
    val thin = RetentionBuckets.isThin(n)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = AppTheme.space.sm)
            .semantics {
                contentDescription = if (bucket == null) {
                    "$label：还没有记录"
                } else {
                    "$label：实测 ${rateLabel(bucket.observedRate)}，" +
                        "模型预测 ${bucket.predictedMean?.let(::rateLabel) ?: "当时没记"}，共 $n 次"
                }
            },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = RetentionBuckets.displayNameOf(label), style = texts.body, modifier = Modifier.weight(1f))
            Text(
                text = if (bucket == null) {
                    "还没记录"
                } else {
                    "实测 ${rateLabel(bucket.observedRate)} · 预测 ${bucket.predictedMean?.let(::rateLabel) ?: "没记"}"
                },
                style = texts.caption,
                color = colors.secondaryText,
            )
        }
        Spacer(Modifier.height(AppTheme.space.xs))
        LinearProgressIndicator(
            progress = { (bucket?.observedRate ?: 0.0).toFloat() },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp),
            // 预测高于实测 = 模型把这个档估乐观了，用 warning 提醒；反之是保守（success）。
            // 没数据时既不是好也不是坏，用 divider，不给一个凭空的颜色。
            color = when {
                bucket == null -> colors.divider
                (bucket.predictedMean ?: bucket.observedRate) > bucket.observedRate -> colors.warning
                else -> colors.success
            },
            trackColor = colors.divider,
            strokeCap = StrokeCap.Round,
        )
        Spacer(Modifier.height(AppTheme.space.xs))
        Text(
            // 没数据那一档 n=0，[RetentionBuckets.isThin] 已经把它算进"样本还少"了，这里不再判一次 null
            text = if (thin) "$n 次 · 样本还少，先别当真" else "$n 次",
            style = texts.caption,
            color = colors.secondaryText,
        )
    }
}

/**
 * 保留率的可读形式。
 *
 * 不直接印四舍五入的整数：`MemoryHealth` 那侧已经为同一个坑付过学费（见 `DurabilityBucket.shareLabel`）——
 * "有 1 次却显示 0%"念起来像"一次都没成"。0 次才是真的 0%。
 */
private fun rateLabel(rate: Double): String {
    val percent = (rate * 100).roundToInt()
    return when {
        percent == 0 && rate > 0.0 -> "不足 1%"
        else -> "$percent%"
    }
}

/**
 * 一周柱状。柱高直接算 dp，**不用 `weight`**：`weight` 只在 `RowScope`/`ColumnScope`
 * 的直接子节点有效，套在 Column 里那层会编译失败。
 *
 * 用普通 Composable 而不是 Canvas：这样每一根柱子都能被读屏念出来（"9 月 23 日 要复习 12 个"），
 * 而 Canvas 画出来的图对无障碍等于一张壁纸。
 */
@Composable
private fun ForecastBars(forecast: List<DayLoad>) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    val peak = (forecast.maxOfOrNull { it.count } ?: 0).coerceAtLeast(1)
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AppTheme.space.xs, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.Bottom,
        ) {
            forecast.forEach { day ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = "${day.count}", style = texts.caption, color = colors.secondaryText)
                    Spacer(Modifier.height(2.dp))
                    Spacer(
                        modifier = Modifier
                            .width(28.dp)
                            .height((6 + 54 * day.count / peak).dp)
                            .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                            .background(if (day.count == 0) colors.divider else colors.accent)
                            .semantics {
                                contentDescription = "${day.date} 要复习 ${day.count} 个"
                            },
                    )
                }
            }
        }
        Spacer(Modifier.height(AppTheme.space.xs))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AppTheme.space.xs, Alignment.CenterHorizontally),
        ) {
            forecast.forEach { day ->
                Text(
                    text = day.date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
                        .removePrefix("星期"),
                    style = texts.caption,
                    color = colors.secondaryText,
                    modifier = Modifier.width(28.dp),
                )
            }
        }
    }
}

/**
 * 复习热力图（v3.0-A Task 8）：`周 × 7` 的格子，**列 = 周（旧 → 新，最右是本周）、行 = 周一..周日**。
 *
 * ## 为什么横向滚动而不是把 52 列挤进一屏
 * 52 列 × 7 行是 364 格，一屏宽只有 ~360dp，格子要缩到 6dp 才放得下——那时色阶的四级深浅
 * 在物理上就分辨不出了，图就白画。本仓的 [com.studykit.ui.components.HabitSnake]（20 周一排）
 * 早就走了同一条路：横向滚动 + 首帧滚到今天。这里连"滚到最右"那条一次性守卫都照抄。
 *
 * ## 无障碍：整块图**一句**汇总描述
 * 逐格念的话读屏要把这一年念三百多遍（364 个节点），那不是无障碍而是刑罚。
 * 汇总里带真数（周数 + 有复习的天数），画法与口径写在图下那行 caption 里——
 * 与 [LoadBandChart] 同一条分工：看得见的和听得见的各拿一半。
 *
 * ## 占位格不是"零复习"格
 * 今天之后那几格（末周残缺）**不填色，只描一圈 `heatIdle` 的空框**；零复习的格子是实心灰。
 * 空心框 vs 实心块靠形状分开，两套主题都看得见——若只把占位格调成"再淡一档的 alpha"，
 * 它和灰格的差别会掉到屏幕分辨不出的量级，而这张图的核心规则恰恰是"未来 ≠ 没坚持"。
 * 画成灰格就等于对用户说"这几天你没坚持"，而那件事还没发生。
 * 判定在 [ReviewHeatmapLogic]，这里只换画法。
 *
 * 格子来自 [ReviewHeatmapLogic.cells]，视图**不自己数时间戳**——数第二遍就会和读屏那句对不上。
 */
@Composable
internal fun ReviewHeatmapSection(cells: List<HeatCell>, modifier: Modifier = Modifier) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    if (cells.isEmpty()) {
        EmptyState(
            title = "还没有复习流水",
            caption = "背完第一个词、练完第一道题，这里才会亮起来。",
        )
        return
    }
    val columns = cells.chunked(HeatmapRowsPerWeek)
    val activeDays = cells.count { it.level > ReviewHeatmapLogic.LEVEL_NONE }
    val description = "最近 ${columns.size} 周复习热力图，共 $activeDays 天有复习"

    val scrollState = rememberScrollState()
    // 只在第一次（且已量出可滚范围时）滚到今天；之后用户滚到哪就停在哪——与 HabitSnake 同一条守卫
    var didCenter by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(scrollState.maxValue, columns.size) {
        if (!didCenter && scrollState.maxValue > 0) {
            didCenter = true
            scrollState.scrollTo(scrollState.maxValue)
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "一格一天：列是周（旧 → 新，最右是本周），行是周一到周日。" +
                "深浅是按你自己非零日的 90 分位排的，不是固定阈值——颜色深只说明那天比你的平常日子忙。" +
                "只数两路：单词复习与题目练习；书摘没有每次复习的流水表，所以这张图不等于全部学习行为，" +
                "也不会等于顶上「复习记录」那个数（那一只数词卡）。" +
                "灰格是那天一次都没复习，只有一圈空框、没有填色的格子是还没到来的日子。",
            style = texts.caption,
            color = colors.secondaryText,
        )
        Spacer(Modifier.height(AppTheme.space.sm))
        Row(
            modifier = Modifier
                .horizontalScroll(scrollState)
                .semantics { contentDescription = description },
        ) {
            columns.forEach { column ->
                Column {
                    column.forEach { cell ->
                        val shape = RoundedCornerShape(2.dp)
                        Box(
                            modifier = Modifier
                                .size(HeatmapCellSide)
                                .padding(1.dp)
                                .clip(shape)
                                .background(heatmapCellColor(colors, cell.level))
                                // 未来占位格＝空心框（见 [heatmapCellColor] 的 KDoc）：
                                // 与实心灰格靠形状分开，不靠再淡一档的 alpha
                                .then(
                                    if (cell.level == ReviewHeatmapLogic.LEVEL_FUTURE) {
                                        Modifier.border(1.dp, colors.heatIdle, shape)
                                    } else {
                                        Modifier
                                    },
                                ),
                        )
                    }
                }
            }
        }
    }
}

/**
 * 一档色 → 一个既有 token。**不新增色值**，也不许退回 `divider`：
 * `divider` 满不透明在浅色卡面上只有 1.29:1（Palette.kt 为这笔账写过"几乎看不见"），
 * 零复习格正是 [AppColors.heatIdle] 这个 token 存在的理由（两主题各约 1.5:1）。
 *
 * - 非零四档：`accent` 的 alpha（0.30 / 0.55 / 0.80 / 1.0），一条没动；
 * - 零复习：`heatIdle` 实心；
 * - 未来占位：[Color.Transparent] **不填色**，由视图（[ReviewHeatmapSection]）补一圈
 *   `heatIdle` 描边——空心框 vs 实心块是**画法**的区别，两套主题都分辨得出；
 *   上一版用 `divider` / `divider.copy(alpha = 0.30)` 约 1.29:1 / 1.09:1，
 *   "未来 ≠ 零复习"这条核心规则只活在数据里，屏幕上两个都快看不见。
 */
internal fun heatmapCellColor(colors: AppColors, level: Int): Color = when (level) {
    ReviewHeatmapLogic.LEVEL_FUTURE -> Color.Transparent
    ReviewHeatmapLogic.LEVEL_NONE -> colors.heatIdle
    1 -> colors.accent.copy(alpha = 0.30f)
    2 -> colors.accent.copy(alpha = 0.55f)
    3 -> colors.accent.copy(alpha = 0.80f)
    else -> colors.accent
}

/** 一行的格子数（周一..周日）：`cells` 按 7 个一列切回来 */
private const val HeatmapRowsPerWeek = 7

/** 一格边长（含 1dp 间隔）：再小就分辨不出四级深浅，所以宁可横向滚动 */
private val HeatmapCellSide = 13.dp

/**
 * 稳定度全景（v3.0-A Task 7）：FSRS 的稳定度 S 按 log2 分十二档，互斥。
 *
 * 行条复用 [DurabilityRow]，**不新画控件**——两节的形状本来就是一回事
 * （标签 + "N 个 · 占比" + 比例条），换一套只会让用户多学一次怎么读。
 * 无障碍口径也随它一起继承：一行一条 `contentDescription`。
 *
 * 十二档**全摆出来**，零计数的档也占一行（与 [RetentionCard] 那条"没数据的档照样在"同一条理由：
 * 缺行会让人以为那一档不存在，而不是"自己没攒到"）。分档、分母、脏值判据全在
 * [StabilityBins]，这里只排版——同一个算法不许在视图里再判一遍。
 *
 * `cardCount == 0` 走空态而不是画十二行 0%：那幅图读起来像"你的记忆全不行"，
 * 而事实是还没有一张卡被 FSRS 写过稳定度（[ForecastCards] 的"宁缺不假"）。
 */
@Composable
internal fun StabilityPanoramaSection(bins: List<StabilityBin>, cardCount: Int, modifier: Modifier = Modifier) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    if (cardCount <= 0 || bins.isEmpty()) {
        EmptyState(
            title = "还没有可信的稳定度",
            caption = "卡被 FSRS 写过稳定度之后，这里才会分出十二档。",
        )
        return
    }
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "这里的 S 是模型给每张卡算的稳定度：S 越大，同样的保留率下能隔得越久。" +
                "母体是已排期、且被 FSRS 写过 S 的那批卡（词 + 错题 + 书摘，共 $cardCount 张卡），" +
                "百分比的分母就是这个可信卡数——与上面「这些记忆能扛多久」是同一套分母口径，" +
                "但母体不同：那节数的是全库词卡的半衰期、四档是嵌套的 ≥，这里十二档互斥，" +
                "所以两边的百分比不能横向比大小。",
            style = texts.caption,
            color = colors.secondaryText,
        )
        Spacer(Modifier.height(AppTheme.space.sm))
        bins.forEach { bin ->
            DurabilityRow(
                label = bin.label,
                count = bin.count,
                share = bin.shareLabel,
                fraction = bin.sharePercent / 100f,
            )
        }
    }
}

/** 一行持久度：标签 + "N 个 · 占比" + 比例条。占比文字取自 [DurabilityBucket.shareLabel]，不用裸百分数 */
@Composable
private fun DurabilityRow(label: String, count: Int, share: String, fraction: Float) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = AppTheme.space.sm)
            .semantics { contentDescription = "$label：$count 个，占 $share" },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = label, style = texts.body, modifier = Modifier.weight(1f))
            Text(
                text = "$count 个 · $share",
                style = texts.caption,
                color = colors.secondaryText,
            )
        }
        Spacer(Modifier.height(AppTheme.space.xs))
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp),
            color = colors.accent,
            trackColor = colors.divider,
            strokeCap = StrokeCap.Round,
        )
    }
}

/**
 * 遗忘曲线。横轴线性取 0~9 天 —— 与墨墨那张图一致。
 *
 * 艾宾浩斯前两个小时那几个点会挤在最左边，这正是它"先陡后平"的形状，
 * 不要为了好看改成对数轴：换了横轴口径，两条线就没法比了。
 */
@Composable
private fun RetentionChart(
    ebbinghaus: List<Pair<Double, Double>>,
    modelCurve: List<Double>,
    curve: List<CurvePoint>,
    showModel: Boolean,
) {
    val colors = AppTheme.colors
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp)
            .semantics { contentDescription = "遗忘曲线：纵轴是记住的百分比，横轴是天数" },
    ) {
        val w = size.width
        val h = size.height
        val maxDay = 9.0
        listOf(0f, 0.25f, 0.5f, 0.75f, 1f).forEach { r ->
            val y = h * (1f - r)
            drawLine(
                color = colors.divider,
                start = Offset(0f, y),
                end = Offset(w, y),
                strokeWidth = 1.dp.toPx(),
            )
        }
        fun point(day: Double, retention: Double): Offset = Offset(
            x = w * (day / maxDay).toFloat().coerceIn(0f, 1f),
            y = h * (1f - retention.toFloat()),
        )
        if (showModel && modelCurve.isNotEmpty()) {
            // DrawScope 的 drawLine **只有** (brush|color, start, end, ...) 两个重载，
            // 没有收 Path 的版本 —— 折线要走 drawPath + style = Stroke。
            drawPath(
                path = Path().apply {
                    modelCurve.forEachIndexed { i, r ->
                        val p = point(i.toDouble(), r)
                        if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
                    }
                },
                color = colors.accent,
                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
            )
        }
        drawPath(
            path = Path().apply {
                ebbinghaus.forEachIndexed { i, (day, retention) ->
                    val p = point(day, retention)
                    if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
                }
            },
            color = colors.warning,
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
        )
        curve.forEach { pt ->
            val observed = pt.observedRecall ?: return@forEach
            drawCircle(
                color = colors.success,
                radius = 4.dp.toPx(),
                center = point(pt.midGapDays.coerceAtMost(maxDay), observed),
            )
        }
    }
}

@Composable
private fun ChartLegend(showModel: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(AppTheme.space.md)) {
        if (showModel) LegendDot(color = AppTheme.colors.accent, label = "模型预测")
        LegendDot(color = AppTheme.colors.warning, label = "艾宾浩斯")
        LegendDot(color = AppTheme.colors.success, label = "你的实测")
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    val texts = AppTheme.texts
    Row(verticalAlignment = Alignment.CenterVertically) {
        Spacer(
            modifier = Modifier
                .width(10.dp)
                .height(10.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(color),
        )
        Spacer(Modifier.width(AppTheme.space.xs))
        // 文字用同族 Ink 而不是填充色本身：warning 直接当文字色在浅色主题下只有 1.6:1
        Text(text = label, style = texts.caption, color = AppTheme.colors.secondaryText)
    }
}
