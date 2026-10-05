package com.studykit.ui.optimize

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import com.studykit.data.AppSettings
import com.studykit.data.GlassLevel
import com.studykit.data.memory.FsrsModel
import com.studykit.optimize.Bin
import com.studykit.optimize.Decision
import com.studykit.optimize.EvalMetrics
import com.studykit.optimize.FsrsGate
import com.studykit.optimize.MetricsParser
import com.studykit.optimize.ParamSnapshot
import com.studykit.optimize.TrainReport
import com.studykit.ui.settings.SchedulePracticeSettings
import com.studykit.ui.theme.AppThemeVals
import com.studykit.ui.theme.LightColors
import com.studykit.ui.theme.LocalAppTheme
import com.studykit.ui.theme.buildAppTexts
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 设置页「排期内核」校准卡 / 结果弹窗 / 快照列表的**渲染守卫**（v2.9 计划 Task 12）。
 *
 * ## 它防的是什么（纯函数测不到）
 * [com.studykit.optimize.CalibrateGuardrailTest] 钉的是编排层的状态机与护栏；`FsrsGateTest`
 * 钉的是门禁阈值。它们都回答不了「文档 §③ 那批文案有没有真的摆到界面上」——文案写丢一个占位符、
 * 按钮被 `if` 隐藏而不是置灰、弹窗小图的无障碍描述漏了，数据与状态机全绿，界面却是坏的或说不清话的。
 * 唯一防法是把这些组件真组合出来、按 §③ 逐字断言。
 *
 * ## provider 与「一个测试一次 setContent」
 * 复用 [com.studykit.ui.study.ConfidencePillRenderTest] / [com.studykit.ui.habit.CheckInSheetRenderTest]
 * 的 `AppThemeVals(LightColors, buildAppTexts(LightColors), AppSettings(glass=OFF, reduceMotion=true))`；
 * `reduceMotion = true` 让 [com.studykit.ui.components.AppButton] 的按压 spring 不建补间，测试才等得到 idle。
 * 每个场景一个 `@Test`、各自一次 `setContent`（同 [com.studykit.ui.book.ExcerptReviewRenderTest] /
 * [com.studykit.ui.habit.IfThenRenderTest]：多条 compose 用例可并存，只要每条只 setContent 一次、
 * 且不触发 App/WorkManager 那类会在 teardown 漏异常的副作用）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CalibrateRenderTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** §③「数据不足」：还差 N 次评分就能校准 + [重新校准] 置灰不隐藏 + 无障碍与 tooltip。 */
    @Test
    fun 数据不足态显示还差几次并把重新校准置灰而非隐藏() {
        composeRule.setContent {
            CalibCardHost {
                CalibrationCard(
                    state = CalibrateState.Idle,
                    ratingCount = 300,
                    snapshots = emptyList(),
                    onCalibrate = {},
                    onAdopt = {},
                    onRestoreDefault = {},
                    onExport = {},
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("还差 ${FsrsGate.MIN_TERMS - 300} 次评分就能校准").assertExists()
        composeRule.onNodeWithText("重新校准").assertExists()
        composeRule.onNodeWithText("重新校准").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("用本机复习记录重新校准排期曲线").assertExists()
        composeRule.onNodeWithText("全部计算在本机完成，记录不出设备", substring = true).assertExists()
    }

    /** §③「已校准」：已用你的 N 次评分校准 · 优化过 + [重新校准] 可点 + 快照行与导出、页脚恢复默认。 */
    @Test
    fun 已校准态显示用了几次评分并放行重校并列出快照() {
        val now = System.currentTimeMillis()
        val snap = ParamSnapshot(
            id = 1L,
            params = (0 until FsrsModel.NP).joinToString(",") { "0.5" },
            ts = now,
            nTerms = 1500,
            logloss = 0.400,
            engineVer = ParamSnapshot.ENGINE_VER,
        )
        composeRule.setContent {
            CalibCardHost {
                CalibrationCard(
                    state = CalibrateState.Idle,
                    ratingCount = 1500,
                    snapshots = listOf(snap),
                    onCalibrate = {},
                    onAdopt = {},
                    onRestoreDefault = {},
                    onExport = {},
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("已用你的 1500 次评分校准", substring = true).assertExists()
        composeRule.onNodeWithText("优化过", substring = true).assertExists()
        composeRule.onNodeWithText("重新校准").assertIsEnabled()
        composeRule.onNodeWithText("条记录 · 误差", substring = true).assertExists()
        composeRule.onNodeWithContentDescription("导出当前参数快照用于反馈问题").assertExists()
        composeRule.onNodeWithText("恢复默认参数").assertExists()
    }

    /** §③「结果弹窗」（Adopt 级）：三行数字 + 采用/先不改 + 小图标题与无障碍。 */
    @Test
    fun 结果弹窗自动采纳级摆出三行数字与采用先不改() {
        val result = result(Decision.Adopt)
        composeRule.setContent {
            CalibCardHost {
                CalibrateResultDialog(
                    result = result,
                    onAdopt = {},
                    onDismiss = {},
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("校准完成，要采用新曲线吗？").assertExists()
        composeRule.onNodeWithText("预测误差：0.450 → 0.400", substring = true).assertExists()
        composeRule.onNodeWithText("校准曲线最大偏差：5.0%", substring = true).assertExists()
        composeRule.onNodeWithText("预计平均间隔：2.0 天 → 2.5 天", substring = true).assertExists()
        composeRule.onNodeWithText("采用新参数").assertExists()
        composeRule.onNodeWithText("先不改").assertExists()
        composeRule.onNodeWithText("预测 vs 实际（越贴对角线越准）").assertExists()
        composeRule.onNodeWithContentDescription("校准曲线图：横轴预测召回率，纵轴实际召回率").assertExists()
    }

    /** §③「结果弹窗」（Manual 级）：换标题并追加「提醒你：…」一行。 */
    @Test
    fun 结果弹窗人工确认级换标题并追加提醒你一行() {
        val result = result(Decision.Manual(listOf("校准曲线偏差偏大（最大 9.0%）")))
        composeRule.setContent {
            CalibCardHost {
                CalibrateResultDialog(
                    result = result,
                    onAdopt = {},
                    onDismiss = {},
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("校准结果想让你看一眼").assertExists()
        composeRule.onNodeWithText("提醒你：", substring = true).assertExists()
    }

    /** §③ 内核选项：FSRS 挂「推荐」角标、旧内核灰字含「仅维护」。 */
    @Test
    fun 内核选项里FSRS带推荐角标旧内核带仅维护灰字() {
        composeRule.setContent {
            CalibCardHost {
                SchedulePracticeSettings(
                    settings = AppSettings(),
                    onKernel = {},
                    onConfidence = {},
                    onInterleaving = {},
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("推荐").assertExists()
        composeRule.onNodeWithText("仅维护", substring = true).assertExists()
    }

    /** §③「失败 toast」：这次校准没通过体检（原因），已保留原参数。 */
    @Test
    fun 失败态把没通过体检的原话摆到卡片上() {
        composeRule.setContent {
            CalibCardHost {
                CalibrationCard(
                    state = CalibrateState.Failed("数据不足：300/${FsrsGate.MIN_TERMS}"),
                    ratingCount = 300,
                    snapshots = emptyList(),
                    onCalibrate = {},
                    onAdopt = {},
                    onRestoreDefault = {},
                    onExport = {},
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("这次校准没通过体检", substring = true).assertExists()
    }

    private fun result(decision: Decision): CalibrateState.Result =
        CalibrateState.Result(
            report = TrainReport(FloatArray(21) { 0.5f }, 0.5f, 0.4f, 100, 1500, 1234L),
            current = EvalMetrics(logloss = 0.45f, bins = emptyList(), nTerms = 300),
            candidate = EvalMetrics(logloss = 0.40f, bins = oneDevBin(0.05f), nTerms = 1_500),
            decision = decision,
            nTrainTerms = 1_200,
            nValTerms = 300,
            dryRun = false,
            intervalBefore = 2.0f,
            intervalAfter = 2.5f,
        )

    /** 只放一个样本够多的桶（pred 比 obs 高 [dev]），让「最大偏差」正好落在 dev×100%。 */
    private fun oneDevBin(dev: Float): List<Bin> =
        List(MetricsParser.BIN_COUNT) { i -> if (i == 0) Bin(0.9f, 0.9f - dev, 100) else Bin(-1f, -1f, 0) }
}

/** 只把组件本体摆出来，不摆整页（整页要 ViewModel + Room，JVM 起不来）。 */
@Composable
private fun CalibCardHost(content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalAppTheme provides AppThemeVals(
            colors = LightColors,
            texts = buildAppTexts(LightColors),
            settings = AppSettings(glass = GlassLevel.OFF, reduceMotion = true),
        ),
    ) {
        content()
    }
}
