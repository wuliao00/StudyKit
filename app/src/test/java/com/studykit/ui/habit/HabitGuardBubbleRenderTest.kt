package com.studykit.ui.habit

import androidx.compose.foundation.layout.Column
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
 * 断签保护展示层的**渲染守卫**（provider 块逐字复用 [ConfidencePillRenderTest] / [IfThenRenderTest] 那一份）。
 *
 * 钉三处真正进语义树的 UI 文案（一个方法一次 setContent，多段靠自定义消息区分，同 CheckInSheetRenderTest 纪律）：
 *  1. 日历日详情气泡 —— `MISSING_GUARD_TEXT` 那句「未打卡（已用断签保护）」（[GuardDayBubble]）；
 *  2. 打卡界面检测到昨日缺卡时挂的 MISS_ONE_DAY 贴士（[TipEvent.GapDay]）；
 *  3. 首页 hero 连续 21 天挂的 SIXTY_SIX 贴士（[TipEvent.StreakReached]）。
 * 后两条把「死码变活」这件事从映射层（StudyTipsTest 已钉）延伸到渲染层：文案改一个字、卡片不进树都会红。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HabitGuardBubbleRenderTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `断签保护气泡与两张宽恕_六六贴士都渲染进语义树`() {
        val gapTip = StudyTips.forEvent(TipEvent.GapDay)!!
        val sixTip = StudyTips.forEvent(TipEvent.StreakReached(StudyTips.STREAK_MYTH_DAY))!!
        composeRule.setContent {
            CompositionLocalProvider(
                LocalAppTheme provides AppThemeVals(
                    colors = LightColors,
                    texts = buildAppTexts(LightColors),
                    settings = AppSettings(glass = GlassLevel.OFF, reduceMotion = true),
                ),
            ) {
                Column {
                    GuardDayBubble(text = HabitGuard.MISSING_GUARD_TEXT)
                    TipCard(tip = gapTip, showEvidence = true)
                    TipCard(tip = sixTip, showEvidence = true)
                }
            }
        }
        composeRule.onNodeWithText(HabitGuard.MISSING_GUARD_TEXT)
            .assertExists("断签保护气泡文案没进语义树")
        composeRule.onNodeWithText(gapTip.text)
            .assertExists("MISS_ONE_DAY（GapDay）贴士文案没进语义树")
        composeRule.onNodeWithText(sixTip.text)
            .assertExists("SIXTY_SIX（StreakReached 21）贴士文案没进语义树")
    }
}
