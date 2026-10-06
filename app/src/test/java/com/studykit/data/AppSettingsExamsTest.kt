package com.studykit.data

import com.studykit.data.memory.ExamEntry
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v3.0-B 考试清单的设置层往返。
 *
 * 钉两件事：① 脏值只该让**那一条**退回默认，绝不打垮 `fromMap`（与 `AppSettingsTest` 同一条纪律）；
 * ② 旧版只有一个 `exam_epoch_day` 的用户，升级后必须看到**一模一样**的行为 ——
 * 所以 `examList` 在清单为空时从旧键合成一场全学科考试，`examEpochDayEffective` 回原值。
 *
 * 沿用 Task 1 立下的约定：**期望值一律写字面量**，不引用 `ExamEntry.DEFAULT_*` 这类生产常量 ——
 * 常量被改时测试会跟着一起改，闸门就形同虚设。双精度那条必须带 delta。
 */
class AppSettingsExamsTest {

    private val sample = listOf(
        ExamEntry("期末考", 20_600L, listOf("数学", "物理"), 0.94, 21),
        ExamEntry("四级", 20_700L, emptyList(), 0.95, 30),
    )

    @Test
    fun `默认值：没有考试、不限时、暂停不计入`() {
        val s = AppSettings()
        assertTrue(s.exams.isEmpty())
        assertEquals(0, s.examLimitMinutes)
        assertEquals(0L, s.examEpochDayEffective)
    }

    @Test
    fun `清单写出读回逐字段相等`() {
        val back = AppSettings.fromMap(AppSettings(exams = sample).toMap())
        assertEquals(sample, back.exams)
    }

    @Test
    fun `限时与暂停开关往返`() {
        val back = AppSettings.fromMap(AppSettings(examLimitMinutes = 90, examPauseCountsTime = false).toMap())
        assertEquals(90, back.examLimitMinutes)
        assertEquals(false, back.examPauseCountsTime)
    }

    @Test
    fun `examEpochDayEffective 取最早一场`() {
        assertEquals(20_600L, AppSettings(exams = sample).examEpochDayEffective)
    }

    @Test
    fun `examForCountdown 优先未到的那场，全过了取最后过的那场`() {
        val today = LocalDate.ofEpochDay(20_650L)
        val s = AppSettings(exams = sample)
        assertEquals("四级", s.examForCountdown(today)?.name)          // 期末考(20600)已过，四级(20700)未到
        val allPast = AppSettings(exams = listOf(ExamEntry("A", 20_500L), ExamEntry("B", 20_600L)))
        assertEquals("B", allPast.examForCountdown(today)?.name)       // 全过 -> 最近过掉的那场，"已过 N 天"这句还在
        assertEquals(null, AppSettings().examForCountdown(today))
    }

    @Test
    fun `旧单日期用户在清单为空时行为不变`() {
        val legacy = AppSettings.fromMap(mapOf(AppSettings.KEY_EXAM_DAY to "20600"))
        assertEquals(20_600L, legacy.examEpochDayEffective)
        assertEquals(1, legacy.examList.size)
        assertTrue(legacy.examList.first().subjects.isEmpty())          // 全学科，与 v2.9 的影响面一致
        assertEquals(LocalDate.ofEpochDay(20_600L), legacy.examDate)
    }

    @Test
    fun `脏串整条退回空表，不抛`() {
        assertEquals(emptyList<ExamEntry>(), AppSettings.examsFromJson(""))
        assertEquals(emptyList<ExamEntry>(), AppSettings.examsFromJson("not json"))
        assertEquals(emptyList<ExamEntry>(), AppSettings.examsFromJson("[1,2]"))
        assertTrue(AppSettings.fromMap(mapOf(AppSettings.KEY_EXAMS to "}{{")).exams.isEmpty())
    }

    @Test
    fun `单条脏字段只退回默认，条目本身保住`() {
        val raw = """[{"name":"数学","epochDay":20600,"subjects":["数学"],"rSprint":9,"windowDays":-5}]"""
        val one = AppSettings.examsFromJson(raw)
        assertEquals(1, one.size)
        // 期望值钉字面量，不读 ExamEntry.DEFAULT_R_SPRINT / DEFAULT_WINDOW_DAYS：常量一动这条门就跟着动，等于没钉
        // （双精度比较必须带 delta —— 两参的 assertEquals(double, double) 在 JUnit4 里是「无条件失败」的死路）
        assertEquals(0.95, one[0].rSprint, 1e-9)     // 9 越界 -> 默认
        assertEquals(30, one[0].windowDays)          // -5 越界 -> 默认
        assertEquals("数学", one[0].name)
    }

    @Test
    fun `epochDay 越界或缺失的那一条整条丢弃`() {
        val raw = """[{"name":"坏","epochDay":1},{"name":"好","epochDay":20600}]"""
        val list = AppSettings.examsFromJson(raw)
        assertEquals(listOf(20600L), list.map { it.epochDay })
    }

    @Test
    fun `subjects 不是数组时退化为全学科而不是崩`() {
        val raw = """[{"name":"x","epochDay":20600,"subjects":"数学"}]"""
        assertTrue(AppSettings.examsFromJson(raw).first().subjects.isEmpty())
    }

    @Test
    fun `限时越界退回 0`() {
        assertEquals(0, AppSettings.fromMap(mapOf(AppSettings.KEY_EXAM_LIMIT_MIN to "99999")).examLimitMinutes)
        assertEquals(0, AppSettings.fromMap(mapOf(AppSettings.KEY_EXAM_LIMIT_MIN to "abc")).examLimitMinutes)
    }

    @Test
    fun `未知键忽略、缺键给默认`() {
        val s = AppSettings.fromMap(mapOf("exams_json_v9" to "[]"))
        assertTrue(s.exams.isEmpty())
        assertEquals(0, s.examLimitMinutes)
    }
}
