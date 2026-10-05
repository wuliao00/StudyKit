package com.studykit.ui.stats

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import com.studykit.data.AppSettings
import com.studykit.data.GlassLevel
import com.studykit.data.memory.DayBand
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
 * 负荷带视图（v3.0-A Task 6）的**渲染守卫**。
 *
 * ## 它防的是什么（纯函数测不到）
 * [com.studykit.data.memory.DueForecastTest] 钉的是带的算法（期望值、方差、出窗），
 * [com.studykit.ui.stats.ForecastCardsTest] 钉的是装配口径。两边都回答不了
 * 「带子算对了，但用户和读屏软件到底看见了什么」——空态文案写丢了就是一片空白，
 * 超容量那行没摆出来就只剩一条看不出问题的曲线。这类只有"真组合一次"才拦得住。
 *
 * ## 为什么断言的是文字行而不是逐日 contentDescription
 * 超容量日只以**一行中文文字**报出来（`第 3 天预计 30 项，超过每日容量 20 项。`），
 * 不给每一天各挂一个语义节点：整张图已经有一条总描述，再挂 14 个逐日节点，
 * 读屏会把同一张图念十四遍。文字行本来就是可见的，读屏念它、眼睛也看它，一份语义两处用。
 * 于是这里的断言和真实用户拿到的是同一串字符——比断言一个藏在 `semantics {}` 里的
 * 字符串更有说服力。
 *
 * provider 与「一个测试一次 setContent」沿用 [com.studykit.ui.optimize.CalibrateRenderTest]：
 * `AppSettings(glass = OFF, reduceMotion = true)` 里的 `reduceMotion` 让 `waitForIdle()` 等得到。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LoadBandRenderTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** 超容量那天必须写成一句人话：第几天、预计多少项、容量是多少。 */
    @Test
    fun 超容量日用一行中文报出第几天与预计项数() {
        composeRule.setContent {
            StatsHost { LoadBandChart(bands = bands(10.0, spikeAt = 2), capacityPerDay = 20) }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("第 3 天预计 30 项，超过每日容量 20 项。").assertExists()
    }

    /** 全零负荷不许画一张贴底的空线图冒充数据，直接给空态文案。 */
    @Test
    fun 全零负荷显示空态文案而不是空图() {
        composeRule.setContent {
            StatsHost { LoadBandChart(bands = List(14) { DayBand(it, 0.0, 0.0) }, capacityPerDay = 20) }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("接下来两周没有排期").assertExists()
    }

    /** 装配层一条卡都没有（新库）时同样走空态，不许在 `maxOf` 上崩。 */
    @Test
    fun 空列表也走空态而不是崩溃() {
        composeRule.setContent {
            StatsHost { LoadBandChart(bands = emptyList(), capacityPerDay = 20) }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("接下来两周没有排期").assertExists()
    }

    /** 整张图只有**一条**总描述（读屏念这一句就够了），且必须逐字说清三线各是什么。 */
    @Test
    fun 整张图有一条总描述() {
        composeRule.setContent {
            StatsHost { LoadBandChart(bands = bands(3.0), capacityPerDay = 20) }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription(
            "未来 14 天复习负荷带：实线是期望数量，阴影是波动范围，横虚线是每日容量",
        ).assertExists()
    }

    /** 没超容量就不许有标注行：否则「天天超载」会变成图上的固定装饰，用户学会无视它。 */
    @Test
    fun 容量之内不写任何超容量标注() {
        composeRule.setContent {
            StatsHost { LoadBandChart(bands = bands(3.0), capacityPerDay = 20) }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("超过每日容量", substring = true).assertDoesNotExist()
    }

    /** 天天超载时不许刷出 14 行文字：最多逐日点几天，其余收成一句总数。 */
    @Test
    fun 每天都超时也只点出前几天并汇总剩余() {
        composeRule.setContent {
            StatsHost { LoadBandChart(bands = bands(50.0), capacityPerDay = 20) }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("第 1 天预计 50 项，超过每日容量 20 项。").assertExists()
        composeRule.onNodeWithText("第 2 天预计 50 项，超过每日容量 20 项。").assertExists()
        composeRule.onNodeWithText("第 3 天预计 50 项，超过每日容量 20 项。").assertExists()
        composeRule.onNodeWithText("第 4 天预计 50 项，超过每日容量 20 项。").assertDoesNotExist()
        composeRule.onNodeWithText("另有 11 天同样超过每日容量 20 项。").assertExists()
    }

    private fun bands(v: Double, spikeAt: Int = -1) =
        (0 until 14).map { DayBand(it, if (it == spikeAt) v * 3 else v, 0.5) }
}

/** 只把组件本体摆出来，不摆整页（整页要 ViewModel + Room，JVM 起不来）。 */
@Composable
private fun StatsHost(content: @Composable () -> Unit) {
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
