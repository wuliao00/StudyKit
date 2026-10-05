package com.studykit.data.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

/**
 * Task 1（v3.0-A）负荷带纯函数的单测：纯 JVM，无 runner，不碰 Room。
 *
 * 口径提醒：反解出的间隔带 ULP 级噪声（S=2/R=0.9 精确值略小于 2.0），
 * 断言里取整一律用 `roundToInt`，与 [DueForecast.forecast] 同一口径。
 */
class DueForecastTest {

    private val cfg14 = ForecastCfg(horizonDays = 14, retention = 0.9, newPerDay = 0, learningPerNew = 0.0)

    @Test
    fun `一张 S 与到期时刻一致的卡落在反解出的那一天`() {
        // S=2 天、R=0.9 → interval ≈ 2 天（同式反解，不手算硬编码）
        val day = DueForecast.intervalDays(FsrsModel.DEFAULT_PARAMS, 2.0, 0.9).roundToInt()
        val bands = DueForecast.forecast(
            listOf(ForecastCard(2.0, 0L, elapsedDays = 0.0)),
            cfg14,
        )
        assertEquals(1.0, bands[day].mean, 1e-9)
        // 到期本事件只落在反解出的那一天；除返工尾巴（+1..+3，另测）外全零。
        assertEquals(
            0.0,
            bands.filterIndexed { i, _ -> i < day || i > day + 3 }.sumOf { it.mean },
            1e-9,
        )
    }

    @Test
    fun `elapsed 会把到期日往前拉`() {
        val full = DueForecast.forecast(listOf(card2(0.0)), cfg14)
        val half = DueForecast.forecast(listOf(card2(1.0)), cfg14)
        assertTrue(peakDay(full) > peakDay(half))
    }

    @Test
    fun `跌破目标保留率的返工质量摊到之后三天`() {
        val bands = DueForecast.forecast(listOf(card2(0.0)), cfg14)
        val d = peakDay(bands)
        // 到期本事件 1.0 只落在 d；返工（概率 1-R）按 inflowW 摊到 d+1..d+3
        assertEquals(0.10 * 0.5, bands[d + 1].mean, 1e-9)
        assertEquals(0.10 * 0.3, bands[d + 2].mean, 1e-9)
        assertEquals(0.10 * 0.2, bands[d + 3].mean, 1e-9)
    }

    @Test
    fun `方差来自伯努利项 sd 是方差的平方根`() {
        val bands = DueForecast.forecast(listOf(card2(0.0)), cfg14)
        val d = peakDay(bands)
        // 到期那一格是确定事件（p=1）→ sd 0；返工格 p=0.1 → sd = sqrt(p(1-p)) · w
        assertEquals(0.0, bands[d].sd, 1e-9)
        assertEquals(Math.sqrt(0.10 * 0.90) * 0.5, bands[d + 1].sd, 1e-9)
    }

    @Test
    fun `出窗的卡不堆到最后一格`() {
        val far = ForecastCard(stabilityDays = 5000.0, dueAt = 0L, elapsedDays = 0.0)
        val bands = DueForecast.forecast(listOf(far), cfg14)
        assertEquals(0.0, bands.sumOf { it.mean }, 1e-9)
    }

    @Test
    fun `脏稳定度被丢掉而不是炸或产出 NaN`() {
        val dirty = listOf(
            ForecastCard(Double.NaN, 0L, 0.0),
            ForecastCard(0.0, 0L, 0.0),
            ForecastCard(-3.0, 0L, 0.0),
        )
        val bands = DueForecast.forecast(dirty, cfg14)
        assertTrue(bands.all { it.mean.isFinite() && it.mean == 0.0 })
    }

    @Test
    fun `新词流入按窗口守恒`() {
        val c = cfg14.copy(newPerDay = 10, learningPerNew = 2.5)
        val bands = DueForecast.forecast(emptyList(), c)
        // 14 天 × 10 张 × 2.5 学习步 = 350 的总流入，按引入日 +0..+2 摊派；
        // 守恒指"不重复计"，落窗外的尾部不强计（与"出窗的卡不堆最后一格"同一口径），
        // 故窗内和 = 每个引入日落在窗内的权重之和 = 12×25 + 20 + 12.5 = 332.5。
        val expected = (0 until 14).sumOf { intro ->
            c.inflowW.indices.filter { intro + it < 14 }.sumOf { c.inflowW[it] } * 10 * 2.5
        }
        assertEquals(332.5, bands.sumOf { it.mean }, 1e-6)
        assertEquals(expected, bands.sumOf { it.mean }, 1e-6)
    }

    @Test
    fun `空库产出全零且长度恰为 horizon`() {
        val bands = DueForecast.forecast(emptyList(), cfg14)
        assertEquals(14, bands.size)
        assertEquals(0.0, bands.sumOf { it.mean }, 1e-9)
    }

    @Test
    fun `horizon 小于摊派跨度时不越界`() {
        val bands = DueForecast.forecast(listOf(card2(0.0)), cfg14.copy(horizonDays = 1))
        assertEquals(1, bands.size)
    }

    private fun card2(elapsedDays: Double) = ForecastCard(2.0, 0L, elapsedDays)
    private fun peakDay(bands: List<DayBand>): Int = bands.indices.maxByOrNull { bands[it].mean } ?: 0
}
