package com.studykit.ui.stats

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import com.studykit.data.AppSettings
import com.studykit.data.GlassLevel
import com.studykit.data.memory.DayBand
import com.studykit.data.memory.ForecastCfg
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
 * 描述里没数字就等于对非可视用户说"这儿有条线"，caption 漏了新词学习步那笔，
 * 用户就会拿它跟"有排期的词"那个数对不上账。这类只有"真组合一次"才拦得住。
 *
 * ## 为什么这一轮删掉了三条超载断言
 * 上一版的判据是 `mean > dailyWordGoal`，而 `dailyWordGoal` 是每日**新词**目标、不是复习容量：
 * mean 里本来就含这批新词产生的学习步，于是每天都"超"（零卡也超），那几行字是常驻装饰而不是提示
 * （取舍写在 [LoadBandChart] 类 KDoc）。比较撤了，钉它的断言跟着删——留一组断言"什么都没发生"
 * 的测试比没测试更糟，因为它会让下一个人以为超载判断还在。
 *
 * ## 无障碍断言的口径
 * 整张图**只有一条** `contentDescription`（逐日节点会让读屏把同一张图念十四遍），而它必须带真数：
 * 窗口长度、峰值在第几天、那天预计多少项。同一组数字另外还有一行**看得见**的文字（横轴没有刻度，
 * 只剩描述里的数字就等于把数只给读屏用户，与本仓 ForecastBars 的约定相反）。画法（实线/阴影各是什么）
 * 与「这里和上方那个词数是两把尺」的交代写在图下那行 caption 里——它是普通文字，读屏照念，
 * 看得见的和听得见的各拿一半。caption **不**比较两个数的大小（那条假话由
 * [说明文字不断言两个数谁大] 挡着）。
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

    /** 描述里必须有真数：峰值那天是第几天、预计多少项、窗口多长。 */
    @Test
    fun 描述带出峰值日与期望项数() {
        composeRule.setContent {
            StatsHost { LoadBandChart(bands = bands(10.0, spikeAt = 2)) }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription(
            "未来 ${ForecastCfg.HORIZON_DAYS} 天复习负荷带：" +
                "峰值在第 3 天，预计 30 项；实线是每天的期望项数，阴影是它的波动范围。",
        ).assertExists()
    }

    /** 峰在别的日子时数字必须跟着走：上一条断言换成 spikeAt=7 才会露出"写死"的马脚。 */
    @Test
    fun 峰值换天时描述里的数字跟着数据走() {
        composeRule.setContent {
            StatsHost { LoadBandChart(bands = bands(10.0, spikeAt = 7)) }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription(
            "未来 ${ForecastCfg.HORIZON_DAYS} 天复习负荷带：" +
                "峰值在第 8 天，预计 30 项；实线是每天的期望项数，阴影是它的波动范围。",
        ).assertExists()
    }

    /**
     * 全图只许**一条**语义节点：14 个逐日节点会让读屏把同一张图念十四遍。
     *
     * 数的是**任意**描述，而不是数这一句的精确串——精确串只防"第二节点同款文案"，
     * 挂一条*不同*描述的节点照样溜过去。空串 + substring 的数法沿回
     * [ReviewHeatmapRenderTest]（`整块热力图只有一句汇总描述`），再单独钉住"这唯一一条的内容"。
     */
    @Test
    fun 整张图只有一条描述() {
        composeRule.setContent {
            StatsHost { LoadBandChart(bands = bands(3.0)) }
        }
        composeRule.waitForIdle()
        composeRule.onAllNodesWithContentDescription("", substring = true).assertCountEquals(1)
        composeRule.onNodeWithContentDescription(
            "未来 ${ForecastCfg.HORIZON_DAYS} 天复习负荷带：" +
                "峰值在第 1 天，预计 3 项；实线是每天的期望项数，阴影是它的波动范围。",
        ).assertExists()
    }

    /**
     * caption 必须交代带子里装了什么，否则数字读不出意义：项数除了到期复习还含每日新词目标带来的
     * 学习步。这句话是本节存在的理由。
     */
    @Test
    fun 说明文字点出带子含到期复习与新词学习步() {
        composeRule.setContent {
            StatsHost { LoadBandChart(bands = bands(3.0)) }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("到期复习", substring = true).assertExists()
        composeRule.onNodeWithText("每日新词", substring = true).assertExists()
        composeRule.onNodeWithText("学习步", substring = true).assertExists()
        composeRule.onNodeWithText("有排期的词", substring = true).assertExists()
    }

    /**
     * ⛔ caption 不许断言「这两个数谁大」——上面那组 substring 检查对这句假话是**盲的**
     * （"会比「有排期的词」那个数大"里每个关键词都在），所以这一条专治它。
     *
     * 旧图注写着「这里的项数会比「有排期的词」那个数大」。那是假话：`plannedCount` 是**整个库**
     * 已排期的词数，而带子的 mean 是**某一天**要处理的项数——库一大那个总数就赢，那句话只对
     * 词库比一天的量还小的用户成立。图注现在只说两把尺，比大小留给读的人。
     */
    @Test
    fun 说明文字不断言两个数谁大() {
        composeRule.setContent {
            StatsHost { LoadBandChart(bands = bands(3.0)) }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("会比", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("那个数大", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("不是同一把尺", substring = true).assertExists()
    }

    /**
     * 整条 caption 逐字钉住，其中「每张新词约 N 次」的 N **取预测用的那个常量**
     * （[ForecastCfg.DEFAULT_LEARNING_PER_NEW]）而不是视图里的字面量：文案里那个数字与
     * `DueForecast` 真正拿去算的那个一旦分叉，图上就印着一句关于自己算法的假话。
     */
    @Test
    fun 说明文字整句取自预测用的那些量() {
        composeRule.setContent {
            StatsHost { LoadBandChart(bands = bands(3.0)) }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(
            "实线是那天预计要处理的项数，阴影是它的波动范围（±1 个标准差）。" +
                "带子里除了已排期的到期复习，还含两笔：忘了的卡按返工概率摊到之后几天，" +
                "以及设置里「每日新词」目标随后产生的学习步" +
                "（每张新词约 ${ForecastCfg.DEFAULT_LEARNING_PER_NEW} 次）。" +
                "它和上方「有排期的词」不是同一把尺：这里数的是一天里要处理多少项，" +
                "那里数的是整个库已排期的词一共多少，谁大谁小都不说明问题。" +
                "一张卡都还没背时，这条带也不会是零。",
        ).assertExists()
    }

    /**
     * 峰值那两三个数必须**看得见**，不能只活在 `contentDescription` 里。
     *
     * 撤掉超容量那行字之后，视力正常的人在这张图上读不到任何数字（横轴没有刻度），
     * 而读屏描述里却有——这与本仓 [ForecastBars] 逐日把数字摆在柱子顶上正相反，
     * 等于把"谁拿得到数"反过来。可见这一行与下面那条描述同源（同一个 `peak`、同一个取整）。
     */
    @Test
    fun 峰值数字有一行看得见的文字() {
        composeRule.setContent {
            StatsHost { LoadBandChart(bands = bands(10.0, spikeAt = 2)) }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("最忙的是第 3 天，预计 30 项。").assertExists()
    }

    /**
     * 反向守卫：这一行不许是写死的装饰。峰换到第 8 天时它必须跟着走——
     * 上一条断言若把"第 3 天"硬编码进视图，只有这一条会红。
     */
    @Test
    fun 可见那行数字跟着峰值日走() {
        composeRule.setContent {
            StatsHost { LoadBandChart(bands = bands(10.0, spikeAt = 7)) }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("最忙的是第 8 天，预计 30 项。").assertExists()
        composeRule.onNodeWithText("最忙的是第 3 天，预计 30 项。").assertDoesNotExist()
    }

    /**
     * 反向守卫：撤掉容量比较之后，「容量」两个字不许在这张图上任何角落残留。
     * 留着它（哪怕只是半句话）就等于邀请下一个人把那条恒真的线画回来。
     */
    @Test
    fun 图上不再出现任何容量字样() {
        composeRule.setContent {
            StatsHost { LoadBandChart(bands = bands(50.0)) }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("容量", substring = true).assertDoesNotExist()
    }

    /** 整窗零期望值时不许画一张贴底的空线图冒充数据，直接给空态文案。 */
    @Test
    fun 全零负荷显示空态文案而不是空图() {
        composeRule.setContent {
            StatsHost {
                LoadBandChart(bands = List(ForecastCfg.HORIZON_DAYS) { DayBand(it, 0.0, 0.0) })
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("接下来两周还没有可画的负荷").assertExists()
    }

    /** 首次发射前（或窗口长度 0）同样走空态，不许在 `maxOf`/`bands[0]` 上崩。 */
    @Test
    fun 空列表也走空态而不是崩溃() {
        composeRule.setContent {
            StatsHost { LoadBandChart(bands = emptyList()) }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("接下来两周还没有可画的负荷").assertExists()
    }

    /**
     * 一张卡都没有**不是**空态：此时带子画的是每日新词目标将产生的学习步，那是如实的预测。
     * 空态只留给"一条线都画不出来"，否则用户会被一句"没有排期"骗去看一个其实有内容的窗口。
     */
    @Test
    fun 只剩新词学习步时照常画图而不是空态() {
        composeRule.setContent {
            // 与 DueForecast 对零卡用户真实产出的形状同量级：每天 intake×权重，mean 全为正但很小
            StatsHost { LoadBandChart(bands = bands(1.25)) }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("接下来两周还没有可画的负荷").assertDoesNotExist()
        composeRule.onNodeWithContentDescription(
            "未来 ${ForecastCfg.HORIZON_DAYS} 天复习负荷带：" +
                "峰值在第 1 天，预计 1 项；实线是每天的期望项数，阴影是它的波动范围。",
        ).assertExists()
    }

    /** 窗口长度跟着数据走：改 `ForecastCfg.HORIZON_DAYS` 不会让描述停在"14 天"。 */
    @Test
    fun 描述里的天数取实际带长() {
        composeRule.setContent {
            StatsHost { LoadBandChart(bands = (0 until 7).map { DayBand(it, 3.0, 0.5) }) }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription(
            "未来 7 天复习负荷带：峰值在第 1 天，预计 3 项；实线是每天的期望项数，阴影是它的波动范围。",
        ).assertExists()
    }

    private fun bands(v: Double, spikeAt: Int = -1) =
        (0 until ForecastCfg.HORIZON_DAYS).map { DayBand(it, if (it == spikeAt) v * 3 else v, 0.5) }
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
