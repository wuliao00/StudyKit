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
        // v2.5 §3.1/§3.2 那两项：闸门默认**开**（设计里的默认行为，不是"用户已经选过"），
        // 那条一次性说明默认**没看过**（每次安装只出现一次）
        assertTrue(s.recallBeforeGrade)
        assertFalse(s.recallGateHintSeen)
    }

    @Test
    fun `空表退回全默认`() {
        assertEquals(AppSettings(), AppSettings.fromMap(emptyMap()))
    }

    @Test
    fun `闸门两项写下去再读回来一分不变`() {
        // 键值行表加字段 = 加一行，不需要迁移：这一条钉的就是"两行都能原样回来"
        val off = AppSettings(recallBeforeGrade = false, recallGateHintSeen = true)
        assertEquals(off, AppSettings.fromMap(off.toMap()))
        val on = AppSettings(recallBeforeGrade = true, recallGateHintSeen = false)
        assertEquals(on, AppSettings.fromMap(on.toMap()))
        // 缺键（首装一行都没写过）取默认：闸门开、说明没看过
        val fresh = AppSettings.fromMap(mapOf(AppSettings.KEY_THEME to "LIGHT"))
        assertTrue(fresh.recallBeforeGrade)
        assertFalse(fresh.recallGateHintSeen)
    }

    @Test
    fun `闸门两项的垃圾值只让那一项退回默认`() {
        // 库里可能被手改过的备份恢复进来任何字符串；解释不通就退回默认，而不是整个 App 崩
        val junk = mapOf(
            AppSettings.KEY_RECALL_BEFORE_GRADE to "也许",
            AppSettings.KEY_RECALL_GATE_HINT_SEEN to "1",
        )
        val s = AppSettings.fromMap(junk)
        assertTrue(s.recallBeforeGrade)
        assertFalse(s.recallGateHintSeen)
        // "true"/"false" 这两个严格写法要收（Boolean.toString 的产物就是它们）
        assertFalse(AppSettings.fromMap(mapOf(AppSettings.KEY_RECALL_BEFORE_GRADE to "false")).recallBeforeGrade)
        assertTrue(AppSettings.fromMap(mapOf(AppSettings.KEY_RECALL_GATE_HINT_SEEN to "true")).recallGateHintSeen)
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

    @Test
    fun `排期内核默认 FSRS 且垃圾值退回默认`() {
        assertEquals("FSRS", AppSettings.fromMap(emptyMap()).schedulingKernel)
        assertEquals(
            "FSRS",
            AppSettings.fromMap(mapOf(AppSettings.KEY_SCHEDULING_KERNEL to "???")).schedulingKernel,
        )
        assertEquals(
            "HALF_LIFE",
            AppSettings.fromMap(mapOf(AppSettings.KEY_SCHEDULING_KERNEL to "HALF_LIFE")).schedulingKernel,
        )
    }

    @Test
    fun `信心开关默认开且经 toMap 往返一分不变`() {
        val s = AppSettings(confidenceEnabled = false)
        assertEquals(false, AppSettings.fromMap(s.toMap()).confidenceEnabled)
        assertEquals(true, AppSettings.fromMap(emptyMap()).confidenceEnabled)
    }

    @Test
    fun `新增两键的默认值也能原样往返`() {
        val d = AppSettings()
        val back = AppSettings.fromMap(d.toMap())
        assertEquals(d.schedulingKernel, back.schedulingKernel)
        assertEquals(d.confidenceEnabled, back.confidenceEnabled)
    }

    @Test
    fun `翻面贴士默认没看过且经 toMap 往返一分不变`() {
        // 口径同 recallGateHintSeen：每次安装一次的"看过没有"，默认 false（没看过）
        assertFalse(AppSettings().flipTipSeen)
        assertFalse(AppSettings.fromMap(emptyMap()).flipTipSeen)
        val seen = AppSettings(flipTipSeen = true)
        assertEquals(true, AppSettings.fromMap(seen.toMap()).flipTipSeen)
    }

    @Test
    fun `翻面贴士的垃圾值只让那一项退回默认`() {
        assertFalse(AppSettings.fromMap(mapOf(AppSettings.KEY_FLIP_TIP_SEEN to "1")).flipTipSeen)
        assertTrue(AppSettings.fromMap(mapOf(AppSettings.KEY_FLIP_TIP_SEEN to "true")).flipTipSeen)
    }

    @Test
    fun `执行意图贴士默认没看过且经 toMap 往返一分不变`() {
        // 口径同 flipTipSeen：每次安装一次的"看过没有"，默认 false（没看过）
        assertFalse(AppSettings().ifThenTipSeen)
        assertFalse(AppSettings.fromMap(emptyMap()).ifThenTipSeen)
        val seen = AppSettings(ifThenTipSeen = true)
        assertEquals(true, AppSettings.fromMap(seen.toMap()).ifThenTipSeen)
    }

    @Test
    fun `执行意图贴士的垃圾值只让那一项退回默认`() {
        assertFalse(AppSettings.fromMap(mapOf(AppSettings.KEY_IF_THEN_TIP_SEEN to "1")).ifThenTipSeen)
        assertTrue(AppSettings.fromMap(mapOf(AppSettings.KEY_IF_THEN_TIP_SEEN to "true")).ifThenTipSeen)
    }

    @Test
    fun `六六贴士默认没看过且经 toMap 往返一分不变`() {
        // 口径同 flipTipSeen / ifThenTipSeen：每次安装一次的"看过没有"，默认 false（没看过）
        assertFalse(AppSettings().sixtySixTipSeen)
        assertFalse(AppSettings.fromMap(emptyMap()).sixtySixTipSeen)
        val seen = AppSettings(sixtySixTipSeen = true)
        assertEquals(true, AppSettings.fromMap(seen.toMap()).sixtySixTipSeen)
    }

    @Test
    fun `六六贴士的垃圾值只让那一项退回默认`() {
        assertFalse(AppSettings.fromMap(mapOf(AppSettings.KEY_SIXTY_SIX_TIP_SEEN to "1")).sixtySixTipSeen)
        assertTrue(AppSettings.fromMap(mapOf(AppSettings.KEY_SIXTY_SIX_TIP_SEEN to "true")).sixtySixTipSeen)
    }

    @Test
    fun `宽恕贴士默认没看过且经 toMap 往返一分不变`() {
        // 口径同 flipTipSeen / ifThenTipSeen / sixtySixTipSeen：每次安装一次的“看过没有”，默认 false
        assertFalse(AppSettings().gapTipSeen)
        assertFalse(AppSettings.fromMap(emptyMap()).gapTipSeen)
        val seen = AppSettings(gapTipSeen = true)
        assertEquals(true, AppSettings.fromMap(seen.toMap()).gapTipSeen)
    }

    @Test
    fun `宽恕贴士的垃圾值只让那一项退回默认`() {
        assertFalse(AppSettings.fromMap(mapOf(AppSettings.KEY_GAP_TIP_SEEN to "1")).gapTipSeen)
        assertTrue(AppSettings.fromMap(mapOf(AppSettings.KEY_GAP_TIP_SEEN to "true")).gapTipSeen)
    }

    @Test
    fun `章节自测贴士默认没看过且经 toMap 往返一分不变`() {
        // 口径同 flipTipSeen / ifThenTipSeen / sixtySixTipSeen / gapTipSeen：每次安装一次的“看过没有”，默认 false
        assertFalse(AppSettings().chapterTipSeen)
        assertFalse(AppSettings.fromMap(emptyMap()).chapterTipSeen)
        val seen = AppSettings(chapterTipSeen = true)
        assertEquals(true, AppSettings.fromMap(seen.toMap()).chapterTipSeen)
    }

    @Test
    fun `章节自测贴士的垃圾值只让那一项退回默认`() {
        assertFalse(AppSettings.fromMap(mapOf(AppSettings.KEY_CHAPTER_TIP_SEEN to "1")).chapterTipSeen)
        assertTrue(AppSettings.fromMap(mapOf(AppSettings.KEY_CHAPTER_TIP_SEEN to "true")).chapterTipSeen)
    }

    @Test
    fun `书摘检索贴士默认没看过且经 toMap 往返一分不变`() {
        // 与 chapterTipSeen 分开两枚布尔（复审建议：以后可泛型成一个 seen-set，本次不重构）
        assertFalse(AppSettings().excerptTipSeen)
        assertFalse(AppSettings.fromMap(emptyMap()).excerptTipSeen)
        val seen = AppSettings(excerptTipSeen = true)
        assertEquals(true, AppSettings.fromMap(seen.toMap()).excerptTipSeen)
    }

    @Test
    fun `书摘检索贴士的垃圾值只让那一项退回默认`() {
        assertFalse(AppSettings.fromMap(mapOf(AppSettings.KEY_EXCERPT_TIP_SEEN to "1")).excerptTipSeen)
        assertTrue(AppSettings.fromMap(mapOf(AppSettings.KEY_EXCERPT_TIP_SEEN to "true")).excerptTipSeen)
    }
}
