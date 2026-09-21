package com.studykit.data

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AppSettings] 的纯逻辑单测 —— 键值行与类型化设置之间的双向映射。
 *
 * 钉住的是"永不被坏值打垮"这一条：设置表里的值可能来自旧版本、也可能来自用户手工改过的
 * 备份恢复包（本版有"从 zip 恢复"），任何一种解释不通都只该让**那一项**退回默认，
 * 而不是让 App 启动时抛异常。
 */
class AppSettingsTest {

    @Test
    fun `无参构造就是没进过设置页时的行为`() {
        val s = AppSettings()
        assertEquals(ThemeMode.SYSTEM, s.themeMode)
        assertEquals(GlassLevel.SOFT, s.glass)
        assertFalse(s.reduceMotion)
        assertEquals("", s.nickname)
        assertEquals(AppSettings.DEFAULT_WORD_GOAL, s.dailyWordGoal)
        assertEquals(AppSettings.DEFAULT_REMINDER_HOURS, s.reminderEveryHours)
        assertNull(s.examDate)
    }

    @Test
    fun `空表退回全默认`() {
        assertEquals(AppSettings(), AppSettings.fromMap(emptyMap()))
    }

    @Test
    fun `写下去再读回来一分不变`() {
        val original = AppSettings(
            themeMode = ThemeMode.DARK,
            glass = GlassLevel.STRONG,
            reduceMotion = true,
            nickname = "小计划",
            dailyWordGoal = 88,
            reminderEveryHours = 12,
            examEpochDay = LocalDate.of(2026, 12, 20).toEpochDay(),
            lastDictFailure = "请求被网络重定向到了 100.100.9.2",
        )
        assertEquals(original, AppSettings.fromMap(original.toMap()))
    }

    @Test
    fun `未识别的枚举值只让那一项退回默认`() {
        val s = AppSettings.fromMap(mapOf(AppSettings.KEY_THEME to "MOON", AppSettings.KEY_GLASS to "GLASSY"))
        assertEquals(ThemeMode.SYSTEM, s.themeMode)
        assertEquals(GlassLevel.SOFT, s.glass)
    }

    @Test
    fun `越界与垃圾数字一律退回默认`() {
        val junk = mapOf(
            AppSettings.KEY_REDUCE_MOTION to "也许",
            AppSettings.KEY_WORD_GOAL to "0",
            AppSettings.KEY_REMINDER_HOURS to "200",
            AppSettings.KEY_EXAM_DAY to "-5",
        )
        val s = AppSettings.fromMap(junk)
        assertFalse(s.reduceMotion)
        assertEquals(AppSettings.DEFAULT_WORD_GOAL, s.dailyWordGoal)
        assertEquals(AppSettings.DEFAULT_REMINDER_HOURS, s.reminderEveryHours)
        assertNull(s.examDate)

        // 区间边界本身要收
        assertEquals(500, AppSettings.fromMap(mapOf(AppSettings.KEY_WORD_GOAL to "500")).dailyWordGoal)
        assertEquals(72, AppSettings.fromMap(mapOf(AppSettings.KEY_REMINDER_HOURS to "72")).reminderEveryHours)
    }

    @Test
    fun `昵称两端空白去掉并限长`() {
        assertEquals("张三", AppSettings.fromMap(mapOf(AppSettings.KEY_NICKNAME to "  张三  ")).nickname)
        val long = "词".repeat(60)
        assertEquals(AppSettings.NICKNAME_MAX, AppSettings.fromMap(mapOf(AppSettings.KEY_NICKNAME to long)).nickname.length)
    }

    @Test
    fun `未知键忽略而不影响已知项`() {
        val s = AppSettings.fromMap(
            mapOf(AppSettings.KEY_THEME to "LIGHT", "some_future_key" to "whatever"),
        )
        assertEquals(ThemeMode.LIGHT, s.themeMode)
    }

    @Test
    fun `主题三态落到是否深色`() {
        assertFalse(AppSettings(themeMode = ThemeMode.LIGHT).resolveDark(systemDark = true))
        assertTrue(AppSettings(themeMode = ThemeMode.DARK).resolveDark(systemDark = false))
        assertTrue(AppSettings(themeMode = ThemeMode.SYSTEM).resolveDark(systemDark = true))
        assertFalse(AppSettings(themeMode = ThemeMode.SYSTEM).resolveDark(systemDark = false))
    }

    @Test
    fun `考试日期零值表示未设置 其余按 epochDay 解出`() {
        val day = LocalDate.of(2027, 3, 1)
        assertEquals(day, AppSettings(examEpochDay = day.toEpochDay()).examDate)
        assertNull(AppSettings(examEpochDay = 0L).examDate)
    }

    @Test
    fun `诊断文本超长被掐掉而不是撑坏设置页`() {
        val s = AppSettings.fromMap(mapOf(AppSettings.KEY_DICT_FAILURE to "x".repeat(5_000)))
        assertEquals(400, s.lastDictFailure.length)
    }
}
