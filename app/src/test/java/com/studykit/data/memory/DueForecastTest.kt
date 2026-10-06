package com.studykit.data.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 卡（S=2 天、elapsed=0、R=0.9）的到期格子——**手算**得到的字面值，不是调用被测函数得来的。
 * FSRS v6 反解式 `t = S/f·(R^(1/decay) − 1)`，而 `f ≡ 0.9^(1/decay) − 1`；本次 R 恰为 0.9，
 * 于是分子分母同一个数，`t = S = 2` 天 → 第 2 格。
 */
private const val ARRIVAL_DAY = 2

/**
 * Task 1（v3.0-A）负荷带纯函数的单测：纯 JVM，无 runner，不碰 Room。
 *
 * 口径提醒：反解出的间隔带 ULP 级噪声（S=2/R=0.9 的精确值是 `1.9999999999999998`），
 * [DueForecast.forecast] 内部用 `roundToInt` 落格，所以到期落在第 2 天。
 * 断言里的期望格子是**手算字面值**（见 [ARRIVAL_DAY]），不调 [DueForecast.intervalDays]——
 * 用被测函数给自己出题，那条断言就永远不会红。
 */
class DueForecastTest {

    private val cfg14 = ForecastCfg(
        horizonDays = 14,
        retention = 0.9,
        // 无考试日的默认口径（`MemoryModel.maxIntervalDaysFor(180)`）；上限本身怎么改窗口另测
        maxIntervalDays = 36.0,
        newPerDay = 0,
        learningPerNew = 0.0,
    )

    // ── 间隔上限：政策上限必须是预测的输入，而不是被 3650 天的反解上限顶掉 ──────
    //
    // 调度器排每张卡时套的是 `Scheduling.maxIntervalDays`（考前 20 天只有 `max(3, 20×0.2)=4` 天），
    // 预测侧漏了它，凡自然间隔超过上限的卡都会被带子放到**更晚**、甚至整张甩出 14 天窗，
    // 而 App 其实把它们排到了更早——考前那两周于是系统性少报。下面三条钉的就是这件事。

    /** S=30 的卡：默认上限（36 天）下第 30 天到期、出窗即弃；紧上限（4 天）下第 4 天就回来。 */
    @Test
    fun `紧上限把出窗的卡拉回窗口里`() {
        val card = ForecastCard(stabilityDays = 30.0, elapsedDays = 0.0)
        // 夹具自检：宽上限下这张卡确实不在窗内（否则下面那条断言测的是窗口而不是上限）
        assertEquals(0.0, DueForecast.forecast(listOf(card), cfg14).sumOf { it.mean }, 1e-9)

        val exam20 = cfg14.copy(maxIntervalDays = 4.0)
        val bands = DueForecast.forecast(listOf(card), exam20)
        // 手算：intervalDays(w,30,0.9,4) 被上限夹到 4 → 到期落第 4 格（不是第 30 格、也不是窗尾），
        // 带子的和 = 到期本事件 1.0 + 返工尾巴 0.05+0.03+0.02
        assertEquals(4, bands.indexOfFirst { it.mean >= 1.0 - 1e-9 })
        assertEquals(1.0, bands[4].mean, 1e-9)
        assertEquals(1.10, bands.sumOf { it.mean }, 1e-9)
    }

    /** 考试越近、上限越紧，窗内数到的卡越多——负荷带随政策单调变忙，不是反着来。 */
    @Test
    fun `上限越紧窗口越忙`() {
        // 三张卡的自然间隔是 8 / 15 / 30 天（R=0.9 时反解恒等于 S），14 天窗只装得下第一张
        val cards = listOf(ForecastCard(8.0, 0.0), ForecastCard(15.0, 0.0), ForecastCard(30.0, 0.0))
        val sums = listOf(36.0, 12.0, 4.0).map { cap ->
            DueForecast.forecast(cards, cfg14.copy(maxIntervalDays = cap)).sumOf { it.mean }
        }
        // 手算（每张到期卡另带 0.1 的返工尾巴，落不进窗的那几步按 D3 弃掉）：
        //   cap=36 → 只有第 8 天那张进窗                     → 1.0 + 0.1 = 1.10
        //   cap=12 → 15 与 30 都被夹到第 12 天，三张全进窗（后两张的尾巴只剩 +1 那步）→ 3.20
        //   cap=4  → 三张全夹到第 4 天，尾巴三步都在窗内                        → 3.30
        assertEquals(1.10, sums[0], 1e-9)
        assertEquals(3.20, sums[1], 1e-9)
        assertEquals(3.30, sums[2], 1e-9)
        assertTrue("上限收紧时带子必须更忙，实测 $sums", sums[0] < sums[1] && sums[1] < sums[2])
    }

    /** 上限紧到远小于窗口时，卡落在被夹的那一天，而不是堆在窗尾（与 D3「出窗即弃」同一口径）。 */
    @Test
    fun `紧上限不靠窗尾堆峰来表达`() {
        val bands = DueForecast.forecast(
            listOf(ForecastCard(400.0, 0.0), ForecastCard(900.0, 0.0)),
            cfg14.copy(maxIntervalDays = 4.0),
        )
        // 两张都被夹到第 4 天：峰在第 4 格，窗尾（第 13 格）仍是零
        assertEquals(2.0, bands[4].mean, 1e-9)
        assertEquals(0.0, bands[13].mean, 1e-9)
    }

    @Test
    fun `一张 S 与到期时刻一致的卡落在反解出的那一天`() {
        val bands = DueForecast.forecast(
            listOf(ForecastCard(2.0, elapsedDays = 0.0)),
            cfg14,
        )
        // 到期本事件的 1.0 只落在手算的到期日……
        assertEquals(1.0, bands[ARRIVAL_DAY].mean, 1e-9)
        // ……除返工尾巴（+1..+3，另测）外全零。
        assertEquals(
            0.0,
            bands.filterIndexed { i, _ -> i < ARRIVAL_DAY || i > ARRIVAL_DAY + 3 }.sumOf { it.mean },
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
        val far = ForecastCard(stabilityDays = 5000.0, elapsedDays = 0.0)
        val bands = DueForecast.forecast(listOf(far), cfg14)
        assertEquals(0.0, bands.sumOf { it.mean }, 1e-9)
    }

    @Test
    fun `脏稳定度被丢掉而不是炸或产出 NaN`() {
        val dirty = listOf(
            ForecastCard(Double.NaN, 0.0),
            ForecastCard(0.0, 0.0),
            ForecastCard(-3.0, 0.0),
        )
        val bands = DueForecast.forecast(dirty, cfg14)
        assertTrue(bands.all { it.mean.isFinite() && it.mean == 0.0 })
    }

    @Test
    fun `新词流入按窗口守恒`() {
        val c = cfg14.copy(newPerDay = 10, learningPerNew = 2.5)
        val bands = DueForecast.forecast(emptyList(), c)
        // 332.5 是**手算**字面值（每引入日流入 10×2.5×(0.5+0.3+0.2)=25，摊到引入日 +0..+2）：
        //   intro 0..11  三步全在窗内 → 12 × 25            = 300
        //   intro 12     +2 步落到第 14 天（窗外）→ 25×0.8 =  20
        //   intro 13     只剩 +0 步            → 25×0.5    =  12.5
        //   窗内合计                                        332.5
        // 不按实现的那句 `intro + k < horizon` 重算期望值——那样共享的 off-by-one 会一起漂。
        // 守恒指"不重复计"：窗外尾部不强计（与「出窗的卡不堆到最后一格」同一口径），故小于名义 350。
        assertEquals(332.5, bands.sumOf { it.mean }, 1e-6)
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

    private fun card2(elapsedDays: Double) = ForecastCard(2.0, elapsedDays)
    private fun peakDay(bands: List<DayBand>): Int = bands.indices.maxByOrNull { bands[it].mean } ?: 0
}
