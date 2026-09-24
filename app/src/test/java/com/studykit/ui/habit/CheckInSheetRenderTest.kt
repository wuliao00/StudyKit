package com.studykit.ui.habit

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.studykit.data.AppSettings
import com.studykit.data.GlassLevel
import com.studykit.data.entity.Habit
import com.studykit.ui.theme.AppThemeVals
import com.studykit.ui.theme.LightColors
import com.studykit.ui.theme.LocalAppTheme
import com.studykit.ui.theme.buildAppTexts
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 打卡弹层的**渲染**守卫。
 *
 * 为什么需要它：2026-09-24 真机撞到一个纯 JVM 测试永远抓不到的缺陷 —— `Modifier.glassSurface`
 * 挂在 `ModalBottomSheet` 自己的 `modifier` 上，玻璃开着时弹层**只剩一层遮罩**，
 * 标题/备注/「确认补卡」整棵子树不进语义树（屏幕变暗看得见、内容点不着、uiautomator 查不到节点）。
 * 数据、DAO、状态机全都是对的，只有界面是空的 —— 这类 bug 只能靠"把 composable 真组合出来、
 * 再断言节点在不在"来防。
 *
 * 断言刻意用 `assertIsDisplayed()` 而不是 `assertExists()`：当初节点是**根本不存在**，
 * 但同一类问题也可能表现为"存在却被裁到零尺寸"，`assertIsDisplayed` 两种都拦得住。
 *
 * `@GraphicsMode(NATIVE)` 是 Robolectric 跑 Compose 绘制的前提；`@Config(sdk = [34])`
 * 钉死运行环境，免得升 Robolectric 时测试语义悄悄漂移。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CheckInSheetRenderTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** 天数型习惯（targetCount=0）：弹层里没有数量栏，结构最简单，最适合当渲染探针 */
    private val readHabit = Habit(uuid = "u-test-read", name = "背单词", icon = "book")

    private val makeupDate: LocalDate = LocalDate.of(2026, 9, 22)

    private fun showSheet(glass: GlassLevel, onConfirm: (String, Double) -> Unit = { _, _ -> }) {
        composeRule.setContent {
            CompositionLocalProvider(
                LocalAppTheme provides AppThemeVals(
                    colors = LightColors,
                    texts = buildAppTexts(LightColors),
                    // reduceMotion=true 只为让弹入时那一次亮带扫描动画不排 —— 挂着 Animatable
                    // 的话测试规则等不到 idle。材质本体（background / clip / drawWithCache / border）
                    // 依然完整走一遍，而那四步才是当初把内容弄没的东西。
                    settings = AppSettings(glass = glass, reduceMotion = true),
                ),
            ) {
                CheckInSheet(
                    habit = readHabit,
                    date = makeupDate,
                    existing = null,
                    isMakeUp = true,
                    onDismiss = {},
                    onConfirm = onConfirm,
                )
            }
        }
    }

    @Test
    fun `补打卡弹层在玻璃开着时也要把内容渲染出来`() {
        // 这就是当初坏掉的那个条件：AppSettings 默认档位就是 SOFT
        showSheet(GlassLevel.SOFT)

        composeRule.onNodeWithText("补打卡").assertIsDisplayed()
        composeRule.onNodeWithText("背单词 · 9月22日（补卡限过去 7 天内）").assertIsDisplayed()
        composeRule.onNodeWithText("备注").assertIsDisplayed()
        composeRule.onNodeWithText("确认补卡").assertIsDisplayed()
    }

    @Test
    fun `玻璃关闭时同样渲染`() {
        // 对照组。若只有这条绿、上面那条红，说明问题出在材质而不是弹层本身。
        showSheet(GlassLevel.OFF)

        composeRule.onNodeWithText("补打卡").assertIsDisplayed()
        composeRule.onNodeWithText("确认补卡").assertIsDisplayed()
    }

    @Test
    fun `点确认补卡带着备注回调出去`() {
        // 光"渲染出来了"还不够 —— 节点在但点击不落地，用户照样打不成卡。
        var confirmedNote: String? = null
        var confirmedAmount: Double? = null
        showSheet(GlassLevel.SOFT) { note, amount ->
            confirmedNote = note
            confirmedAmount = amount
        }

        composeRule.onNodeWithText("确认补卡").performClick()

        assertTrue("确认按钮没有回调，弹层等于摆设", confirmedAmount != null)
        // 天数型习惯固定不记数量
        assertEquals(0.0, confirmedAmount!!, 0.0001)
        assertEquals("", confirmedNote)
    }
}
