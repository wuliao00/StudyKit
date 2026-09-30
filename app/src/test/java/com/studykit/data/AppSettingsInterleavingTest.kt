package com.studykit.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 交错练习开关（[AppSettings.interleavingEnabled]）的键值往返单测。
 *
 * 沿用 [AppSettingsTest] 里对闸门两项（recallBeforeGrade / recallGateHintSeen）的同一口径：
 *  - **默认开**：这是设计里的默认行为，不是「用户已经选过」，所以首装一行都没写进库也应为 true；
 *  - **写下去原样回来**：加一列开关 = 加一行键值，不需要 Room 迁移（app_settings 是键值表）；
 *  - **垃圾值只让这一项退回默认**：库里的值可能被旧版本或手改备份恢复进来，解释不通只退回本项。
 */
class AppSettingsInterleavingTest {

    @Test fun `默认开`() {
        assertTrue(AppSettings().interleavingEnabled)
        // 空表（从没进过设置页）也取默认开
        assertTrue(AppSettings.fromMap(emptyMap()).interleavingEnabled)
    }

    @Test fun `写下去再读回来一分不变`() {
        val off = AppSettings(interleavingEnabled = false)
        assertEquals(off, AppSettings.fromMap(off.toMap()))
        val on = AppSettings(interleavingEnabled = true)
        assertEquals(on, AppSettings.fromMap(on.toMap()))
    }

    @Test fun `垃圾值只让交错这一项退回默认`() {
        val junk = mapOf(AppSettings.KEY_INTERLEAVING to "也许")
        assertTrue(AppSettings.fromMap(junk).interleavingEnabled)
        // "true"/"false" 这两个严格写法要收（Boolean.toString 的产物就是它们）
        assertFalse(AppSettings.fromMap(mapOf(AppSettings.KEY_INTERLEAVING to "false")).interleavingEnabled)
        assertTrue(AppSettings.fromMap(mapOf(AppSettings.KEY_INTERLEAVING to "true")).interleavingEnabled)
    }

    @Test fun `交错开关落进 toMap 的独立键`() {
        val map = AppSettings(interleavingEnabled = false).toMap()
        assertEquals("false", map[AppSettings.KEY_INTERLEAVING])
    }
}
