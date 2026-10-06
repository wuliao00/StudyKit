package com.studykit.ui.study

import com.studykit.data.AppSettings
import com.studykit.data.memory.CardState
import com.studykit.data.memory.FsrsModel
import com.studykit.data.memory.KernelRating
import com.studykit.data.memory.KernelState
import com.studykit.optimize.BacktestState
import com.studykit.optimize.FsrsModelCore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task 9 的 ⛔ 一票否决：单卡遗忘曲线的「到期标记」必须与**调度器自己排出来的间隔**逐值相等，
 * 而且**图上画的位置与文案报的数必须是同一个时间原点**（修复轮的 Important：以前点画在
 * 「距上次复习」的刻度上、话却说成「距今天」，两者差一整个 elapsed）。
 *
 * 真实调度入口是 `SchedulingKernel.nextIntervalDays`（FSRS 实现内部委托
 * [FsrsModel.nextIntervalDays]，非 AGAIN 档只用 [KernelState.stability]，夹取走
 * [FsrsModel.sanitizeStability] —— 修复轮把三处镜像收成这一份，本文件最后一例钉住它）。
 * 这里固定走 FSRS 内核、把三种「忆起系」评分（HARD/GOOD/EASY，调度函数对它们同一条支）
 * × 多档稳定度 × 多档目标保留率扫一遍，delta 用 0.0 —— 要求**比特级逐值相等**，不是"差不多"。
 * 稳定度一端扫到 40000.0（越过封顶 [FsrsModel.MAX_STABILITY]），扫的那条支才是"封顶"本身
 * （修以前只扫到 900，天花板从来没被碰到，KDoc 里那句"扫描会红"对封顶漂移其实是空话）。
 * AGAIN 档是调度器自己的 10 分钟专支，不在相等性主张内（有单独的说明用例钉住）。
 *
 * 另注：`CardRecallCurve.dueMarkerDays` 的排期上限现在是**必填**参数（修复轮 Minor 4：
 * 带默认值的可选参数让页面漏传政策上限也能悄悄绿过去），所以本文件所有调用点都显式写
 * [CardRecallCurve.DEFAULT_MAX_INTERVAL_DAYS] 或某个具体上限。
 */
class CardRecallCurveTest {

    private val w = FsrsModel.DEFAULT_PARAMS

    private fun reviewState(s: Double?) =
        KernelState(stability = s, difficulty = 5.0, cardState = CardState.REVIEW)

    // ── ⛔ 一致性硬闸 ────────────────────────────────────────────────

    @Test
    fun `到期标记与调度器给的间隔逐值相等`() {
        val kernel = KernelHub.forId("FSRS", AppSettings())
        // 40000.0 越过了 FsrsModel.MAX_STABILITY：这一档才真正扫到"封顶"那条支
        // （内核与曲线原先各拿一份 MAX_STABILITY，现在共用 FsrsModel 那一份，谁换回私有夹取就红）
        for (s in listOf(0.5, 1.0, 2.3, 7.0, 33.0, 120.0, 900.0, FsrsModel.MAX_STABILITY, 40000.0)) {
            for (r in listOf(0.80, 0.88, 0.90, 0.95)) {
                val curve = CardRecallCurve.dueMarkerDays(w, s, r, CardRecallCurve.DEFAULT_MAX_INTERVAL_DAYS)
                for (rating in listOf(KernelRating.HARD, KernelRating.GOOD, KernelRating.EASY)) {
                    val sched = kernel.nextIntervalDays(reviewState(s), rating, r, 3650.0)
                    assertEquals("S=$s R=$r rating=$rating", sched, curve, 0.0)
                }
            }
        }
    }

    @Test
    fun `到期标记与调度器在任意排期上限下逐值相等`() {
        val kernel = KernelHub.forId("FSRS", AppSettings())
        // 上限一端扫到 40000（比稳定度封顶还高）：只有把 cap 放开到那儿，S=40000 那档才真正
        // 把"封顶 36500"这条支暴露出来 —— 3650 的排期上限会把夹没夹顶的两个结果一起压成 3650，
        // 所以只加 S=40000、不加 40000 的 cap，扫描对封顶漂移仍然是绿的（修复轮踩到的坑）。
        for (cap in listOf(3.0, 21.0, 365.0, 3650.0, 40000.0)) {
            for (s in listOf(2.0, 40.0, 800.0, 40000.0)) {
                val curve = CardRecallCurve.dueMarkerDays(w, s, 0.9, maxIntervalDays = cap)
                val sched = kernel.nextIntervalDays(reviewState(s), KernelRating.GOOD, 0.9, cap)
                assertEquals("cap=$cap S=$s", sched, curve, 0.0)
            }
        }
        // 封顶本身在这条组合上确实咬住了（不是两边都放行）：漏了封顶这里会是 40000
        assertEquals(FsrsModel.MAX_STABILITY, CardRecallCurve.dueMarkerDays(w, 40000.0, 0.9, maxIntervalDays = 40000.0), 1e-6)
        // 脏上限（非有限）走 FsrsModel 的兜底保险丝：那条数是 MAX_INTERVAL_FALLBACK（365 天），
        // **不是** DEFAULT_MAX_INTERVAL_DAYS（3650）—— 拿 1200 天这种"本来就排得出去"的稳定度才看得出差别
        val curveNaN = CardRecallCurve.dueMarkerDays(w, 1200.0, 0.9, maxIntervalDays = Double.NaN)
        val schedNaN = kernel.nextIntervalDays(reviewState(1200.0), KernelRating.GOOD, 0.9, Double.NaN)
        assertEquals(schedNaN, curveNaN, 0.0)
        assertEquals(FsrsModel.MAX_INTERVAL_FALLBACK, curveNaN, 0.0)
        assertNotEquals(CardRecallCurve.DEFAULT_MAX_INTERVAL_DAYS, curveNaN, 0.0)
    }

    @Test
    fun `脏稳定度与未评分卡走同一夹取`() {
        val kernel = KernelHub.forId("FSRS", AppSettings())
        for (s in listOf(0.0, -4.0, Double.NaN)) {
            val sched = kernel.nextIntervalDays(reviewState(s), KernelRating.GOOD, 0.9, 3650.0)
            assertEquals("S=$s", sched, CardRecallCurve.dueMarkerDays(w, s, 0.9, 3650.0), 0.0)
        }
        // 新卡：内核拿到的是 stability=null，曲线侧还没有数 —— 两边都必须落到同一个
        // MIN_STABILITY_FLOOR 夹取，不许一边算出间隔、一边凭空造出另一个数
        val schedNull = kernel.nextIntervalDays(reviewState(null), KernelRating.GOOD, 0.9, 3650.0)
        assertEquals(schedNull, CardRecallCurve.dueMarkerDays(w, 0.0, 0.9, 3650.0), 0.0)
    }

    @Test
    fun `AGAIN 是调度器的十分钟专支不在相等性主张内`() {
        val kernel = KernelHub.forId("FSRS", AppSettings())
        val schedAgain = kernel.nextIntervalDays(reviewState(7.0), KernelRating.AGAIN, 0.9, 3650.0)
        assertEquals(FsrsModel.TEN_MINUTES_IN_DAYS, schedAgain, 0.0)
        assertNotEquals(schedAgain, CardRecallCurve.dueMarkerDays(w, 7.0, 0.9, 3650.0), 0.0)
    }

    // ── 曲线几何 ────────────────────────────────────────────────────

    @Test
    fun `曲线在到期标记处正好等于目标保留率`() {
        for (r in listOf(0.80, 0.88, 0.90, 0.95)) {
            val t = CardRecallCurve.dueMarkerDays(w, 10.0, r, 3650.0)
            assertEquals(r, FsrsModel.recall(w, 10.0, t), 1e-6)
        }
    }

    @Test
    fun `曲线单调不增且逐日从第1天排起`() {
        val pts = CardRecallCurve.points(w, 10.0, fromDay = 1, toDay = 40)
        assertEquals(40, pts.size)
        assertEquals(1.0, pts.first().first, 0.0)
        assertEquals(40.0, pts.last().first, 0.0)
        assertTrue(pts.zipWithNext().all { (a, b) -> a.second >= b.second })
        assertTrue(pts.all { (_, r) -> r > 0.0 && r <= 1.0 })
    }

    @Test
    fun `零稳定度与空天数区间退化为空曲线不抛`() {
        assertTrue(CardRecallCurve.points(w, 0.0, fromDay = 1, toDay = 10).isNotEmpty())
        assertTrue(CardRecallCurve.points(w, 10.0, fromDay = 1, toDay = 0).isEmpty())
        assertTrue(CardRecallCurve.points(w, 10.0, fromDay = 5, toDay = 4).isEmpty())
        val marker = CardRecallCurve.dueMarkerDays(w, 0.0, 0.9, 3650.0)
        assertTrue(marker.isFinite() && marker >= 0.0)
    }

    // ── 摆位：图与话同一个时间原点（修复轮的 Important）────────────────

    @Test
    fun `到期标记与今天线同源 文案报的数就是画在图上的位置`() {
        val cap = CardRecallCurve.DEFAULT_MAX_INTERVAL_DAYS
        for (s in listOf(1.0, 3.7, 10.0, 60.0)) {
            for (elapsed in listOf(0.4, 3.0, 12.5)) {
                val marker = CardRecallCurve.dueMarkerDays(w, s, 0.9, cap)
                val p = CardRecallCurve.placement(w, s, 0.9, elapsed, cap)
                assertNotNull("干净的输入却画不出（S=$s elapsed=$elapsed）", p)
                requireNotNull(p)
                // ⛔ 横轴原点 = 文案原点 = 今天：标记落在"调度器那个间隔里还没走完的那一段"上，
                // 而不是间隔本身（修复前就是这两个数被混用，点与话差一整个 elapsed）
                assertEquals("S=$s elapsed=$elapsed", marker - elapsed, p.markerDaysFromToday, 1e-9)
                val cross = crossU(p, 0.9)
                if (p.markerIsFuture) {
                    assertFalse("交点在今天右边，折线却没穿过目标线（S=$s）", cross.isNaN())
                    assertEquals(
                        "画在图上的交点与文案分家（S=$s elapsed=$elapsed）",
                        p.markerDaysFromToday, cross, 0.2,
                    )
                } else {
                    assertTrue("已经逾期的卡，折线却还在今天右边压到目标线（S=$s elapsed=$elapsed）", cross.isNaN())
                }
            }
        }
        // 文案里那个数必须是"距今天"，不是"距上次复习"：上次复习后第 10 天到期、今天已是第 3 天 → 说 7 天
        val threeDaysIn = CardRecallCurve.placement(w, 10.0, 0.9, 3.0, cap)!!
        assertEquals(7.0, threeDaysIn.markerDaysFromToday, 1e-9)
        assertEquals("7 天后就掉到目标线", dropPhrase(threeDaysIn))
        assertFalse(dropPhrase(threeDaysIn).contains("10 天后"))
        // 逾期：说"几天前就掉破了"，并且图上不画那颗点（把过去的交点夹到左缘就是画假点）
        val overdue = CardRecallCurve.placement(w, 10.0, 0.9, 13.0, cap)!!
        assertEquals(-3.0, overdue.markerDaysFromToday, 1e-9)
        assertFalse(overdue.markerIsFuture)
        assertEquals("3 天前就掉破了目标线", dropPhrase(overdue))
    }

    @Test
    fun `正好今天到期的卡 标记不落在今天线左边`() {
        val cap = CardRecallCurve.DEFAULT_MAX_INTERVAL_DAYS
        for (s in listOf(1.0, 3.7, 10.0, 60.0, 900.0)) {
            val marker = CardRecallCurve.dueMarkerDays(w, s, 0.9, cap)
            val p = CardRecallCurve.placement(w, s, 0.9, marker, cap)
            requireNotNull(p)
            // 今天线就是横轴的 0（图的最左缘）：到期日就是今天 → 标记必须落在 0 上，不许偏左
            assertEquals("S=$s", 0.0, p.markerDaysFromToday, 1e-9)
            assertTrue("今天到期却被判成逾期、点被抹掉（S=$s）", p.markerIsFuture)
            // 曲线起点（今天那一格）正好压在目标线上：图与数各说一套就红
            assertEquals(0.9, p.todayRecall, 1e-6)
        }
        // 还差一天没到期 → 标记必须落在今天右边（修复前它落在"距上次复习"的刻度上，看着像还早得多）
        val almostDue = CardRecallCurve.placement(w, 10.0, 0.9, 9.0, cap)
        assertNotNull(almostDue)
        requireNotNull(almostDue)
        assertTrue("还有一天才到期，标记却跑到今天左边", almostDue.markerDaysFromToday >= 1.0 - 1e-9)
        assertTrue(almostDue.markerIsFuture)
    }

    @Test
    fun `脏读数走空态而不是在 Canvas 里崩`() {
        val cap = CardRecallCurve.DEFAULT_MAX_INTERVAL_DAYS
        // 脏稳定度（含 NaN —— 修复前它把窗口算成 0 天、采样表算成空表，Canvas 里 points.last() 抛异常）
        for (bad in listOf(Double.NaN, 0.0, -4.0, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertNull("S=$bad 本该走空态", CardRecallCurve.placement(w, bad, 0.9, 3.0, cap))
        }
        // 脏/荒谬的"距上次复习"
        for (bad in listOf(Double.NaN, -1.0, Double.POSITIVE_INFINITY, CardRecallCurve.MAX_ELAPSED_DAYS + 1.0)) {
            assertNull("elapsed=$bad 本该走空态", CardRecallCurve.placement(w, 10.0, 0.9, bad, cap))
        }
        // 脏参数向量：factor/decay 都是 NaN → 标记非有限 → 以前正是这条路崩在绘制里
        val dirtyW = DoubleArray(FsrsModel.NP) { Double.NaN }
        assertTrue(CardRecallCurve.dueMarkerDays(dirtyW, 10.0, 0.9, cap).isNaN())
        assertNull(CardRecallCurve.placement(dirtyW, 10.0, 0.9, 3.0, cap))
        // 任何"能画"的摆位都必须真画得出来：窗口非零、点非空（points.last() 不再有机会抛）
        for (s in listOf(0.001, 10.0, FsrsModel.MAX_STABILITY)) {
            for (elapsed in listOf(0.0, 3.0, 4000.0)) {
                val p = CardRecallCurve.placement(w, s, 0.9, elapsed, cap)
                requireNotNull(p)
                assertTrue(
                    "S=$s elapsed=$elapsed",
                    p.windowDays in CardRecallCurve.MIN_WINDOW_DAYS.toInt()..CardRecallCurve.MAX_WINDOW_DAYS.toInt(),
                )
                assertTrue("S=$s elapsed=$elapsed 采样表为空，Canvas 里就是崩溃", p.points.isNotEmpty())
                assertTrue(p.points.all { it.second.isFinite() })
                assertTrue(p.todayRecall.isFinite())
            }
        }
        // 交点被封顶贴到右缘时，可见行仍报真实数字（730 是轴的长度，不是那个数）
        val huge = CardRecallCurve.placement(w, FsrsModel.MAX_STABILITY, 0.9, 0.0, cap)!!
        assertTrue(huge.markerDaysFromToday > huge.windowDays.toDouble())
        assertEquals("3650 天后就掉到目标线", dropPhrase(huge))
    }

    // ── 夹取：全仓一份（修复轮 Minor 2）──────────────────────────────

    @Test
    fun `稳定度夹取全仓只有一份`() {
        val cap = CardRecallCurve.DEFAULT_MAX_INTERVAL_DAYS
        val kernel = KernelHub.forId("FSRS", AppSettings())
        val cases: List<Pair<Double?, Double>> = listOf(
            null to FsrsModel.MIN_STABILITY_FLOOR,
            0.0 to FsrsModel.MIN_STABILITY_FLOOR,
            -4.0 to FsrsModel.MIN_STABILITY_FLOOR,
            Double.NaN to FsrsModel.MIN_STABILITY_FLOOR,
            Double.POSITIVE_INFINITY to FsrsModel.MIN_STABILITY_FLOOR, // 非有限一律走地板，不是封顶
            2.5 to 2.5,
            FsrsModel.MAX_STABILITY to FsrsModel.MAX_STABILITY,
            40000.0 to FsrsModel.MAX_STABILITY,
            1e300 to FsrsModel.MAX_STABILITY,
        )
        for ((raw, expected) in cases) {
            assertEquals("事实源 S=$raw", expected, FsrsModel.sanitizeStability(raw), 0.0)
            assertEquals("曲线侧 S=$raw", expected, CardRecallCurve.sanitizeStability(raw), 0.0)
            // 内核与回测那两处的夹取是私有的，所以从**它对外的读数**验：R(1 天) 对 S 严格单调，
            // 读数与"直接用期望值算"逐比特相等 ⟺ 那一处夹的就是同一个值
            val expectedRecall = FsrsModel.recall(w, expected, 1.0)
            assertEquals("内核侧 S=$raw", expectedRecall, kernel.recall(reviewState(raw), 1.0), 0.0)
            assertEquals(
                "回测侧 S=$raw", expectedRecall,
                FsrsModelCore.recall(w, BacktestState(raw ?: 0.0, 5.0), 1.0), 0.0,
            )
            val expectedInterval = FsrsModel.nextIntervalDays(w, expected, 0.9, cap)
            assertEquals(
                "内核间隔 S=$raw", expectedInterval,
                kernel.nextIntervalDays(reviewState(raw), KernelRating.GOOD, 0.9, cap), 0.0,
            )
            assertEquals(
                "曲线间隔 S=$raw", expectedInterval,
                CardRecallCurve.dueMarkerDays(w, raw ?: 0.0, 0.9, cap), 0.0,
            )
        }
    }

    /**
     * 画出来的那条折线（顶点 = 今天那一格 + [RecallCurvePlacement.points]）与目标保留率线相交处的
     * 横坐标（线性插值）；整条线都在目标线下方（已逾期）时返回 NaN。
     * 用它来钉"点画在哪"与"话怎么说"是不是同一个原点 —— 这两个量本来就该重合。
     */
    private fun crossU(p: RecallCurvePlacement, target: Double): Double {
        val line = ArrayList<Pair<Double, Double>>(p.points.size + 1)
        line += 0.0 to p.todayRecall
        line += p.points
        for (i in 1 until line.size) {
            val (u0, r0) = line[i - 1]
            val (u1, r1) = line[i]
            if (r0 >= target - 1e-9 && r1 <= target + 1e-9) {
                val drop = r0 - r1
                return if (drop == 0.0) u1 else u0 + (r0 - target) / drop * (u1 - u0)
            }
        }
        return Double.NaN
    }
}
