package com.studykit.ui.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 稳定性全景分箱（v3.0-A Task 7）的单测：纯 JVM、无 runner —— [StabilityBins] 零 Android 依赖，
 * 分箱这种"边界差一点就整段读错"的规则只有在没有真机的情况下也能钉住才算钉住。
 *
 * ## 为什么边界要钉得这么死
 * 分箱是**下闭上开**（`S = 4.0` 进「4-8 天」，`S = 3.9` 进「2-4 天」）。这条约定一旦反过来写，
 * 每张恰好落在边界上的卡都会被挪进上一档，而**总数照样对**——用户看到的是一幅"记忆比实际更牢"的图，
 * 且没有任何一处会报错。文档 ⛔ 单独点出 `S=3.9 → 4d 箱`，就是因为这个方向最容易写错。
 *
 * ## 分母是这块图唯一的"口径耦合点"
 * `sharePercent` 的分母是**可信卡数**（过滤掉脏值之后那批），与
 * [MemoryHealth.durability] 用的是同一条约定（那边的原话：分母是全部可信的词，不是各桶之和）。
 * 两处各判一次"什么算可信"迟早分叉，所以可信判据（正有限数）在本文件只写一次，
 * 并由 `百分比的分母是可信卡数，不是传入条数` 这条测试钉住分母。
 */
class StabilityBinsTest {

    @Test
    fun `箱边界是 log2 步长，钉住这一串数`() {
        assertEquals(
            listOf(0.5, 1.0, 2.0, 4.0, 8.0, 16.0, 32.0, 64.0, 128.0, 256.0, 365.0),
            StabilityBins.Edges,
        )
    }

    /**
     * 文档 ⛔ 那条：`S = 3.9 → 4d 箱`。brief 把这一箱叫「4 天箱」，指的是**上界为 4** 的那一箱，
     * 也就是标签写的「2-4 天」；与 `S = 4.0` 进「4-8 天」是同一个下闭上开的两面。
     *
     * 方法名里不能有点（JVM 名称禁止），所以 brief 原文的 `S 3.9 落在…` 这个名字编译不过，改成"3 点 9"。
     */
    @Test
    fun `S 为 3 点 9 时落在上界为 4 的那箱而不是下界为 4 的那箱`() {
        val bins = StabilityBins.bin(listOf(3.9))
        assertEquals(1, bins.first { it.label == "2-4 天" }.count)
        assertEquals(0, bins.first { it.label == "4-8 天" }.count)
    }

    @Test
    fun `箱边界是下闭上开`() {
        assertEquals("2-4 天", StabilityBins.bin(listOf(2.0)).first { it.count > 0 }.label)
        assertEquals("4-8 天", StabilityBins.bin(listOf(4.0)).first { it.count > 0 }.label)
    }

    @Test
    fun `首尾两条边界同样下闭上开`() {
        // 下界：0.5 这条边本身属于「0.5-1 天」，0.49 才落到「≤0.5 天」
        assertEquals("0.5-1 天", StabilityBins.bin(listOf(0.5)).first { it.count > 0 }.label)
        assertEquals("≤0.5 天", StabilityBins.bin(listOf(0.49)).first { it.count > 0 }.label)
        // 上界：365 是最后一条**开区间上界**，不是"1 年以内"的闭端
        assertEquals("256-365 天", StabilityBins.bin(listOf(364.9)).first { it.count > 0 }.label)
        assertEquals("1 年以上", StabilityBins.bin(listOf(365.0)).first { it.count > 0 }.label)
    }

    @Test
    fun `标签条数比边界多一条且首尾各占一档`() {
        val bins = StabilityBins.bin(emptyList())
        assertEquals(StabilityBins.Edges.size + 1, bins.size)
        assertEquals("≤0.5 天", bins.first().label)
        assertEquals("1 年以上", bins.last().label)
    }

    @Test
    fun `脏值被排除且各箱计数之和等于可信数`() {
        val bins = StabilityBins.bin(listOf(1.0, 3.0, Double.NaN, 0.0, -2.0, 100.0))
        assertEquals(3, bins.sumOf { it.count })
    }

    /** ±Infinity 过得了 `> 0.0`：只有 `isFinite()` 挡得住它，与 [ForecastCards] 同一条纪律。 */
    @Test
    fun `正无穷不算可信`() {
        val bins = StabilityBins.bin(listOf(Double.POSITIVE_INFINITY, 5.0))
        assertEquals(1, bins.sumOf { it.count })
        assertEquals("4-8 天", bins.first { it.count > 0 }.label)
    }

    /** 分母必须是可信卡数：写成"传入条数"时脏值会来稀释百分比，两栏数字立刻对不上账。 */
    @Test
    fun `百分比的分母是可信卡数，不是传入条数`() {
        val bins = StabilityBins.bin(listOf(1.0, Double.NaN, 0.0))
        assertEquals(100, bins.first { it.label == "1-2 天" }.sharePercent)
        assertEquals(100, bins.sumOf { it.sharePercent })
    }

    @Test
    fun `一百分布不会因四舍五入超过 100`() {
        val bins = StabilityBins.bin(List(7) { 1.5 })
        assertTrue(bins.sumOf { it.sharePercent } <= 100)
    }

    /** 互斥分箱（与 durability 那四档嵌套 ≥ 不同）：各箱百分比之和应回到 100。 */
    @Test
    fun `互斥分箱的百分比之和回到一百`() {
        val bins = StabilityBins.bin(listOf(0.4, 1.5, 9.0, 200.0, 400.0))
        assertEquals(100, bins.sumOf { it.sharePercent })
    }

    @Test
    fun `空输入产出全零而不抛`() {
        val bins = StabilityBins.bin(emptyList())
        assertTrue(bins.all { it.count == 0 })
        assertTrue(bins.all { it.sharePercent == 0 })
    }

    /** minDays 是这一档的**下界**（第一档没有下界，取 0.0），视图与调试都靠它对得上边界串。 */
    @Test
    fun `每档的 minDays 是它的下界`() {
        val bins = StabilityBins.bin(emptyList())
        assertEquals(0.0, bins.first().minDays, 0.0)
        assertEquals(0.5, bins[1].minDays, 0.0)
        assertEquals(4.0, bins[4].minDays, 0.0)
        assertEquals(365.0, bins.last().minDays, 0.0)
    }

    /**
     * 占比文字沿用 [DurabilityBucket.shareLabel] 那条教训：一千多个词里只有 1 个扛过某档时
     * 四舍五入是 0%，"1 个 · 0%"是自相矛盾的话（真机上就这么显示过）。
     */
    @Test
    fun `有计数但四舍五入为零时占比文字不说零`() {
        val bins = StabilityBins.bin(List(999) { 1.5 } + listOf(50.0))
        val rare = bins.first { it.label == "32-64 天" }
        assertEquals(1, rare.count)
        assertEquals(0, rare.sharePercent)
        assertEquals("不足 1%", rare.shareLabel)
        assertEquals("0%", bins.first { it.label == "1 年以上" }.shareLabel)
    }
}
