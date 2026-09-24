package com.studykit.ui.habit

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 打卡弹层的**渲染**守卫。
 *
 * ## 它防的是什么
 *
 * 2026-09-24 真机撞到一个纯 JVM 逻辑测试**结构上就抓不到**的缺陷：`Modifier.glassSurface`
 * 挂在 `ModalBottomSheet` 自己的 `modifier` 上，玻璃开着时弹层只剩一层遮罩 ——
 * 标题、备注、「确认补卡」整棵子树不进语义树。数据、DAO、状态机全对，只有界面是空的。
 * 唯一的防法是把它真组合出来、再断言节点在不在。
 *
 * ## 为什么只有一个测试方法
 *
 * 这不是省事，是**必需的**。之前拆成"对照组 + 守卫"两个 `@Test`，先跑的那条（哪怕它通过）
 * 会在 teardown 漏出未捕获异常，把同 JVM 里后跑的那条毒成
 * `UncaughtExceptionsBeforeTest` —— 于是同一条断言两次红在**不同原因**上，
 * 给不出可复现判定，也就不能当防线。
 * 一个方法里做两段，跨测试污染这个类别整体消失。
 *
 * 两段的消息不同，判别力靠消息而不是靠测试数量：
 * 第一段红 = **量具坏了**（玻璃关着在真机上是好的，它不该失败）；
 * 第一段绿、第二段红 = **守卫命中缺陷**。
 *
 * ## 为什么走 Robolectric
 *
 * instrumented test 要模拟器，而本仓 CI 唯一的执行器是 `testDebugUnitTest`；
 * 引模拟器会把构建时长和变红风险一起抬上来。
 * `@GraphicsMode(NATIVE)` 是 Robolectric 跑 Compose 绘制的前提，
 * `@Config(sdk = [34])` 钉死环境，免得升 Robolectric 时测试语义悄悄漂移。
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

    /**
     * 当前玻璃档位。**只能 setContent 一次**（第二次直接抛
     * `Cannot call setContent twice per test!`），所以换档靠翻这个 state 触发重组，
     * 而不是重设内容 —— 这样仍然是"一个测试方法内做完两段"，跨测试污染无从发生。
     */
    private var glass by mutableStateOf(GlassLevel.OFF)

    @Test
    fun `玻璃开关两种状态下弹层内容都必须渲染`() {
        composeRule.setContent {
            SheetHost(glass = glass, habit = readHabit, date = makeupDate)
        }

        // ── 第一段：对照组。玻璃关闭在真机上一直是好的，它不该失败 ──
        // 这一手先跑，是为了让"量具坏了"和"守卫命中缺陷"分得开：
        // 若这里就红，说明 Robolectric 压根没把弹层组合起来，后面那条红毫无意义。
        clue("量具不可信：玻璃【关闭】时弹层内容都没渲染出来，" +
            "说明 Robolectric 没能组合 ModalBottomSheet，本测试的判定无效") {
            composeRule.onNodeWithText("补打卡").assertIsDisplayed()
            composeRule.onNodeWithText("确认补卡").assertIsDisplayed()
        }

        // ── 第二段：守卫。这就是当初坏掉的那个条件 ──
        // AppSettings 的默认档位就是 SOFT，所以"什么都不设"也正是用户会遇到的状态。
        glass = GlassLevel.SOFT
        composeRule.waitForIdle()
        clue("守卫命中：玻璃【柔和】时弹层内容没渲染出来 —— " +
            "这就是 Modifier.glassSurface 被挂到 ModalBottomSheet.modifier 上的那个缺陷，" +
            "详见 v2.4 真机走查报告 §2；材质应画在弹层内容容器上，不要画在 ModalBottomSheet 自身。") {
            composeRule.onNodeWithText("补打卡").assertIsDisplayed()
            composeRule.onNodeWithText("背单词 · 9月22日（补卡限过去 7 天内）").assertIsDisplayed()
            composeRule.onNodeWithText("备注").assertIsDisplayed()
            composeRule.onNodeWithText("确认补卡").assertIsDisplayed()
        }
    }

    private companion object {
        /**
         * 把一句人话挂到失败消息上。Compose 的断言只会说 "The component is not displayed"，
         * 而这条测试的价值全在**是哪一段**红 —— 没有这段话，下一次排查又要从头猜。
         * 用 JUnit 的 `Assume` 语义会跳过而不是失败，所以这里用 try/catch 重抛带线索的断言错误。
         */
        fun clue(hint: String, block: () -> Unit) {
            try {
                block()
            } catch (e: AssertionError) {
                throw AssertionError("$hint\n\n原始信息：${e.message}", e)
            }
        }
    }
}

/**
 * 单独抽出来，是为了让两种玻璃档位能**在同一个测试方法内**先后呈现：
 * `setContent` 一个测试只能调一次（第二次直接抛 `Cannot call setContent twice per test!`），
 * 所以换档靠外层传入的 `glass` 状态触发重组。
 * 之所以要挤进一个方法而不是拆两个 `@Test`：拆开会互相污染（上一条在 teardown 漏出的
 * 未捕获异常会把下一条毒成 `UncaughtExceptionsBeforeTest`，与成败无关），
 * 见类文档与 v2.4 真机走查报告 §2。
 *
 * `reduceMotion = true` 只为让弹入时那一次亮带扫描动画不排 —— 挂着 `Animatable`
 * 的话测试规则等不到 idle。材质本体（background / clip / drawWithCache / border）
 * 依然完整走一遍，而那四步才是当初把内容弄没的东西。
 */
@Composable
private fun SheetHost(
    glass: GlassLevel,
    habit: Habit,
    date: LocalDate,
) {
    CompositionLocalProvider(
        LocalAppTheme provides AppThemeVals(
            colors = LightColors,
            texts = buildAppTexts(LightColors),
            settings = AppSettings(glass = glass, reduceMotion = true),
        ),
    ) {
        CheckInSheet(
            habit = habit,
            date = date,
            existing = null,
            isMakeUp = true,
            onDismiss = {},
            onConfirm = { _, _ -> },
        )
    }
}
