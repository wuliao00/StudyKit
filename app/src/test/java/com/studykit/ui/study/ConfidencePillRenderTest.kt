package com.studykit.ui.study

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.studykit.data.AppSettings
import com.studykit.data.GlassLevel
import com.studykit.data.memory.Confidence
import com.studykit.ui.theme.AppThemeVals
import com.studykit.ui.theme.LightColors
import com.studykit.ui.theme.LocalAppTheme
import com.studykit.ui.theme.buildAppTexts
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 信心条的**渲染守卫**：三档文案真组合出来、点其中一档能回调出对应枚举。
 *
 * provider 块逐字复用 [com.studykit.ui.habit.CheckInSheetRenderTest] / [RecallGateRenderTest]
 * 那一份（`AppThemeVals(LightColors, buildAppTexts(LightColors), AppSettings(...))`）——
 * `LocalAppTheme` 虽有默认值，但默认值绑的哪套色没承诺过，跟存量测试走。
 * 一个测试只能 `setContent` 一次，所以三段断言挤进同一个方法（同 CheckInSheetRenderTest 的纪律）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ConfidencePillRenderTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `three tiers render and selection reports back`() {
        var picked: Confidence? = null
        composeRule.setContent {
            CompositionLocalProvider(
                LocalAppTheme provides AppThemeVals(
                    colors = LightColors,
                    texts = buildAppTexts(LightColors),
                    settings = AppSettings(glass = GlassLevel.OFF, reduceMotion = true),
                ),
            ) {
                ConfidenceRow(selected = null, onPick = { picked = it })
            }
        }
        composeRule.onNodeWithText("非常确定").performClick()
        assertEquals(Confidence.SURE, picked)
        composeRule.onNodeWithText("有点印象").assertExists()
        composeRule.onNodeWithText("瞎猜").assertExists()
    }
}
