package com.studykit.ui.habit

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 近 7 天达标（弹性口径）的纯计算与那一行文案。
 *
 * 为什么单独钉：这一格是从「连续天数」旁边长出来的第二个指标，而它存在的理由恰恰是
 * **不要把连续天数那套"断了就归零"的暗示搬过来**（Lally et al. 2010, EJSP：漏一天
 * 并不毁掉自动性）。所以除了算得对，这里还钉住两件事：窗口边界、以及文案里
 * 不许出现的说法（断签 / 清零 / 额度 / 自动补签 —— 最后两条是因为本轮**没有**
 * 断签保护卡的落库位置，UI 上一句都不许暗示）。
 */
class WeekComplianceTest {

    private val today: LocalDate = LocalDate.of(2026, 9, 30)

    /** 夹具按"距今第 n 天"给，读起来与断言里的口径同一套词 */
    private fun dates(vararg daysAgo: Int): Set<LocalDate> =
        daysAgo.map { today.minusDays(it.toLong()) }.toSet()

    // ── 达标判定：5/7 这类弹性口径 ────────────────────────────────

    /** 窗口内打卡 5 天就算跟上：71% 是四舍五入后的达标率 */
    @Test
    fun `窗口内打卡满 5 天记为达标`() {
        val week = weekCompliance(dates(0, 1, 2, 3, 4), today)
        assertEquals(7, week.windowDays)
        assertEquals(5, week.checkedDays)
        assertEquals(5, week.targetDays)
        assertTrue(week.achieved)
        assertEquals(71, week.ratePercent)
    }

    /** 漏一天（6/7）不但不判负，那一行字里也不许出现"断"这个字 */
    @Test
    fun `漏一天不判负也不说成断链`() {
        val week = weekCompliance(dates(0, 1, 2, 3, 4, 5), today)
        assertEquals(6, week.checkedDays)
        assertTrue(week.achieved)
        val label = weekComplianceLabel(week)
        assertFalse("「$label」又把漏一天说成断了", label.contains("断"))
    }

    /** 4/7 是没跟上，但说出来的只是两个数：不判罪、不吓唬 */
    @Test
    fun `没跟上时只报数不判罪`() {
        val week = weekCompliance(dates(0, 1, 2, 3), today)
        assertEquals(4, week.checkedDays)
        assertFalse(week.achieved)
        val label = weekComplianceLabel(week)
        assertEquals("近 7 天 4/7", label)
        listOf("断签", "清零", "失败", "没打", "没坚持", "可惜").forEach {
            assertFalse("「$label」出现了「$it」", label.contains(it))
        }
    }

    // ── 窗口边界（错了会静默偏差一天）────────────────────────────

    /** 窗口是"含今天的 7 个日历日"：第 6 天还在里面，第 7 天已经出去 */
    @Test
    fun `窗口含今天向前数七天`() {
        assertEquals(1, weekCompliance(dates(6), today).checkedDays)
        assertEquals(0, weekCompliance(dates(7), today).checkedDays)
    }

    /** 更早的打卡不进分子也不进分母 —— 否则"近 7 天达标率"名不副实 */
    @Test
    fun `窗口外的打卡不进计算`() {
        val week = weekCompliance((8..40).map { today.minusDays(it.toLong()) }.toSet(), today)
        assertEquals(7, week.windowDays)
        assertEquals(0, week.checkedDays)
        assertEquals(0, week.ratePercent)
        // 窗口内一天 + 窗口外三十天：只有窗口内那一天计入分子
        val mixed = weekCompliance(((8..40).map { today.minusDays(it.toLong()) } + today).toSet(), today)
        assertEquals(1, mixed.checkedDays)
    }

    /** 一条记录都没有：0/7 而不是崩溃，也不是"100% 达标" */
    @Test
    fun `空数据给零而不是假达标`() {
        val week = weekCompliance(emptySet(), today)
        assertEquals(0, week.checkedDays)
        assertEquals(0, week.ratePercent)
        assertFalse(week.achieved)
    }

    /** 同一天重复打卡（数量型一天多条）只算一天：集合语义，分母不膨胀 */
    @Test
    fun `同一天只算一天`() {
        val week = weekCompliance(dates(0), today)
        assertEquals(1, week.checkedDays)
    }

    // ── 达标率四舍五入 ───────────────────────────────────────────

    @Test
    fun `达标率四舍五入取整`() {
        assertEquals(57, weekCompliance(dates(0, 1, 2, 3), today).ratePercent)   // 4/7 = 57.14
        assertEquals(43, weekCompliance(dates(0, 1, 2), today).ratePercent)      // 3/7 = 42.86
        assertEquals(29, weekCompliance(dates(0, 1), today).ratePercent)         // 2/7 = 28.57
        assertEquals(100, weekCompliance(dates(0, 1, 2, 3, 4, 5, 6), today).ratePercent)
    }

    // ── 短窗口：习惯还没满一周时不许按 5/7 判它 ──────────────────

    /** 昨天的习惯第一天就被判"未达标"是凭空造坏消息，窗口先跟实际日子数走 */
    @Test
    fun `窗口长度跟习惯实际年龄走`() {
        assertEquals(1, weeklyWindowDays(startDate = today, today = today))
        assertEquals(3, weeklyWindowDays(startDate = today.minusDays(2), today = today))
        assertEquals(7, weeklyWindowDays(startDate = today.minusDays(6), today = today))
        // 满周之后封顶在 7
        assertEquals(7, weeklyWindowDays(startDate = today.minusDays(30), today = today))
        // 建库时间在未来（时钟漂移/补录）也不许算出 0 或负窗口
        assertEquals(1, weeklyWindowDays(startDate = today.plusDays(3), today = today))
    }

    /** 达标线随窗口等比缩放，但永远不许比窗口本身还高 */
    @Test
    fun `达标线不超过窗口天数`() {
        for (windowDays in 1..7) {
            val target = weekCompliance(emptySet(), today, windowDays = windowDays).targetDays
            assertTrue("窗口 $windowDays 天的达标线是 $target", target in 1..windowDays)
        }
        assertEquals(2, weekCompliance(emptySet(), today, windowDays = 3).targetDays)
    }

    /** 刚建的习惯当天：窗口 1 天、达标线 1 天，打了卡就当场算跟上 */
    @Test
    fun `新习惯第一天按一天窗口判`() {
        val windowDays = weeklyWindowDays(startDate = today, today = today)
        val checked = weekCompliance(dates(0), today, windowDays = windowDays)
        assertEquals(1, checked.windowDays)
        assertEquals(1, checked.targetDays)
        assertTrue(checked.achieved)
        assertEquals("近 1 天 1/1 达标", weekComplianceLabel(checked))
    }

    // ── 文案：不许声称本轮没有的东西 ─────────────────────────────

    @Test
    fun `达标那行的字面`() {
        assertEquals("近 7 天 5/7 达标", weekComplianceLabel(weekCompliance(dates(0, 1, 2, 3, 4), today)))
        assertEquals("近 7 天 7/7 达标", weekComplianceLabel(weekCompliance(dates(0, 1, 2, 3, 4, 5, 6), today)))
        assertEquals("近 7 天 0/7", weekComplianceLabel(weekCompliance(emptySet(), today)))
    }

    /**
     * 本轮**没有**"断签保护卡额度"的落库位置（那要加列，超出范围），
     * 所以这一行字里一句都不许暗示有额度、或漏掉的日子会被自动补上。
     */
    @Test
    fun `文案不许声称额度或自动补签`() {
        val labels = listOf(
            weekComplianceLabel(weekCompliance(dates(0, 1, 2, 3, 4), today)),
            weekComplianceLabel(weekCompliance(dates(0, 1), today)),
            weekComplianceLabel(weekCompliance(emptySet(), today)),
        )
        listOf("额度", "自动", "补签", "保护卡", "无限").forEach { banned ->
            labels.forEach { label ->
                assertFalse("「$label」声称了不存在的「$banned」", label.contains(banned))
            }
        }
    }

    /** 没有威胁与绝对化措辞（"再不…就…""必须""一定要"） */
    @Test
    fun `文案没有威胁与绝对化措辞`() {
        listOf(
            weekComplianceLabel(weekCompliance(dates(0, 1), today)),
            weekComplianceLabel(weekCompliance(emptySet(), today)),
        ).forEach { label ->
            listOf("再不", "就忘", "必须", "一定要", "前功尽弃").forEach { banned ->
                assertFalse("「$label」出现了「$banned」", label.contains(banned))
            }
        }
    }

    // ── 常量本身的口径：窗口 7 天、达标线 5 天 ────────────────────

    @Test
    fun `窗口与达标线常量就是文案里那两个数`() {
        assertEquals(7, WEEK_WINDOW_DAYS)
        assertEquals(5, WEEK_TARGET_DAYS)
        assertEquals(WEEK_TARGET_DAYS, weekCompliance(emptySet(), today).targetDays)
    }
}
