package com.studykit.ui.study

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import com.studykit.data.AppSettings
import com.studykit.data.GlassLevel
import com.studykit.data.entity.Word
import com.studykit.ui.theme.AppThemeVals
import com.studykit.ui.theme.LightColors
import com.studykit.ui.theme.LocalAppTheme
import com.studykit.ui.theme.buildAppTexts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 闸门的**渲染守卫**：手势路径真的走一遍，断言评分回调到底有没有被调用。
 *
 * ## 为什么这条必须有（纯函数测不到它）
 * [RecallGateTest] 钉的是 [decideRecallGate] 那张真值表，但它回答不了
 * 「`Modifier.draggable` 的 `onDragStopped` 里那条 when 有没有接上这个判定」——
 * 判定对、接错线的写法（比如漏了 FLIP 分支、或者 FLIP 分支里顺手 `claim()`）在纯函数测试里全绿。
 * 这一条把 [SwipeRatingCard] 真组合出来、注入真手势，于是这三种坏法当场红：
 * ① 闸门开着而滑动还是直接结算了（拦了个假）；② 拦下之后这张卡再也评不了（把拦截写成了 claim，
 * 就是本页 KDoc 里那条软锁的另一半）；③ 关掉开关还在拦人（"可关"是句空话）。
 *
 * ## 为什么只有一个 `@Test` 方法
 * 同 [com.studykit.ui.habit.CheckInSheetRenderTest] 与 `ContractRitualRenderTest`：
 * 本仓实测过同一个类里多方法会互相污染（先跑那条即使通过也在 teardown 漏出未捕获异常，
 * 把后跑的毒成 `UncaughtExceptionsBeforeTest`，红的不是断言而是别处的残留）。
 * 三段塞进一个方法，靠 [clue] 的自定义消息区分是哪一段红；换状态用外层 `mutableStateOf`
 * 触发重组，因为**一个测试只能 `setContent` 一次**（第二次直接抛
 * `Cannot call setContent twice per test!`）。
 * 第三段换的是**另一张卡**（`word.id` 不同）：卡片里那枚 `graded` 守卫是
 * `rememberSaveable(word.id)`，同一段里不能把同一张卡结算两次。
 *
 * ## 不测什么
 * - **不测那条一次性说明**：本仓实测过 Robolectric 的输入注入打不进 dialog 窗口
 *   （`ContractRitualRenderTest` 因此也不测点击）。这条守卫把 `onGateBlocked` 换成一个只计数的
 *   回调，压根不摆弹层，免得弹层的遮罩把后面两次注入的手势全吃掉。
 *   说明本身的读写口径由 `AppSettingsTest` 那两条键值往返用例钉。
 * - **不测预测试那一屏**：它的题面来自 Room 查询，JVM 侧没有库；
 *   出题条件与"不足三条就跳过"由 [RecallGateTest] 的纯函数用例覆盖。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RecallGateRenderTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val wordA = Word(id = 1L, uuid = "u1", word = "abandon", meaning = "放弃；丢弃", example = "")
    private val wordB = Word(id = 2L, uuid = "u2", word = "genuine", meaning = "真的；非伪造的", example = "")

    /** 闸门档位 / 当前卡片：`setContent` 只有一次，换档与换卡都靠翻这两个状态 */
    private var gate by mutableStateOf(true)
    private var shown by mutableStateOf(wordA)

    /** 页面那一层持有的「答案露过没有」；测试里就是闸门的唯一输入 */
    private val revealed = mutableStateOf(false)

    /** 结算回调的记录：断言"这一手到底有没有评上分"就看它 */
    private val grades = mutableListOf<Boolean>()

    /** 闸门真的拦下过几手（一次性说明的触发次数走的是同一个回调） */
    private var blocked by mutableStateOf(0)

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun 闸门拦下第一次滑动不结算_翻面后同一手势才结算_关掉开关直接退回旧行为() {
        composeRule.setContent {
            CardHost(
                word = shown,
                revealed = revealed,
                gateEnabled = gate,
                onGateBlocked = { blocked += 1 },
                onGrade = { grades += it },
            )
        }
        composeRule.waitForIdle()

        // ── 对照组：真机上这张卡一直是好的，这一段红 = 量具坏了 ──
        clue(
            "量具不可信：卡片连词面都没摆出来 —— Robolectric 没能组合 SwipeRatingCard，" +
                "后面几段的判定无效",
        ) {
            composeRule.onNodeWithText("abandon").assertIsDisplayed()
            composeRule.onNodeWithText("不认识").assertIsDisplayed()
        }

        // ── ① 闸门开 + 未翻面：注入一次右滑 ⇒ onGrade 没被调用，而是翻面 + 弹回 ──
        injectSwipe(toRight = true)
        clue(
            "闸门开着、卡片没翻面，这一次滑动却把分结算了（grades=$grades）—— " +
                "说明 onDragStopped 没走 decideRecallGate，或者 FLIP 分支没生效：" +
                "「先看答案之前算不算回忆过」这个闸门白做",
        ) {
            assertEquals(emptyList<Boolean>(), grades)
        }
        clue("闸门拦下这一手时应当顺手把它变成一次翻面（revealed 该为真），实际 ${revealed.value}") {
            assertTrue(revealed.value)
        }
        clue("拦下一次就该回调一次 onGateBlocked（那条一次性说明靠它决定要不要出现）") {
            assertEquals(1, blocked)
        }

        // ── ② 翻面之后：同一条手势路径按原语义直接结算 ──
        injectSwipe(toRight = true)
        clue("卡片已经翻面（答案在屏幕上）之后右滑没有结算（grades=$grades）—— 闸门把拦下写成了死锁") {
            assertEquals(listOf(true), grades)
        }
        clue("已翻面的一手不该再算一次拦截") {
            assertEquals(1, blocked)
        }

        // ── ③ 闸门关 + 换一张卡（未翻面）：左滑直接结算 = 完全退回旧行为 ──
        gate = false
        revealed.value = false
        shown = wordB
        composeRule.waitForIdle()
        clue("换卡后新词面应当摆出来") {
            composeRule.onNodeWithText("genuine").assertIsDisplayed()
        }
        injectSwipe(toRight = false)
        clue(
            "闸门【关掉】、卡片没翻面，左滑却没有直接结算（grades=$grades）—— " +
                "这违反 §3.1「关掉就完全退回今天的行为」，也就是那条可关承诺的全部内容。" +
                "先查 recallBeforeGrade 有没有真的被读（AppSettingsTest 之外这里的 gateEnabled）",
        ) {
            assertEquals(listOf(true, false), grades)
        }
    }

    /**
     * 注入一次横向拖拽：按下 → 分四步推到约 64% 屏宽 → 松手。
     *
     * 分四步（而不是一次 `moveTo` 跳到位）是必须的：`Modifier.draggable` 要先过 touchSlop
     * 才开始跟手，一次跳到位会让第一帧的位移全喂给 slop 判定，速度历史也是空的。
     * 步长按 `width`（根节点=整窗，卡片几乎铺满同宽）取 0.16，四步合计越过
     * [swipeThresholdPx] 那条 `宽 * 0.33f` 的阈值；起点取 0.35 高的位置，稳落在卡片本体
     * 而不是底部那两颗按钮上。
     */
    @OptIn(ExperimentalTestApi::class)
    private fun injectSwipe(toRight: Boolean) {
        val step = if (toRight) 0.16f else -0.16f
        composeRule.onRoot().performTouchInput {
            down(percentOffset(if (toRight) 0.12f else 0.88f, 0.35f))
            repeat(4) { moveBy(Offset(width * step, 0f)) }
            up()
        }
        composeRule.waitForIdle()
    }

    private companion object {
        /**
         * 把"哪一段红的"写进失败信息。三段共用一个 `@Test`，没有这层包装就只知道一条断言失败，
         * 分不清是量具坏了还是守卫命中 —— 而这两种红的处置完全不一样。
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
 * 只摆卡片本体，不摆整页：整页要 [StudyViewModel]（它背后是 Room），JVM 侧起不来；
 * 而闸门的手势路径全部住在这一个组件里。
 *
 * `key(word.id)` 是**必需的**，不是抄风格：真页面上每张卡都住在 [androidx.compose.animation.AnimatedContent]
 * 的 slot 里，切卡即重建，于是 `offsetX`（那枚飞出用的 `remember { Animatable(0f) }`）跟着归零。
 * 本宿主直接摆组件，不加 key 的话上一段"结算"把卡片飞到 1.5 倍屏宽之外就再也不回来 ——
 * 第三段换的那张新卡会画在屏幕外，注入的手势也落在空处
 * （第一次跑这条守卫时它就是那样红的：`genuine` 报 not displayed）。
 *
 * `reduceMotion = true` 让 [com.studykit.ui.components.AppButton] 的按压 spring 不建补间，
 * 测试规则才等得到 idle；闸门的判定、拖拽手势、翻面那三件事一点没少走。
 */
@Composable
private fun CardHost(
    word: Word,
    revealed: MutableState<Boolean>,
    gateEnabled: Boolean,
    onGateBlocked: () -> Unit,
    onGrade: (Boolean) -> Unit,
) {
    CompositionLocalProvider(
        LocalAppTheme provides AppThemeVals(
            colors = LightColors,
            texts = buildAppTexts(LightColors),
            settings = AppSettings(glass = GlassLevel.OFF, reduceMotion = true),
        ),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            key(word.id) {
                SwipeRatingCard(
                    word = word,
                    revealed = revealed,
                    gateEnabled = gateEnabled,
                    onGateBlocked = onGateBlocked,
                    onGrade = onGrade,
                )
            }
        }
    }
}
