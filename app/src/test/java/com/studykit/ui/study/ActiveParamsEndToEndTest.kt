package com.studykit.ui.study

import com.studykit.data.AppSettings
import com.studykit.data.dao.ScheduledMemoryRow
import com.studykit.data.entity.Mistake
import com.studykit.data.entity.Word
import com.studykit.data.memory.FSRS_HALF_OVER_S
import com.studykit.data.memory.FsrsKernel
import com.studykit.data.memory.FsrsModel
import com.studykit.data.memory.ReviewGrade
import com.studykit.data.memory.Scheduling
import com.studykit.data.memory.SchedulingKernel
import com.studykit.data.memory.kernelStateFor
import com.studykit.data.memory.toKernelRating
import com.studykit.ui.mistake.MistakeScheduling
import com.studykit.worker.dueRetentionPercent
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * v2.9「采纳的 21 维参数回流调度器」这句话的**多路**端到端守卫（v3.0-A T2.6）。
 *
 * 与 [GradePreviewCalibrationRenderTest] 的分工：那一头在真组合的屏上钉「显示 ≡ 实排」，
 * 这一头把同一条性质推到**其余几条当时也被漏掉的路**上。四路都从 [KernelHub.forId] 的双参口径取核，
 * 喂同一份卡状态：
 *  ① 背词评分（`StudyViewModel.gradeCard` 的内核段：`kernelStateFor → review → nextIntervalDays`）；
 *  ② 错题重做评分（生产纯函数 [MistakeScheduling.grade]，写回 `mistakes.review_at`）；
 *  ③ 复习提醒通知里那句「现在大约还记得 X%」（生产纯函数 [dueRetentionPercent]）；
 *  ④ 首页「明天预计复习 N 词 · 到时候大约还记得 X%」（生产纯函数 [TomorrowForecast.of]）。
 *
 * 修复前这四路的调用点全走**单参** `forId(id)`，或直接吃 `KernelHub.forId(null)` 那个默认值
 * ——也就是全都拿发布默认参数。这一组用例说的就是：换了那份向量，这四处的数必须一起跟着换。
 *
 * **这一组用例的射程要说清楚**：它钉的是「把那份设置交给 hub，这四路的数会不会跟着换」，
 * 走的是纯函数边界，所以**它自己不检验「某个调用点有没有把手上那份设置交出来」**——
 * 那一步由编译器负责（单参重载已删除，交不出设置就编译不过），评分按钮那一侧另有
 * [GradePreviewCalibrationRenderTest] 在真组合的屏上验。剩下的唯一缺口是「调用点明知拿得到设置
 * 却故意传 null」，机检不到，只能靠 [KernelHub] 类注释与调用点那行的 KDoc 说清 `null` 的读法。
 *
 * 全程不碰 FSRS 算术本身（那由 `FsrsModelParityTest` 挂 py-fsrs 6.3.2 预言机钉），
 * 只比对「默认参数 vs 活跃参数」两组输出确实分岔，以及每一路的数出自同一个内核。
 */
class ActiveParamsEndToEndTest {

    private val dayMs = TimeUnit.DAYS.toMillis(1)
    private val now = 1_760_000_000_000L

    /**
     * 两个数都是**避开陷阱**选的（与 [GradePreviewCalibrationRenderTest] 同一份夹具口径）：
     *  - `0.85`：`targetRecall = 0.9` 时 `nextIntervalDays` 的 `factor` 与 `dr` 项相消，
     *    间隔恒等于稳定度、与所有参数无关（[ParamInjectionTest] 类注释记过这条）；
     *  - `365.0`：`MemoryScheduler` 没填考试日时上限只有 36 天，两档会被夹成同一个数，
     *    差异被夹平，断言就成了空转。
     */
    private val sched = Scheduling(targetRecall = 0.85, maxIntervalDays = 365.0)

    private val altered: DoubleArray =
        FsrsModel.DEFAULT_PARAMS.copyOf().also { it[20] = 0.30; it[0] = 0.05; it[2] = 5.0 }

    private fun calibrated() = AppSettings(fsrsActiveParams = altered)
    private fun uncalibrated() = AppSettings(fsrsActiveParams = null)

    /** ① 背词侧：与 `gradeCard` 逐字同序的那条通路 */
    private fun wordDays(kernel: SchedulingKernel, word: Word, grade: ReviewGrade): Double {
        val rating = grade.toKernelRating()
        val gapDays = (now - word.lastReviewAt!!) / dayMs.toDouble()
        return kernel.nextIntervalDays(
            kernel.review(kernelStateFor(kernel, word), gapDays, rating, null),
            rating,
            sched.targetRecall,
            sched.maxIntervalDays,
        )
    }

    /** 8 天稳定度、90 天没碰：保留率落在中间地带，两档参数才差得出来（贴近 1 或贴近 0 都会被舍入抹平） */
    private fun word(): Word = Word(
        uuid = "w-e2e",
        word = "ambivalent",
        meaning = "矛盾的",
        example = "",
        halfLifeDays = 8.0 * FSRS_HALF_OVER_S,
        difficulty = 3.0,
        lastReviewAt = now - 90 * dayMs,
        createdAt = now - 200 * dayMs,
        fsrsStability = 8.0,
        fsrsDifficulty = 5.0,
        fsrsState = 2,
        kernel = "FSRS",
    )

    /** 从没被算法排过期的错题（`reviewAt = null`）：`MistakeScheduling` 因此按「距入本 90 天」喂内核 */
    private fun mistake(): Mistake = Mistake(
        id = 7L,
        uuid = "m-e2e",
        source = Mistake.SOURCE_PRACTICE,
        subject = "数学",
        title = "题",
        content = "题干",
        reviewAt = null,
        mastered = false,
        createdAt = now - 90 * dayMs,
        fsrsStability = 8.0,
        fsrsDifficulty = 5.0,
        fsrsState = 2,
    )

    // ── ① 背词评分 ────────────────────────────────────────────────

    @Test
    fun `背词评分的间隔随活跃参数改变`() {
        val w = word()
        // AGAIN（「忘记」）不在断言内：FSRS 那一支排的是当日再见（`TEN_MINUTES_IN_DAYS` 常量），
        // 本来就与参数向量无关（`CardRecallCurve` 类注释的边界①把这条写在「不许逐值一致」之外）。
        listOf(ReviewGrade.VAGUE, ReviewGrade.RECALL).forEach { grade ->
            assertNotEquals(
                "$grade 档必须按活跃参数排（默认参数那一档的数不该原样留着）",
                wordDays(KernelHub.forId("FSRS", uncalibrated()), w, grade),
                wordDays(KernelHub.forId("FSRS", calibrated()), w, grade),
                1e-9,
            )
        }
    }

    /** 没采纳过的用户逐比特不变——这条是「注入不改变首装行为」的证人，与 [ParamInjectionTest] 同一口径 */
    @Test
    fun `没采纳过时长得和发布默认参数一模一样`() {
        val w = word()
        ReviewGrade.entries.forEach { grade ->
            assertEquals(
                "$grade 档：未采纳时不许动排期",
                wordDays(FsrsKernel(), w, grade),
                wordDays(KernelHub.forId("FSRS", uncalibrated()), w, grade),
                0.0,
            )
        }
    }

    // ── ② 错题重做评分（生产纯函数） ──────────────────────────────

    @Test
    fun `错题重做排期随活跃参数改变`() {
        val m = mistake()
        val dueDefault = MistakeScheduling.grade(
            mistake = m, grade = ReviewGrade.RECALL, conf = null,
            kernel = KernelHub.forId("FSRS", uncalibrated()), sched = sched, now = now,
        )
        val dueCalibrated = MistakeScheduling.grade(
            mistake = m, grade = ReviewGrade.RECALL, conf = null,
            kernel = KernelHub.forId("FSRS", calibrated()), sched = sched, now = now,
        )
        assertNotEquals(
            "错题的 review_at 必须跟着活跃参数走（否则词与错题两套记忆两张皮）",
            dueDefault.reviewAt,
            dueCalibrated.reviewAt,
        )
        // 落库的到期时刻就是**同一个内核**排出的那个间隔：错题侧的「显示 ≡ 实排」证人
        val kernel = KernelHub.forId("FSRS", calibrated())
        val rating = ReviewGrade.RECALL.toKernelRating()
        val expectedDays = kernel.nextIntervalDays(
            kernel.review(kernelStateFor(kernel, m), 90.0, rating, null),
            rating,
            sched.targetRecall,
            sched.maxIntervalDays,
        )
        assertEquals(now + (expectedDays * dayMs).toLong(), dueCalibrated.reviewAt)
    }

    // ── ③ 通知正文的保留率（生产纯函数） ──────────────────────────

    @Test
    fun `通知里那句还记得多少随活跃参数改变`() {
        val list = listOf(word())
        assertNotEquals(
            "通知的保留率必须读活跃参数那条曲线",
            dueRetentionPercent(list, now, KernelHub.forId("FSRS", uncalibrated())),
            dueRetentionPercent(list, now, KernelHub.forId("FSRS", calibrated())),
        )
    }

    // ── ④ 首页明日那一格（生产纯函数） ────────────────────────────

    @Test
    fun `首页明日保留率随活跃参数改变`() {
        val utc = ZoneOffset.UTC
        val today = LocalDate.of(2026, 9, 29)
        val dueAt = today.plusDays(1).atStartOfDay(utc).toInstant().toEpochMilli() + dayMs / 2
        val rows = listOf(
            ScheduledMemoryRow(
                nextReviewAt = dueAt,
                halfLifeDays = 8.0 * FSRS_HALF_OVER_S,
                lastReviewAt = dueAt - 90 * dayMs,
                createdAt = dueAt - 200 * dayMs,
                fsrsStability = 8.0,
            ),
        )
        val window = TomorrowForecast.window(utc, today)
        val viaDefault = TomorrowForecast.of(rows, window, KernelHub.forId("FSRS", uncalibrated()))
        val viaCalibrated = TomorrowForecast.of(rows, window, KernelHub.forId("FSRS", calibrated()))
        assertEquals(1, viaDefault.wordCount)
        assertEquals(viaDefault.wordCount, viaCalibrated.wordCount)
        assertEquals(viaCalibrated.wordCount, viaCalibrated.sampledCount)
        assertNotEquals(
            "首页那一格的百分比必须读活跃参数那条曲线",
            viaDefault.predictedRecallPercent,
            viaCalibrated.predictedRecallPercent,
        )
    }

    // ── 边界：null 设置、半衰期内核、坏 id ─────────────────────────

    /**
     * `settings = null` 是诚实的「还没读到设置」，退回发布默认参数；
     * 它不等于「用户没采纳」，也不许被当成绕开校准的出口（调用点必须把手上那份设置传进来）。
     */
    @Test
    fun `设置没读到时回默认参数`() {
        val w = word()
        assertEquals(
            wordDays(FsrsKernel(), w, ReviewGrade.RECALL),
            wordDays(KernelHub.forId("FSRS", null), w, ReviewGrade.RECALL),
            0.0,
        )
    }

    /** 半衰期没有 FSRS 参数维：活跃参数对这一支一律无效（与 [ParamInjectionTest] 同结论） */
    @Test
    fun `半衰期内核不受活跃参数影响`() {
        val w = word()
        val plain = KernelHub.forId("HALF_LIFE", uncalibrated())
        val withParams = KernelHub.forId("HALF_LIFE", calibrated())
        assertEquals("HALF_LIFE", withParams.id)
        assertEquals(
            wordDays(plain, w, ReviewGrade.RECALL),
            wordDays(withParams, w, ReviewGrade.RECALL),
            0.0,
        )
    }

    /** 坏 id 回 FSRS 分支（与 `AppSettings.fromMap` 的白名单同一口径），带活跃参数时走的仍是校准后的核 */
    @Test
    fun `坏内核id回默认分支而不是抛`() {
        val kernel = KernelHub.forId("GARBAGE", calibrated())
        val w = word()
        assertEquals("FSRS", kernel.id)
        assertEquals(
            "垃圾 id 也带着活跃参数走 FSRS 分支",
            wordDays(KernelHub.forId("FSRS", calibrated()), w, ReviewGrade.RECALL),
            wordDays(kernel, w, ReviewGrade.RECALL),
            0.0,
        )
    }
}
