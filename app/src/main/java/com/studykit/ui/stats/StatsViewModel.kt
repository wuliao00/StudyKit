package com.studykit.ui.stats

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.studykit.StudyKitApp
import com.studykit.data.dao.ReviewGapRow
import com.studykit.data.memory.DayBand
import com.studykit.data.memory.DueForecast
import com.studykit.data.memory.ForecastCfg
import com.studykit.data.memory.FsrsModel
import com.studykit.data.memory.MemoryScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 看板一屏要画的全部数字。算法都在 [MemoryHealth]（分桶/曲线/按日聚合）、
 * [ForecastCards] + [DueForecast]（负荷带）与 [RetentionBuckets]（延迟后测）里，这里只做组合。
 */
data class StatsUiState(
    val durability: List<DurabilityBucket> = emptyList(),
    /** 有可信半衰期的词数。**不等于**墨墨的"已加入记忆规划" —— 我们没有那个开关 */
    val plannedCount: Int = 0,
    val totalWords: Int = 0,
    val forecast: List<DayLoad> = emptyList(),
    val curve: List<CurvePoint> = emptyList(),
    val modelCurve: List<Double> = emptyList(),
    val sampleCount: Int = 0,
    /**
     * 「延迟后测」卡（v2.7 spec §9 / D7）：7d / 30d / other 三档加一个 unknown 旧档。
     *
     * 空桶根本不在列表里（判定全在 [RetentionBuckets]），所以"这个桶没数据"由
     * **缺少那个 label** 表达，而不是由一个 0% 冒充。
     */
    val retention: List<RetentionBucket> = emptyList(),
    /**
     * 未来两周的复习负荷带（v3.0-A Task 6）：逐日期望项数与 ±1 标准差，窗口取
     * [ForecastCfg.HORIZON_DAYS]（与提醒侧同一个数）。
     *
     * 与 [forecast] 是**两套口径**、两张图各答一问，别当成重复：`forecast` 只数已落库的到期时间戳
     * （"接下来 7 天原本排了多少"），`loadBand` 由稳定度反解到期日（"照这个练法会堆成什么样"），
     * 所以今天不练，只有后者会如实把明天的量顶上去。口径见 [DueForecast] 的类 KDoc。
     *
     * **mean 里除了到期复习还有学习步**（`newPerDay × learningPerNew`，见 [ForecastCfg]）：它就是会比
     * 词数大。上一版为此在这里递了一个 `capacityPerDay = settings.dailyWordGoal` 给图当对照线，
     * 而 `dailyWordGoal` 是每日**新词**目标、不是复习容量，那个比较天天成立、提示常亮，已删（T6 修复轮）。
     */
    val loadBand: List<DayBand> = emptyList(),
    /**
     * 稳定度全景的分箱结果（v3.0-A Task 7）：母体是 [stabilityCardCount] 那张卡集，
     * 与 [loadBand] **同一批卡**（同一次 [ForecastCards.of] 的结果，见那条 KDoc 的求值一次契约）。
     *
     * 与 [durability] 的区别要如实说：那节数的是全库词卡的 `half_life_days`、四档嵌套 ≥，
     * 这节数的是 FSRS 的稳定度 S、十二档互斥；两节的百分比**分母口径相同**（各自信任的那批卡），
     * 母体不同，所以不能横向比大小。这句话由视图的 caption 说给用户，不在这里默认。
     */
    val stability: List<StabilityBin> = emptyList(),
    /** 进了上面那十二档的分母的可信卡数（= 同一次装配的卡集大小），0 时视图走空态 */
    val stabilityCardCount: Int = 0,
)

/**
 * [StatsViewModel.loadVizFlow] 一次装配的两个产物：负荷带 + 稳定度分箱。
 *
 * 之所以打包成一个类型而不是两条 Flow：两条就要各调一次 [ForecastCards.of]，而那是
 * **求值一次契约**禁止的（[ForecastCards.of] 的 KDoc：多调一次不是"多算一遍"而是"多取一次快照"，
 * 中间只要有一次评分落库，两张图数的就不是同一批卡，而且不报错）。
 */
private data class LoadViz(
    val bands: List<DayBand>,
    val bins: List<StabilityBin>,
    val usableCards: Int,
)

/**
 * 记忆看板 ViewModel。
 *
 * `combine` 的变换（全库分桶、按日聚合）放 Default 线程：一次评分就会让这几个 Flow 全部重算，
 * 落在 Main 上就是主线程数一千多个数 —— 这类账在 v2.1 的首页上踩过一次。
 */
class StatsViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as StudyKitApp).container
    private val wordRepository = container.wordRepository
    private val mistakeRepository = container.mistakeRepository
    private val bookRepository = container.bookRepository
    private val settingsRepository = container.settingsRepository

    companion object {
        /** 未来量看 7 天：够排一周，再多就只是把"下周"也压进焦虑里 */
        private const val FORECAST_DAYS = 7

        /** 模型曲线取第 0~9 天，与墨墨那张图的横轴一致 */
        private val CURVE_DAYS = (0..9).toList()
    }

    /**
     * 负荷带 + 稳定度全景那一路：设置 + 三张表的已排期行 → **一次**装配 → 两个产物（[LoadViz]）。
     *
     * ## 为什么单列一条 combine，而不是塞进下面那条五路的 `combine`
     * kotlinx 的类型化 `combine` 只到 5 个 Flow（再多就只剩返回 `Array<Any?>` 的那个重载，要手写强转）。
     * 下面那条已经用满 5 路，所以这里分两条、最外层再 `copy` 合起来——顺带让这一路
     * 自己 `.flowOn(Dispatchers.Default)`：装配是全库逐卡映射，反解是全库逐卡一次除法，
     * 一次评分就会重跑一遍，绝不能落到 Main 上（理由与类 KDoc 同一条）。
     *
     * ## `ForecastCards.of` 在这一个 lambda 里只调一次（它的 KDoc 是求值一次契约）
     * 四个上游在同一次 combine 里各取到一份快照，`of` 调一次，结果**同时**喂
     * [DueForecast.forecast] 与 [StabilityBins.bin]，于是带子的峰值与十二档数的是同一批卡。
     * 多加一次调用不是"多算一遍"而是"多取一次快照"：两次之间只要有一次评分落库，
     * 两张图就是半新半旧的卡集——而且不报错。这也是 [LoadViz] 打包两个产物而不是拆两条 Flow 的原因。
     *
     * `stabilityDays` 已在 [ForecastCards.of] 那侧滤过脏值（null / <=0 / 非有限整条丢弃），
     * [StabilityBins.bin] 的同一道判据在这里是**幂等的安全网**，不是第二套口径。
     *
     * ## 两个数只有一个来源
     * 目标保留率走 [MemoryScheduler.forSettings] 的 `targetRecall`（写死 0.9 等于在看板另起一份
     * 排期政策，与提醒侧同一条纪律），FSRS 参数走 `settings.fsrsActiveParams`，没校准过时才取发布默认。
     * `settings.dailyWordGoal` 在这里只进 [ForecastCfg.newPerDay] 一个地方：它说的是"每天进几个新词"，
     * 带子因此含这些新词产生的学习步。**不许**把它当每日复习容量再递一份给 UI 画对照线——
     * 那个比较恒真、超载提示常亮，上一轮就是这么写的，已删（见 [LoadBandChart] 类 KDoc）。
     * 时钟只读一次：`today` 由 `now` 推，免得跨过零点那一次重算里「今天」和「now」不是同一天。
     */
    private val loadVizFlow = combine(
        settingsRepository.settings,
        wordRepository.observeScheduledMemoryRows(),
        mistakeRepository.observeUnmastered(),
        bookRepository.observeScheduledExcerpts(),
    ) { settings, words, mistakes, excerpts ->
        val now = System.currentTimeMillis()
        // 不用 LocalDate.ofInstant(...)——那是 Java 9 的静态方法，API 26 的 java.time 里没有
        val today = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()
        val sched = MemoryScheduler.forSettings(
            strictness = settings.reviewStrictness,
            examEpochDay = settings.examEpochDay,
            today = today,
        )
        val cards = ForecastCards.of(
            words = words,
            mistakes = mistakes,
            excerpts = excerpts,
            now = now,
        )
        LoadViz(
            bands = DueForecast.forecast(
                cards.toForecastCards(),
                ForecastCfg(
                    // 与提醒侧同一个常量：两处各写一份 14 时，通知和看板就不是在同一段未来里读数
                    horizonDays = ForecastCfg.HORIZON_DAYS,
                    retention = sched.targetRecall,
                    newPerDay = settings.dailyWordGoal,
                    // learningPerNew 取 ForecastCfg 声明的默认值：这里重述一遍就多了个可能分叉的地方
                ),
                w = settings.fsrsActiveParams ?: FsrsModel.DEFAULT_PARAMS,
            ),
            bins = StabilityBins.bin(cards.map { it.stabilityDays }),
            usableCards = cards.size,
        )
    }.flowOn(Dispatchers.Default)

    /**
     * 看板其余几路：全库半衰期 / 已排期时间戳 / 复习流水 / 词数 / 延迟后测原料。
     *
     * 各消费方共用的字段口径不在这里判（`plannedCount` 与 `loadBand` 是不是同一批卡，
     * 由 [ForecastCards] 那一层负责），这一层只负责把五路凑齐并交给纯函数。
     */
    private val baseState = combine(
        wordRepository.observeHalfLifeDays(),
        wordRepository.observeScheduledTimestamps(),
        wordRepository.observeReviewGapAndResult(),
        wordRepository.observeCount(),
        wordRepository.observeRetentionRows(),
    ) { halfLives, scheduled, reviews, total, retentionRows ->
        // 旧记录没有 gap（v2.3 之前只存时间戳），mapNotNull 丢掉它们而不是补 0
        val samples = reviews.mapNotNull { row: ReviewGapRow ->
            row.gapDays?.let { GapSample(gapDays = it, recalled = row.correct) }
        }
        StatsUiState(
            durability = MemoryHealth.durability(halfLives),
            plannedCount = halfLives.count { it.isFinite() && it > 0.0 },
            totalWords = total,
            forecast = MemoryHealth.forecastByDay(
                timestamps = scheduled,
                zone = ZoneId.systemDefault(),
                from = LocalDate.now(),
                days = FORECAST_DAYS,
            ),
            curve = MemoryHealth.forgettingCurve(samples),
            modelCurve = MemoryHealth.modelCurve(halfLives, CURVE_DAYS),
            sampleCount = samples.size,
            // 分桶与达标口径全在 RetentionBuckets，这里只递原料（一张卡不许自带一套算法）
            retention = RetentionBuckets.of(retentionRows),
        )
    }.flowOn(Dispatchers.Default)

    val state: StateFlow<StatsUiState> = combine(baseState, loadVizFlow) { base, viz ->
        base.copy(
            loadBand = viz.bands,
            stability = viz.bins,
            stabilityCardCount = viz.usableCards,
        )
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatsUiState())
}
