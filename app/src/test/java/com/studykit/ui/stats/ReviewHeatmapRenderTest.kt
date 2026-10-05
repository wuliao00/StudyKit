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
import com.studykit.ui.theme.AppThemeVals
import com.studykit.ui.theme.LightColors
import com.studykit.ui.theme.LocalAppTheme
import com.studykit.ui.theme.buildAppTexts
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 复习热力图（v3.0-A Task 8）视图的**渲染守卫**。
 *
 * ## 它防的是什么（纯函数测不到）
 * [ReviewHeatmapLogicTest] 钉的是格子与色阶的算法。那一层全绿仍回答不了：
 * - **整块图对读屏是一句汇总还是一年三百多遍**（364 个逐格节点是折磨，不是无障碍），
 *   所以这里钉"全图只有 1 条 `contentDescription`"；
 * - 那句汇总里的**天数是不是真数出来的**、周数是不是从格子算出来的（写死"52 周""一年"
 *   在窗口一改就静默说谎）；
 * - caption 有没有把「只含单词复习 + 题目练习、不含书摘」这条口径说给用户——
 *   不说，用户就会拿它跟顶上「复习记录」那个数对不上账。
 *
 * 格子是**走真实 [ReviewHeatmapLogic.cells] 装配出来的**，不是手搓的 `HeatCell` 列表：
 * 视图自己再数一遍时间戳的话，这里就会数出两个不同的答案。
 *
 * provider 与「一个测试一次 setContent」沿用 [com.studykit.ui.optimize.CalibrateRenderTest]。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReviewHeatmapRenderTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val zone: ZoneOffset = ZoneOffset.UTC
    private val today: LocalDate = LocalDate.of(2026, 1, 15)

    /** 整块图**只有一条**语义描述，且那句里的有复习天数是真数出来的。 */
    @Test
    fun 整块热力图只有一句汇总描述() {
        composeRule.setContent {
            StatsPanelHost { ReviewHeatmapSection(grid(today.minusDays(1) to 3, today.minusDays(20) to 2)) }
        }
        composeRule.waitForIdle()
        // 空串 + substring=true = "任何自带 contentDescription 的节点"：这里要钉的是**只有一个**，
        // 而不是"恰好有 364 个逐格节点"
        composeRule.onAllNodesWithContentDescription("", substring = true).assertCountEquals(1)
        composeRule.onNodeWithContentDescription("最近 52 周复习热力图，共 2 天有复习").assertExists()
    }

    /** 反向守卫：天数跟着数据走。上一条若把"2"写进视图，只有这一条会红。 */
    @Test
    fun 汇总描述里的天数跟着数据走() {
        composeRule.setContent {
            StatsPanelHost {
                ReviewHeatmapSection(
                    grid(
                        today.minusDays(2) to 1,
                        today.minusDays(9) to 5,
                        today.minusDays(30) to 2,
                    ),
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("最近 52 周复习热力图，共 3 天有复习").assertExists()
        composeRule.onNodeWithContentDescription("最近 52 周复习热力图，共 2 天有复习").assertDoesNotExist()
    }

    /** 周数同样从格子算：20 周（140 格）的窗口不许继续念"52 周"。 */
    @Test
    fun 汇总描述里的周数从格子算() {
        composeRule.setContent {
            StatsPanelHost { ReviewHeatmapSection(grid(today.minusDays(3) to 1, weeks = 20)) }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("最近 20 周复习热力图，共 1 天有复习").assertExists()
    }

    /** 一格都没有（首发射前）时不画一整年空白格子冒充"你从没学习"，直接空态。 */
    @Test
    fun 没有格子时走空态() {
        composeRule.setContent {
            StatsPanelHost { ReviewHeatmapSection(emptyList()) }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("还没有复习流水").assertExists()
        composeRule.onAllNodesWithContentDescription("", substring = true).assertCountEquals(0)
    }

    /** 有格子但一次复习都没有：描述照念"共 0 天有复习"，不许因此省掉那句汇总。 */
    @Test
    fun 全零历史照样有一句汇总() {
        composeRule.setContent {
            StatsPanelHost { ReviewHeatmapSection(grid()) }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("最近 52 周复习热力图，共 0 天有复习").assertExists()
    }

    /**
     * caption 的三件事：色阶是**相对**的（按非零日 90 分位，不是固定阈值）、只数**两路**
     * （单词复习 + 题目练习，书摘没有复习流水）、以及未来那几格是占位而不是"没坚持"。
     */
    @Test
    fun 说明文字交代色阶口径与两路范围() {
        composeRule.setContent {
            StatsPanelHost { ReviewHeatmapSection(grid(today.minusDays(1) to 4)) }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("90 分位", substring = true).assertExists()
        composeRule.onNodeWithText("单词复习", substring = true).assertExists()
        composeRule.onNodeWithText("题目练习", substring = true).assertExists()
        composeRule.onNodeWithText("书摘", substring = true).assertExists()
        composeRule.onNodeWithText("还没到来", substring = true).assertExists()
    }

    /** 指定日期各来几次复习，拼成时间戳后走真实装配；`weeks` 只影响网格长度。 */
    private fun grid(vararg days: Pair<LocalDate, Int>, weeks: Int = 52): List<HeatCell> =
        ReviewHeatmapLogic.cells(
            timestamps = days.flatMapIndexed { i, (date, times) ->
                (0 until times).map { j ->
                    date.atStartOfDay(zone).plusHours((9 + (i + j) % 8).toLong()).toInstant().toEpochMilli()
                }
            },
            today = today,
            weeks = weeks,
            zone = zone,
        )
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
