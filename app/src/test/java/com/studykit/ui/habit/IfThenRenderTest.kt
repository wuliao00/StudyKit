package com.studykit.ui.habit

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.studykit.data.AppSettings
import com.studykit.data.GlassLevel
import com.studykit.tips.StudyTips
import com.studykit.tips.TipEvent
import com.studykit.ui.components.TipCard
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
 * 执行意图相关的**渲染守卫**（provider 块逐字复用 [ConfidencePillRenderTest] 那一份）。
 *
 * 覆盖两处真正进语义树的 UI 文案：
 *  1. 创建页那段实时组装的"当…我就…"整句（[IfThenPreview]）；
 *  2. 首次保存带 ifThen 时那张 [科学验证] IF_THEN 贴士卡（spec D4 口径原文，防有人在别处写回被否决的百分比假说法）；
 *  3. **B17 复审 ⚠2**：这张卡的**触发时机**——只在创建页只打字、没按「保存习惯」时不出现，
 *     首次成功保存后出现一次，第二次保存（已看过）不再出现。闸门就是 [ifThenTipShouldShow]，
 *     渲染入口就是列表页那个 [OneShotTipCard]（创建页保存后立即 pop，所以渲染落在返回落点）。
 * 一个测试只做一次 setContent（同 CheckInSheetRenderTest 的跨测试污染纪律）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class IfThenRenderTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `创建页实时预览把 when-where-then 组装成的整句渲染进语义树`() {
        val sentence = IfThenTemplate.compose(whenLabel = "早晨", where = "书桌前", then = "背 10 个单词")
        composeRule.setContent {
            CompositionLocalProvider(
                LocalAppTheme provides AppThemeVals(
                    colors = LightColors,
                    texts = buildAppTexts(LightColors),
                    settings = AppSettings(glass = GlassLevel.OFF, reduceMotion = true),
                ),
            ) {
                IfThenPreview(sentence = sentence)
            }
        }
        composeRule.onNodeWithText(sentence).assertExists()
    }

    @Test
    fun `首次保存触发的那条 IF_THEN 贴士按 D4 口径渲染`() {
        val tip = StudyTips.forEvent(TipEvent.HabitFirstSave)!!
        composeRule.setContent {
            CompositionLocalProvider(
                LocalAppTheme provides AppThemeVals(
                    colors = LightColors,
                    texts = buildAppTexts(LightColors),
                    settings = AppSettings(glass = GlassLevel.OFF, reduceMotion = true),
                ),
            ) {
                TipCard(tip = tip, showEvidence = true)
            }
        }
        composeRule.onNodeWithText(StudyTips.TAG_LABEL).assertExists()
        composeRule.onNodeWithText(tip.text).assertExists()
    }

    // ── ⚠2 触发时机：贴士跟「首次成功保存」走，不跟「正在打字」走 ──────────

    @Test
    fun `只打了字没保存时不渲染 IF_THEN 贴士`() {
        val tip = StudyTips.forEvent(TipEvent.HabitFirstSave)!!
        // 用户在创建页把整句拼出来了，但一下「保存习惯」都没按 —— 计划里这句不该出现
        val typed = IfThenTemplate.compose(whenLabel = "早晨", where = "书桌前", then = "背 10 个单词")
        composeRule.setContent {
            ProvideTheme {
                OneShotTipCard(
                    event = TipEvent.HabitFirstSave,
                    visible = ifThenTipShouldShow(
                        justSavedWithIfThen = false,
                        ifThenText = typed,
                        tipSeen = false,
                    ),
                )
            }
        }
        composeRule.onNodeWithText(tip.text).assertDoesNotExist()
    }

    @Test
    fun `首次成功保存带 ifThen 时渲染一次 IF_THEN 贴士`() {
        val tip = StudyTips.forEvent(TipEvent.HabitFirstSave)!!
        val saved = IfThenTemplate.compose(whenLabel = "早晨", where = "书桌前", then = "背 10 个单词")
        composeRule.setContent {
            ProvideTheme {
                OneShotTipCard(
                    event = TipEvent.HabitFirstSave,
                    visible = ifThenTipShouldShow(
                        justSavedWithIfThen = true,
                        ifThenText = saved,
                        tipSeen = false,
                    ),
                )
            }
        }
        composeRule.onNodeWithText(tip.text).assertExists()
    }

    @Test
    fun `看过之后的第 N 次保存不再渲染 IF_THEN 贴士`() {
        val tip = StudyTips.forEvent(TipEvent.HabitFirstSave)!!
        composeRule.setContent {
            ProvideTheme {
                OneShotTipCard(
                    event = TipEvent.HabitFirstSave,
                    visible = ifThenTipShouldShow(
                        justSavedWithIfThen = true,
                        ifThenText = "当早晨，我就阅读",
                        tipSeen = true,
                    ),
                )
            }
        }
        composeRule.onNodeWithText(tip.text).assertDoesNotExist()
    }

    /** provider 块与上面两个既有测试逐字同一份，只是抽出来免得四处重复（同 [ConfidencePillRenderTest] 那一块） */
    @Composable
    private fun ProvideTheme(content: @Composable () -> Unit) {
        CompositionLocalProvider(
            LocalAppTheme provides AppThemeVals(
                colors = LightColors,
                texts = buildAppTexts(LightColors),
                settings = AppSettings(glass = GlassLevel.OFF, reduceMotion = true),
            ),
            content = content,
        )
    }
}
