package com.studykit.ui.habit

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.studykit.data.AppSettings
import com.studykit.data.GlassLevel
import com.studykit.data.entity.Habit
import com.studykit.ui.theme.AppThemeVals
import com.studykit.ui.theme.LightColors
import com.studykit.ui.theme.LocalAppTheme
import com.studykit.ui.theme.buildAppTexts
import java.time.LocalDate
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
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
// 名字序强制**对照组先跑**。JUnit 默认方法序不保证，而失败的那条测试会漏出未捕获异常，
// 让同 JVM 里后跑的测试直接抛 `UncaughtExceptionsBeforeTest`（连断言都执行不到）。
// 上一版守卫测试先跑 SOFT、它失败，于是本该绿的对照组也被拖成红 —— 一整轮 CI 什么也问不出来。
// 对照组拿到干净结果，才谈得上"判别力"：坏代码上必须是「对照绿 + 守卫红」。
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
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
    fun `b_guard_玻璃开着时弹层内容必须渲染出来`() {
        // 这就是当初坏掉的那个条件：AppSettings 默认档位就是 SOFT
        showSheet(GlassLevel.SOFT)

        composeRule.onNodeWithText("补打卡").assertIsDisplayed()
        composeRule.onNodeWithText("背单词 · 9月22日（补卡限过去 7 天内）").assertIsDisplayed()
        composeRule.onNodeWithText("备注").assertIsDisplayed()
        composeRule.onNodeWithText("确认补卡").assertIsDisplayed()
    }

    @Test
    fun `a_control_玻璃关闭时弹层内容同样渲染`() {
        // 对照组，名字前缀保证它先跑。若只有这条绿、上面那条红，说明问题出在材质而不是弹层本身；
        // 若两条一起红在同一个框架异常上，那是量具坏了，不能当成"守卫生效"。
        showSheet(GlassLevel.OFF)

        composeRule.onNodeWithText("补打卡").assertIsDisplayed()
        composeRule.onNodeWithText("确认补卡").assertIsDisplayed()
    }

    // 曾有过第三条「点确认补卡带着备注回调出去」，已删。它在 Robolectric 下必然失败，
    // 而且是**量具的局限不是产品缺陷**：节点找得到（否则抛的是语义匹配器的错，不会走到我
    // 那句自定义断言），`performClick()` 也执行了，但 `onConfirm` 不触发 —— ModalBottomSheet
    // 是独立 window，Robolectric 的输入注入打不进对话框窗口。真机上同一枚按钮点下去
    // `is_makeup=1` 确实落了库（见 v2.4 真机走查报告 §1.1）。
    // 留着它的代价不只是假红：它漏出的异常会污染同 JVM 的后续测试，让本该绿的对照组
    // 一起变成 `UncaughtExceptionsBeforeTest`，一整轮 CI 什么也问不出来。
}
