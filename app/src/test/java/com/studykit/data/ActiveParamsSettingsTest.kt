package com.studykit.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AppSettings.fsrsActiveParams] 的键值往返（v2.9 计划 Task 5）。
 *
 * 这一条把"训练出的活跃参数"接进设置层的存/取。口径刻意镜像 [AppSettings.flipTipSeen] 那类
 * 布尔项的**垃圾值只让那一项退回默认**纪律，只是这里的"退回默认"是 `null`（回落 `DEFAULT_PARAMS`）：
 * 21 参向量要么完整收下，要么一律当脏值丢弃，**绝不允许**把半截（长度≠21）或含非数字的串
 * 拼成一个残缺数组喂给调度器——残缺数组会让 `FsrsKernel` 在 `w[i]` 上越界或算出无意义间隔。
 */
class ActiveParamsSettingsTest {

    private fun params(seed: Double): DoubleArray = DoubleArray(FsrsModelLength.NP) { seed + it }

    @Test
    fun `21 参数组经 toMap fromMap 原样往返`() {
        val arr = params(0.1)
        val s = AppSettings(fsrsActiveParams = arr)
        val back = AppSettings.fromMap(s.toMap()).fsrsActiveParams
        assertTrue("往返后应逐值相等", back != null && back.contentEquals(arr))
    }

    @Test
    fun `默认构造与空表都取到 null（回落默认参数）`() {
        assertNull(AppSettings().fsrsActiveParams)
        assertNull(AppSettings.fromMap(emptyMap()).fsrsActiveParams)
    }

    @Test
    fun `字符串缺键时其余项照常往返`() {
        val s = AppSettings(themeMode = ThemeMode.DARK)
        val back = AppSettings.fromMap(s.toMap())
        assertEquals(ThemeMode.DARK, back.themeMode)
        assertNull(back.fsrsActiveParams)
    }

    @Test
    fun `长度不等于21一律视为脏值退回null`() {
        val short20 = (0 until 20).joinToString(",") { it.toString() }
        assertNull(AppSettings.fromMap(mapOf(AppSettings.KEY_FSRS_ACTIVE_PARAMS to short20)).fsrsActiveParams)
        val long22 = (0 until 22).joinToString(",") { it.toString() }
        assertNull(AppSettings.fromMap(mapOf(AppSettings.KEY_FSRS_ACTIVE_PARAMS to long22)).fsrsActiveParams)
    }

    @Test
    fun `含非数字项一律视为脏值退回null`() {
        // 21 个 token，但第 7 个非数字：mapNotNull 丢掉它后长度只剩 20 → 脏 → null
        val junk = (0 until 21).joinToString(",") { if (it == 7) "abc" else it.toString() }
        assertNull(AppSettings.fromMap(mapOf(AppSettings.KEY_FSRS_ACTIVE_PARAMS to junk)).fsrsActiveParams)
        // 空串也脏
        assertNull(AppSettings.fromMap(mapOf(AppSettings.KEY_FSRS_ACTIVE_PARAMS to "")).fsrsActiveParams)
    }

    @Test
    fun `写入的字符串是21项逗号拼接`() {
        val arr = params(0.5)
        val raw = AppSettings(fsrsActiveParams = arr).toMap()[AppSettings.KEY_FSRS_ACTIVE_PARAMS]
        assertEquals(21, raw?.split(",")?.size)
    }
}

/** 测试内引用参数维度，避免把 21 这个魔法数散在各处（与 FsrsModel.NP 同值）。 */
private object FsrsModelLength {
    const val NP = 21
}
