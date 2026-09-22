package com.studykit.ui.stats

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.studykit.StudyKitApp
import com.studykit.data.dao.ReviewGapRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.time.ZoneId

/** 看板一屏要画的全部数字。算法都在 [MemoryHealth] 里，这里只做组合 */
data class StatsUiState(
    val durability: List<DurabilityBucket> = emptyList(),
    /** 有可信半衰期的词数。**不等于**墨墨的"已加入记忆规划" —— 我们没有那个开关 */
    val plannedCount: Int = 0,
    val totalWords: Int = 0,
    val forecast: List<DayLoad> = emptyList(),
    val curve: List<CurvePoint> = emptyList(),
    val modelCurve: List<Double> = emptyList(),
    val sampleCount: Int = 0,
)

/**
 * 记忆看板 ViewModel。
 *
 * `combine` 的变换（全库分桶、按日聚合）放 Default 线程：一次评分就会让这四个 Flow 全部重算，
 * 落在 Main 上就是主线程数一千多个数 —— 这类账在 v2.1 的首页上踩过一次。
 */
class StatsViewModel(application: Application) : AndroidViewModel(application) {

    private val wordRepository = (application as StudyKitApp).container.wordRepository

    companion object {
        /** 未来量看 7 天：够排一周，再多就只是把"下周"也压进焦虑里 */
        private const val FORECAST_DAYS = 7

        /** 模型曲线取第 0~9 天，与墨墨那张图的横轴一致 */
        private val CURVE_DAYS = (0..9).toList()
    }

    val state: StateFlow<StatsUiState> = combine(
        wordRepository.observeHalfLifeDays(),
        wordRepository.observeScheduledTimestamps(),
        wordRepository.observeReviewGapAndResult(),
        wordRepository.observeCount(),
    ) { halfLives, scheduled, reviews, total ->
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
        )
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatsUiState())
}
