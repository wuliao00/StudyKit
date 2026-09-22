package com.studykit.ui.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.math.pow

/**
 * 记忆看板算法层的黄金样例。
 *
 * 这三张图一旦算错，用户读到的是"我的记忆变差了"这种情绪结论，而不是一个能当场发现的报错，
 * 所以每个数字都要能在没有真机、没有 CI 装机的情况下被钉住。
 */
class MemoryHealthTest {

    private val utc: ZoneId = ZoneOffset.UTC

    private fun stamp(y: Int, m: Int, d: Int, hour: Int = 12): Long =
        LocalDate.of(y, m, d).atTime(hour, 0).toInstant(utc).toEpochMilli()

    // ── 持久度分布 ────────────────────────────────────────────────

    @Test
    fun `durability counts words at or above each threshold`() {
        val hs = listOf(0.5, 3.0, 12.0, 31.0, 70.0, 100.0)
        val out = MemoryHealth.durability(hs)
        assertEquals(listOf(10, 30, 60, 90), out.map { it.minHalfLifeDays })
        assertEquals(listOf(4, 3, 2, 1), out.map { it.count })
        // 分母是"全部可信的词"，不是各桶之和 —— 四档是嵌套的 ≥ 关系
        assertEquals(67, out[0].sharePercent)
        assertEquals(50, out[1].sharePercent)
        assertEquals(33, out[2].sharePercent)
        assertEquals(17, out[3].sharePercent)
    }

    @Test
    fun `durability of an empty library is all zeros and never divides by zero`() {
        val out = MemoryHealth.durability(emptyList())
        assertTrue(out.all { it.count == 0 && it.sharePercent == 0 })
    }

    /** 脏半衰期（NaN / 负数 / 0）不能进分母，否则百分比整体被稀释 */
    @Test
    fun `illegal half-lives are excluded from the denominator`() {
        val out = MemoryHealth.durability(listOf(Double.NaN, -1.0, 0.0, 50.0))
        assertEquals(1, out[0].count)
        assertEquals(100, out[0].sharePercent)
    }

    // ── 未来复习量 ────────────────────────────────────────────────

    @Test
    fun `forecast buckets timestamps by local day including today`() {
        val stamps = listOf(
            stamp(2026, 9, 22),                        // 今天 1 个
            stamp(2026, 9, 23), stamp(2026, 9, 23),    // 明天 2 个
            stamp(2026, 9, 25),                        // 第 4 天 1 个
            stamp(2026, 9, 21),                        // 昨天：不计入"未来"
            stamp(2026, 10, 30),                       // 超出 7 天窗口
        )
        val out = MemoryHealth.forecastByDay(stamps, utc, LocalDate.of(2026, 9, 22), days = 7)
        assertEquals(7, out.size)
        assertEquals(LocalDate.of(2026, 9, 22), out[0].date)
        assertEquals(listOf(1, 2, 0, 1, 0, 0, 0), out.map { it.count })
    }

    /** 跨零点的边界：23:59 归今天，次日 00:00 归明天 */
    @Test
    fun `day boundary is inclusive at the start and exclusive at the end`() {
        val lateNight = LocalDate.of(2026, 9, 22).atTime(23, 59).toInstant(utc).toEpochMilli()
        val nextMidnight = LocalDate.of(2026, 9, 23).atStartOfDay(utc).toInstant().toEpochMilli()
        val out = MemoryHealth.forecastByDay(
            timestamps = listOf(lateNight, nextMidnight),
            zone = utc,
            from = LocalDate.of(2026, 9, 22),
            days = 2,
        )
        assertEquals(listOf(1, 1), out.map { it.count })
    }

    @Test
    fun `forecast of nothing scheduled is all zeros`() {
        val out = MemoryHealth.forecastByDay(emptyList(), utc, LocalDate.of(2026, 9, 22), days = 2)
        assertEquals(listOf(0, 0), out.map { it.count })
    }

    // ── 实测遗忘曲线 ──────────────────────────────────────────────

    @Test
    fun `curve reports observed recall per gap bucket`() {
        val samples = listOf(
            GapSample(0.5, true), GapSample(0.8, true),   // 「1 天内」2/2
            GapSample(1.5, true), GapSample(2.5, false),  // 「1-3 天」1/2
            GapSample(5.0, false),                        // 「3-7 天」0/1
            GapSample(20.0, true),                        // 「7-30 天」1/1
        )
        val byLabel = MemoryHealth.forgettingCurve(samples).associateBy { it.label }
        assertEquals(1.0, byLabel["1 天内"]!!.observedRecall!!, 1e-9)
        assertEquals(0.5, byLabel["1-3 天"]!!.observedRecall!!, 1e-9)
        assertEquals(0.0, byLabel["3-7 天"]!!.observedRecall!!, 1e-9)
        assertEquals(1.0, byLabel["7-30 天"]!!.observedRecall!!, 1e-9)
        assertEquals(2, byLabel["1 天内"]!!.sampleCount)
        assertEquals(1, byLabel["30 天以上"]!!.sampleCount)
    }

    /** 空桶必须是 null，不是 0%。画成 0% 等于对用户撒谎说"你全忘了" */
    @Test
    fun `empty bucket is null not zero`() {
        val out = MemoryHealth.forgettingCurve(listOf(GapSample(0.5, true)))
        val empty = out.filter { it.sampleCount == 0 }
        assertTrue("应当存在空桶", empty.isNotEmpty())
        empty.forEach { assertNull(it.observedRecall) }
    }

    @Test
    fun `no samples at all yields all-null buckets`() {
        assertTrue(MemoryHealth.forgettingCurve(emptyList()).all { it.observedRecall == null })
    }

    /** 落在所有桶之外（gap ≤ 0）或非法的样本不猜进最近的桶 */
    @Test
    fun `samples outside every bucket are ignored rather than guessed into one`() {
        val out = MemoryHealth.forgettingCurve(
            listOf(GapSample(0.0, true), GapSample(-3.0, true), GapSample(Double.NaN, true), GapSample(2.0, true)),
        )
        assertEquals(1, out.sumOf { it.sampleCount })
    }

    // ── 模型曲线与艾宾浩斯 ────────────────────────────────────────

    /** 单个半衰期在 d = h 时必然是 50%，这是"半衰期"这个词的全部含义 */
    @Test
    fun `model curve is 50 percent at exactly one half-life`() {
        assertEquals(0.5, MemoryHealth.modelCurve(listOf(10.0), listOf(10))[0], 1e-9)
    }

    @Test
    fun `model curve averages across the library`() {
        val out = MemoryHealth.modelCurve(listOf(10.0, 30.0), listOf(10))
        assertEquals((0.5 + 2.0.pow(-10.0 / 30.0)) / 2.0, out[0], 1e-9)
    }

    @Test
    fun `model curve is monotonically decreasing and ignores illegal half-lives`() {
        val out = MemoryHealth.modelCurve(listOf(20.0, Double.NaN, -5.0), (0..9).toList())
        assertEquals(10, out.size)
        assertEquals(1.0, out[0], 1e-9)
        for (i in 1 until out.size) assertTrue("第 $i 天没有变低", out[i] <= out[i - 1])
    }

    @Test
    fun `model curve of an empty library is all zeros rather than crashing`() {
        assertTrue(MemoryHealth.modelCurve(emptyList(), listOf(0, 1, 2)).all { it == 0.0 })
    }

    @Test
    fun `ebbinghaus reference starts at 100 percent and falls fast then flattens`() {
        val pts = MemoryHealth.EbbinghausPoints
        assertEquals(1.0, pts.first().second, 1e-9)
        val day1 = pts.first { it.first >= 1.0 }.second
        assertTrue("第 1 天应在 25%~45% 之间，实际 $day1", day1 in 0.25..0.45)
        val last = pts.last()
        assertTrue("尾部要比第 1 天高（先陡后平），实际 ${last.second} vs $day1", last.second > 0.15)
        assertTrue(pts.map { it.first }.sorted() == pts.map { it.first })
    }
}
