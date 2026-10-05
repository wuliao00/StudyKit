package com.studykit.ui.stats

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.studykit.StudyKitApp
import com.studykit.data.AppSettings
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
     * 未来两周的复习负荷带（v3.0-A Task 6）：逐日期望项数与 ±1 标准差。
     *
     * 与 [forecast] 是**两套口径**、两张图各答一问，别当成重复：`forecast` 只数已落库的到期时间戳
     * （"接下来 7 天原本排了多少"），`loadBand` 由稳定度反解到期日（"照这个练法会堆成什么样"），
     * 所以今天不练，只有后者会如实把明天的量顶上去。口径见 [DueForecast] 的类 KDoc。
     */
    val loadBand: List<DayBand> = emptyList(),
    /** 负荷带的对照线：每日容量，取 `AppSettings.dailyWordGoal`（与 [ForecastCfg.newPerDay] 同一个数） */
    val capacityPerDay: Int = AppSettings.DEFAULT_WORD_GOAL,
)

/**
 * [StatsViewModel.loadBandFlow] 的中间产物：带子 + 画它所需要的那条容量线。
 *
 * 单独一个类型是因为这两个数**必须同源**：`capacityPerDay` 取的是 `settings.dailyWordGoal`，
 * 而带子里的新词流入项数同样由它推出（[ForecastCfg.newPerDay]）。分两条 Flow 各取一次设置，
 * 就可能出现"线是旧的、带是新的"——线画低了一格，超载日凭空多几天。
 */
private data class LoadBandState(val bands: List<DayBand>, val capacityPerDay: Int)

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

        /**
         * 负荷带看 14 天：与提醒侧 `ReminderWorker.FORECAST_HORIZON_DAYS` 同值。
         * 用户从通知上读到的"后天会堆到 N 项"和看板上那根点，必须是同一个窗口的同一次读数。
         */
        private const val LOAD_BAND_DAYS = 14

        /** 模型曲线取第 0~9 天，与墨墨那张图的横轴一致 */
        private val CURVE_DAYS = (0..9).toList()
    }

    /**
     * 负荷带那一路：设置 + 三张表的已排期行 → 一次装配 → 14 天带。
     *
     * ## 为什么单列一条 combine，而不是塞进下面那条五路的 `combine`
     * kotlinx 的类型化 `combine` 只到 5 个 Flow（再多就只剩返回 `Array<Any?>` 的那个重载，要手写强转）。
     * 下面那条已经用满 5 路，所以这里分两条、最外层再 `copy` 合起来——顺带让负荷带这一路
     * 自己 `.flowOn(Dispatchers.Default)`：装配是全库逐卡映射，反解是全库逐卡一次除法，
     * 一次评分就会重跑一遍，绝不能落到 Main 上（理由与类 KDoc 同一条）。
     *
     * ## `ForecastCards.of` 在这一个 lambda 里只调一次（它的 KDoc 是求值一次契约）
     * 四个上游在同一次 combine 里各取到一份快照，`of` 调一次、结果同时喂给带子与容量线，
     * 于是图上的实线、超载点与那几行标注数的必然是同一批卡。多加一次调用不是"多算一遍"
     * 而是"多取一次快照"，两次之间只要有一次评分落库，图上就是半新半旧的卡集——而且不报错。
     *
     * ## 两个数只有一个来源
     * 目标保留率走 [MemoryScheduler.forSettings] 的 `targetRecall`（写死 0.9 等于在看板另起一份
     * 排期政策，与提醒侧同一条纪律），FSRS 参数走 `settings.fsrsActiveParams`，没校准过时才取发布默认。
     * 时钟只读一次：`today` 由 `now` 推，免得跨过零点那一次重算里「今天」和「now」不是同一天。
     */
    private val loadBandFlow = combine(
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
        val bands = DueForecast.forecast(
            ForecastCards.of(
                words = words,
                mistakes = mistakes,
                excerpts = excerpts,
                now = now,
            ).toForecastCards(),
            ForecastCfg(
                horizonDays = LOAD_BAND_DAYS,
                retention = sched.targetRecall,
                newPerDay = settings.dailyWordGoal,
                // learningPerNew 取 ForecastCfg 声明的默认值：这里重述一遍就多了个可能分叉的地方
            ),
            w = settings.fsrsActiveParams ?: FsrsModel.DEFAULT_PARAMS,
        )
        LoadBandState(bands = bands, capacityPerDay = settings.dailyWordGoal)
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

    val state: StateFlow<StatsUiState> = combine(baseState, loadBandFlow) { base, band ->
        base.copy(loadBand = band.bands, capacityPerDay = band.capacityPerDay)
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatsUiState())
}
