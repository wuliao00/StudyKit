package com.studykit.ui.study

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import com.studykit.data.AppSettings
import com.studykit.data.GlassLevel
import com.studykit.data.entity.Word
import com.studykit.data.memory.FSRS_HALF_OVER_S
import com.studykit.data.memory.FsrsKernel
import com.studykit.data.memory.FsrsModel
import com.studykit.data.memory.ReviewGrade
import com.studykit.data.memory.Scheduling
import com.studykit.data.memory.SchedulingKernel
import com.studykit.data.memory.kernelStateFor
import com.studykit.data.memory.recallForDisplay
import com.studykit.data.memory.toKernelRating
import com.studykit.ui.theme.AppThemeVals
import com.studykit.ui.theme.LightColors
import com.studykit.ui.theme.LocalAppTheme
import com.studykit.ui.theme.buildAppTexts
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 「采纳过的校准参数到底有没有走到评分按钮上」的**渲染层**端到端守卫（v3.0-A T2.6）。
 *
 * ## 为什么是这一屏
 * v2.9 的招牌承诺（`CHANGELOG.md` [2.9.0]：「训练参数真正回流到调度器」）说的是用户采纳那 21 维
 * 向量之后，**排期与显示都按它算**。而 [KernelHub] 的类注释把「按钮上印的间隔」与「点下去真排出的
 * 间隔」不许分家写成了一票否决级约定。这两件事只能在**真组合一次**的屏上同时验：
 * 纯函数层测「给定内核 → 排出几天」，测不到「页面拿的是哪一份内核」。
 *
 * ## 写出来时的红
 * 这一组用例对着**修复前**的接线跑是红的（见 task 报告的 red 运行）：[GradeRow] 当时取的是
 * `KernelHub.forId(AppTheme.settings.schedulingKernel)` —— **单参**那条重载只认 id、
 * 不看 `fsrsActiveParams`，于是按钮印的是发布默认参数的间隔，而 `StudyViewModel.gradeCard`
 * 那侧（同样漏了设置）也按默认排期：采纳过参数的用户看到的是「按钮一个数、库里另一个数」，
 * 而两个数其实都来自同一份**默认**核。删掉单参重载、全仓调用点补上设置之后这里转绿。
 *
 * ## 期望值为什么不写死数字
 * 期望值取 [KernelHub.forId] 的双参口径 + 与 `gradeCard` 逐字同序的那条算术
 * （`kernelStateFor → review → nextIntervalDays`），钉的是「显示 ≡ 实排」这条同源关系，
 * 不是某个 FSRS 常量的当前值（那由 `FsrsModelParityTest` 挂 py-fsrs 6.3.2 预言机钉）。
 *
 * ## 时钟锚点（T17 收掉的那条脆断言）
 * 本文件此前在四处各读一次 `System.currentTimeMillis()`（夹具的 `lastReviewAt`、`GradeRow` 内部、
 * 期望值里的 `elapsedDaysOf`、以及那条 `delta = 0.0` 的逐值比对两侧）。两个样本之间那点毫秒差
 * 会顺着 FSRS 的算术挪到显示舍入的边界（`formatIntervalLabel` 的 `.5` 档）与 `delta = 0.0` 这一档上，
 * 于是**全量首跑偶发红、同一条命令二跑绿**（v3.0-B ledger 记在 Task 6+7 那条）。
 * 现在整条链只有一个锚点 [ANCHOR_MS]：夹具按它铺、屏幕经 `GradeRow(nowMs =)` 读它、期望值经
 * `elapsedDaysOf(word, ANCHOR_MS)` 读它 ⇒ 毫秒边界这个变量从算式里消失。
 * **容差仍是 0.0**，这不是放宽而是收紧：两侧现在只有"参数不同"这一条能造成差异，
 * 时钟那一条被锚点物理排除，`delta = 0.0` 才真的在比算术而不是比两次钟。
 * 性质一分没动：断言的仍是「按钮上印的那个标签 == 评分器那条算术排出的天数格式化出来的标签」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GradePreviewCalibrationRenderTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val dayMs = TimeUnit.DAYS.toMillis(1)

    /**
     * 测试用的排期政策。两个数都是**避开陷阱**选的，不是随手挑的：
     *  - `targetRecall = 0.85`：0.9 会让 `nextIntervalDays` 里的 `factor` 与 `dr` 项相消、
     *    间隔恒等于稳定度而与全部参数无关（[ParamInjectionTest] 类注释记过这条）；
     *  - `maxIntervalDays = 365.0`：`MemoryScheduler` 没填考试日时上限只有 36 天，
     *    两档都会被夹到同一个 36 —— 夹住之后差异被抹平，断言就成了空转。
     */
    private val sched = Scheduling(targetRecall = 0.85, maxIntervalDays = 365.0)

    /** 三档按钮的可见标签，顺序与 [GradeRow] 上那一排一致 */
    private val labels = mapOf(
        ReviewGrade.RECALL to "认识",
        ReviewGrade.VAGUE to "模糊",
        ReviewGrade.FORGET to "忘记",
    )

    /** FSRS 已原生写过的行；8 天稳定度配 90 天没碰，保留率落在中间地带，两个参数档才差得出来 */
    private fun word(): Word {
        // 锚点 ANCHOR_MS 而不是真实钟：这一行的 `lastReviewAt` 与屏幕/期望值那份"已经拖了多久"
        // 必须出自同一个 now，否则差几毫秒就够把天数推到舍入边界上（见类注释那段）
        return Word(
            uuid = "calib-1",
            word = "ambivalent",
            meaning = "矛盾的",
            example = "",
            halfLifeDays = 8.0 * FSRS_HALF_OVER_S,
            difficulty = 3.0,
            lastReviewAt = ANCHOR_MS - 90 * dayMs,
            createdAt = ANCHOR_MS - 200 * dayMs,
            fsrsStability = 8.0,
            fsrsDifficulty = 5.0,
            fsrsState = 2,
            kernel = "FSRS",
        )
    }

    /**
     * 「点下去真排出去的那个数」：与 `StudyViewModel.gradeCard` 的内核段逐字同序，
     * 内核由调用方经 [KernelHub.forId] 的**双参**口径给（含活跃参数），
     * 时间锚点与屏幕那一份同一个（[ANCHOR_MS]，见类注释）。
     */
    private fun scheduledDays(kernel: SchedulingKernel, word: Word, grade: ReviewGrade): Double {
        val rating = grade.toKernelRating()
        return kernel.nextIntervalDays(
            kernel.review(kernelStateFor(kernel, word), elapsedDaysOf(word, ANCHOR_MS), rating, conf = null),
            rating,
            sched.targetRecall,
            sched.maxIntervalDays,
        )
    }

    private fun renderPreview(settings: AppSettings, word: Word) {
        composeRule.setContent {
            StudyHost(settings) {
                GradeRow(
                    word = word,
                    scheduling = sched,
                    enabled = true,
                    onGrade = {},
                    // 屏幕与期望值读同一个钟：这一行是那条脆断言的正身（见类注释那段）
                    nowMs = ANCHOR_MS,
                )
            }
        }
        composeRule.waitForIdle()
    }

    /**
     * 那一排里 `[标签, 间隔]` 合并成一个语义节点，所以按「同一颗按钮上既有档位名又有间隔文本」找，
     * 而不是只按间隔文本找：两档排出同一个数时（旧接线下真的发生过）只按文本找会撞出两个节点。
     */
    private fun assertButtonShows(label: String, interval: String) {
        composeRule.onNode(hasText(label) and hasText(interval)).assertExists()
    }

    @Test
    fun `校准参数进了评分按钮_印的就是校准内核排出的间隔`() {
        val word = word()
        val calibrated = AppSettings(
            glass = GlassLevel.OFF,
            reduceMotion = true,
            fsrsActiveParams = alteredParams(),
        )
        val kernel = KernelHub.forId(calibrated.schedulingKernel, calibrated)

        renderPreview(calibrated, word)

        ReviewGrade.entries.forEach { grade ->
            assertButtonShows(labels.getValue(grade), formatIntervalLabel(scheduledDays(kernel, word, grade)))
        }
        // 「现在按下去，预计还记得 X%」那一行也必须读校准后的曲线（舍入口径与 GradeRow 同）
        val percent = (
            recallForDisplay(kernel, kernelStateFor(kernel, word), elapsedDaysOf(word, ANCHOR_MS)) * 100
            ).roundToInt()
        composeRule.onNode(hasText("现在按下去，预计还记得 $percent%")).assertExists()
    }

    @Test
    fun `没校准过的用户按钮仍是发布默认参数`() {
        val word = word()
        val untouched = AppSettings(glass = GlassLevel.OFF, reduceMotion = true)
        val kernel = KernelHub.forId(untouched.schedulingKernel, untouched)

        renderPreview(untouched, word)

        ReviewGrade.entries.forEach { grade ->
            assertButtonShows(labels.getValue(grade), formatIntervalLabel(scheduledDays(kernel, word, grade)))
            // 这份设置下拿到的就必须是发布默认参数核——「首装行为逐比特一致」由这一行证
            assertEquals(
                "$grade 档：没采纳过就不许动排期",
                scheduledDays(FsrsKernel(), word, grade),
                scheduledDays(kernel, word, grade),
                0.0,
            )
        }
    }

    /**
     * 夹具自检：那份「已采纳」向量确实把间隔挪开了默认参数那一档。
     *
     * 没有这一条，上面两条可能都是「两种参数本来就排出同一个数」的假绿。
     * 「忘记」档刻意**不在**自检里：FSRS 对 AGAIN 排的是当日再见
     * （`FsrsKernel.TEN_MINUTES_IN_DAYS` 常量，`CardRecallCurve` 类注释的边界①），本来就与参数无关。
     */
    @Test
    fun `夹具自检_校准向量与默认参数排出的间隔确实不同`() {
        val word = word()
        val defaultKernel = KernelHub.forId("FSRS", AppSettings(fsrsActiveParams = null))
        val calibratedKernel = KernelHub.forId("FSRS", AppSettings(fsrsActiveParams = alteredParams()))
        listOf(ReviewGrade.VAGUE, ReviewGrade.RECALL).forEach { grade ->
            val before = scheduledDays(defaultKernel, word, grade)
            val after = scheduledDays(calibratedKernel, word, grade)
            assertNotEquals(
                "$grade 档的间隔必须随活跃参数变化（否则本文件其余断言是空转）",
                before,
                after,
                1e-9,
            )
            // 还要差得能被"天"这一档读出来：夹在同一个整数里，用户与这条断言都看不见
            assertNotEquals(
                "$grade 档的差异必须跨过显示舍入（否则按钮上的字根本不会变）",
                formatIntervalLabel(before),
                formatIntervalLabel(after),
            )
        }
    }

    /** 半衰期没有 FSRS 参数维：切到 HALF_LIFE 时活跃参数一律不参与，按钮仍按半衰期内核排 */
    @Test
    fun `半衰期内核活跃时校准参数不改按钮`() {
        val word = word()
        val halfLifeSettings = AppSettings(
            glass = GlassLevel.OFF,
            reduceMotion = true,
            schedulingKernel = "HALF_LIFE",
            fsrsActiveParams = alteredParams(),
        )
        val kernel = KernelHub.forId(halfLifeSettings.schedulingKernel, halfLifeSettings)
        assertEquals("HALF_LIFE", kernel.id)

        renderPreview(halfLifeSettings, word)

        ReviewGrade.entries.forEach { grade ->
            assertButtonShows(labels.getValue(grade), formatIntervalLabel(scheduledDays(kernel, word, grade)))
        }
    }
}

/**
 * 整条链路唯一的钟读锚点。是**常量**而不是 `System.currentTimeMillis()`：夹具、屏幕
 * （`GradeRow(nowMs =)`）、期望值（`elapsedDaysOf(word, ANCHOR_MS)`）三处读的都是这一个数，
 * 于是"两次钟读之间那点毫秒差"这个变量从算式里彻底消失，而这份期望值也不再随机器日期漂移。
 * 具体是哪一天不参与任何算术（内核侧不读钟，`elapsedDaysOf` 只看差值），这里就当它是"今天"。
 */
private const val ANCHOR_MS = 1_780_000_000_000L

/**
 * 一份与 [FsrsModel.DEFAULT_PARAMS] 可测地不同的「已采纳」向量：
 * 动 w[20]（decay，`recall` 与 `nextIntervalDays` 都读它）与 w[0]/w[2]（首评稳定度），
 * 与 [ParamInjectionTest] 用的那一组同值，免得两处夹具各说一套。
 */
private fun alteredParams(): DoubleArray =
    FsrsModel.DEFAULT_PARAMS.copyOf().also { it[20] = 0.30; it[0] = 0.05; it[2] = 5.0 }

/**
 * 主题 host：与 [com.studykit.ui.stats.LoadBandRenderTest] 的 `StatsHost` 同一份写法
 * （调用方传的 settings 已带 `glass = OFF` / `reduceMotion = true`，让 `waitForIdle()` 等得到）。
 */
@Composable
private fun StudyHost(settings: AppSettings, content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalAppTheme provides AppThemeVals(
            colors = LightColors,
            texts = buildAppTexts(LightColors),
            settings = settings,
        ),
    ) {
        content()
    }
}
