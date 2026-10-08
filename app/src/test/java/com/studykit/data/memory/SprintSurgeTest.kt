package com.studykit.data.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 考前涌量预警（文档 L1891：「ramp 开启后峰值/中位 >2 时触发文案」）。
 *
 * 分母取**中位数**而不是均值，是因为负荷带的分布天生右偏：考试前一两天堆出尖峰，
 * 均值会被尖峰自己抬起来、把比值压小，中位不动，峰/中位才是"有没有堆成山"的读数。
 * 纯函数：只吃 v3.0-A 的 `DayBand`，不碰 Room、不碰预测器。
 */
class SprintSurgeTest {

    private fun band(i: Int, mean: Double) = DayBand(i, mean, mean * 0.1)

    @Test
    fun `峰值比中位超过 2 才报警`() {
        val flat = (0 until 14).map { band(it, 20.0) }
        // 平的：峰就是中位，读数 1.0（不是 null —— null 只留给"算不出"的两种情形）
        assertEquals(1.0, SprintSurge.peakMedianRatio(flat)!!, 1e-9)
        assertFalse(SprintSurge.shouldWarn(SprintSurge.peakMedianRatio(flat)))
        val spike = (0 until 14).map { band(it, if (it == 13) 90.0 else 20.0) }
        assertEquals(4.5, SprintSurge.peakMedianRatio(spike)!!, 1e-9)
        assertTrue(SprintSurge.shouldWarn(SprintSurge.peakMedianRatio(spike)))
    }

    @Test
    fun `恰好等于两倍不报警（文档要的是严格大于）`() {
        val bands = (0 until 14).map { band(it, if (it == 13) 40.0 else 20.0) }
        assertEquals(2.0, SprintSurge.peakMedianRatio(bands)!!, 1e-9)
        assertFalse(SprintSurge.shouldWarn(2.0))
        assertTrue(SprintSurge.shouldWarn(2.01))
    }

    @Test
    fun `空表与单格给 null，不炸`() {
        assertNull(SprintSurge.peakMedianRatio(emptyList()))
        assertNull(SprintSurge.peakMedianRatio(listOf(band(0, 5.0))))
        assertFalse(SprintSurge.shouldWarn(null))
    }

    @Test
    fun `中位为 0 给 null 而不是除零`() {
        val mostlyEmpty = (0 until 14).map { band(it, if (it == 0) 30.0 else 0.0) }
        assertNull(SprintSurge.peakMedianRatio(mostlyEmpty))
    }

    @Test
    fun `奇数个格子取正中间那一个`() {
        val odd = (0 until 5).map { band(it, it.toDouble()) }   // 0,1,2,3,4 -> 中位 2，峰 4 -> 2.0
        assertEquals(2.0, SprintSurge.peakMedianRatio(odd)!!, 1e-9)
    }

    @Test
    fun `偶数个格子取中间两值平均`() {
        val even = (0 until 4).map { band(it, it + 1.0) }       // 1,2,3,4 -> 中位 2.5，峰 4 -> 1.6
        assertEquals(1.6, SprintSurge.peakMedianRatio(even)!!, 1e-9)
    }

    @Test
    fun `报警文案带上倍数与那句解释`() {
        val line = SprintSurge.warningLine(3.5)
        assertTrue(line, line.contains("3.5 倍"))
        assertTrue(line, line.contains("复习量会涨"))
    }

    @Test
    fun `忽略 sd 只看 mean`() {
        val noisy = (0 until 14).map { DayBand(it, 20.0, 999.0) }
        // sd 再大也不该影响读数：预测的"堆积"量的是 mean
        assertEquals(1.0, SprintSurge.peakMedianRatio(noisy)!!, 1e-9)
    }
}
