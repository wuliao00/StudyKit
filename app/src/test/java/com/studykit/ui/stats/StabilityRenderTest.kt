package com.studykit.ui.stats

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import com.studykit.data.AppSettings
import com.studykit.data.GlassLevel
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
 * 稳定性全景（v3.0-A Task 7）视图的**渲染守卫**。
 *
 * ## 它防的是什么（纯函数测不到）
 * [StabilityBinsTest] 钉的是分箱本身（边界、分母、脏值）。那一层全绿也回答不了
 * 「十二档到底有没有摆上屏」「占比文字用的是 [StabilityBin.shareLabel] 还是裸百分数」
 * 「caption 有没有交代母体与分母」——这几件事错了都不报错，用户只会读到一幅没有解释的图，
 * 或者更糟：把这里的 30% 与上面「这些记忆能扛多久」的 30% 当成同一件事。
 *
 * ## 行条沿用 DurabilityRow，所以无障碍口径也一并沿用
 * 一行**一条** `contentDescription`（"标签：N 个，占 X%"）。这里是十二行十二条，
 * 与 [ForecastBars] 逐日一条同形——这是"每行都有独立含义的列表"，不是 [LoadBandChart]
 * 那种"同一张图念十四遍"。
 *
 * provider 与「一个测试一次 setContent」沿用 [com.studykit.ui.optimize.CalibrateRenderTest]。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StabilityRenderTest {

    @get:Rule
    val composeRule = createComposeRule()

    /**
     * 十二档**全摆出来**，零计数那十一档也各占一行。
     * 计数用"0 个 · 0%"的行数（11 行）而不是标签数：视图若把空档 `filter` 掉，这里立刻归零。
     * 缺行的坏处是用户以为那一档不存在，而不是"自己没攒到"——与 RetentionCard 那条纪律同一条。
     */
    @Test
    fun 空档也占一行而不是被隐藏() {
        composeRule.setContent {
            StatsPanelHost { StabilityPanoramaSection(bins = StabilityBins.bin(listOf(1.5)), cardCount = 1) }
        }
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText("0 个 · 0%").assertCountEquals(StabilityBins.Edges.size)
        composeRule.onNodeWithText("≤0.5 天").assertExists()
        composeRule.onNodeWithText("1 年以上").assertExists()
    }

    /** 数字与占比是看得见的文字（DurabilityRow 那一套：右侧 "N 个 · 占比"）。 */
    @Test
    fun 每行的数字与占比都摆在面上() {
        composeRule.setContent {
            StatsPanelHost {
                StabilityPanoramaSection(bins = StabilityBins.bin(List(4) { 1.5 }), cardCount = 4)
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("4 个 · 100%").assertExists()
        // 其余十一档都是"0 个 · 0%"——用条数断言而不是 assertExists：`onNodeWithText` 要求唯一命中，
        // 十一个同名节点会直接报"found 11 nodes"，那条报错说的不是"视图错了"而是"断言写错了"
        composeRule.onAllNodesWithText("0 个 · 0%").assertCountEquals(StabilityBins.Edges.size)
    }

    /** 每行一条读屏描述，与 DurabilityRow 同形（"标签：N 个，占 X%"），且只有一条。 */
    @Test
    fun 每行只有一条读屏描述() {
        composeRule.setContent {
            StatsPanelHost {
                StabilityPanoramaSection(bins = StabilityBins.bin(List(4) { 1.5 }), cardCount = 4)
            }
        }
        composeRule.waitForIdle()
        composeRule.onAllNodesWithContentDescription("1-2 天：4 个，占 100%").assertCountEquals(1)
        composeRule.onNodeWithContentDescription("4-8 天：0 个，占 0%").assertExists()
    }

    /**
     * 占比文字必须走 [StabilityBin.shareLabel]：1000 张卡里 1 张落在「32-64 天」时
     * `sharePercent` 四舍五入是 0，"1 个 · 0%"是自相矛盾的话（durability 那节真机上就这么显示过）。
     */
    @Test
    fun 稀有档的占比不说零() {
        val bins = StabilityBins.bin(List(999) { 1.5 } + listOf(50.0))
        composeRule.setContent {
            StatsPanelHost { StabilityPanoramaSection(bins = bins, cardCount = 1000) }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("1 个 · 不足 1%").assertExists()
    }

    /**
     * caption 必须交代两件事，否则这十二行没有意义：母体是**哪一批卡**（已排期且被 FSRS 写过 S 的
     * 词 + 错题 + 书摘），以及分母口径与上面那节相同、母体不同，所以百分比不能横向比。
     */
    @Test
    fun 说明文字交代母体与分母口径() {
        composeRule.setContent {
            StatsPanelHost {
                StabilityPanoramaSection(bins = StabilityBins.bin(List(7) { 3.0 }), cardCount = 7)
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("共 7 张卡", substring = true).assertExists()
        composeRule.onNodeWithText("错题", substring = true).assertExists()
        composeRule.onNodeWithText("书摘", substring = true).assertExists()
        composeRule.onNodeWithText("可信卡数", substring = true).assertExists()
        composeRule.onNodeWithText("这些记忆能扛多久", substring = true).assertExists()
    }

    /** 一张可信卡都没有时不画十二行 0%，直接空态——那十二行只会让人以为"我全忘了"。 */
    @Test
    fun 没有可信卡时走空态() {
        composeRule.setContent {
            StatsPanelHost {
                StabilityPanoramaSection(bins = StabilityBins.bin(emptyList()), cardCount = 0)
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("还没有可信的稳定度").assertExists()
        composeRule.onNodeWithText("4-8 天").assertDoesNotExist()
    }

    /** 首发射前 `bins` 是空列表（不是十二个零档），同样走空态，不许在空列表上画出行条。 */
    @Test
    fun 分箱结果为空列表时同样走空态() {
        composeRule.setContent {
            StatsPanelHost { StabilityPanoramaSection(bins = emptyList(), cardCount = 0) }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("还没有可信的稳定度").assertExists()
    }
}

/** 只把组件本体摆出来，不摆整页（整页要 ViewModel + Room，JVM 起不来）。 */
@Composable
private fun StatsPanelHost(content: @Composable () -> Unit) {
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
