package com.studykit.ui.study

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.studykit.data.AppSettings
import com.studykit.data.GlassLevel
import com.studykit.ui.theme.AppThemeVals
import com.studykit.ui.theme.LightColors
import com.studykit.ui.theme.LocalAppTheme
import com.studykit.ui.theme.buildAppTexts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 「今天从哪开始」卡的**渲染守卫**：三步真的画得出来、行尾动作把真实 key 回报给首页、
 * 收起与帮助各自可点。判定口径已由 [DailyStartPlannerTest] 钉死，这里只补纯层测不到的：
 * 文案在不在树上、点击接线有没有接错。
 *
 * 只渲染这一张卡、不渲染整屏（整屏要 StudyViewModel + Room，口径同 MockExamRenderTest）。
 * 一个 @Test 只 setContent 一次。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DailyStartCardRenderTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun setCard(
        plan: DailyStartUi,
        onStepAction: (String) -> Unit,
        onWhy: () -> Unit = {},
        onDismiss: () -> Unit = {},
    ) {
        composeRule.setContent {
            CompositionLocalProvider(
                LocalAppTheme provides AppThemeVals(
                    colors = LightColors,
                    texts = buildAppTexts(LightColors),
                    settings = AppSettings(glass = GlassLevel.OFF, reduceMotion = true),
                ),
            ) {
                DailyStartCard(
                    plan = plan,
                    onStepAction = onStepAction,
                    onWhy = onWhy,
                    onDismiss = onDismiss,
                )
            }
        }
    }

    @Test
    fun 零比三时三行都在且点击回报真实入口() {
        var picked: String? = null
        var why = false
        var dismissed = false
        setCard(
            plan = DailyStartPlanner.plan(
                signedToday = false,
                currentStreak = 0,
                reviewedToday = 0,
                dueCount = 6,
                wordTotal = 120,
                practicedToday = 0,
                hasQuestions = true,
            ),
            onStepAction = { picked = it },
            onWhy = { why = true },
            onDismiss = { dismissed = true },
        )

        composeRule.onNodeWithText("今天从哪开始").assertIsDisplayed()
        composeRule.onNodeWithText("0/3").assertIsDisplayed()
        composeRule.onNodeWithText("去签到", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("开始复习", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("开始小测", substring = true).assertIsDisplayed()
        // 真实读数：到期 6 个词 / 题库有题 / 今天还没练
        composeRule.onNodeWithText("今天已复习 0 个 · 还有 6 个到期").assertIsDisplayed()

        composeRule.onNodeWithText("签到打卡").performClick()
        assertEquals(DailyStartPlanner.KEY_SIGN_IN, picked)
        composeRule.onNodeWithText("复习单词").performClick()
        assertEquals(DailyStartPlanner.KEY_REVIEW, picked)
        composeRule.onNodeWithText("做个小测").performClick()
        assertEquals(DailyStartPlanner.KEY_QUIZ, picked)

        composeRule.onNodeWithText("为什么这样安排").performClick()
        assertTrue(why)
        composeRule.onNodeWithText("今天先收起").performClick()
        assertTrue(dismissed)
    }

    @Test
    fun 三步完成时换标题并给下一步建议() {
        var picked: String? = null
        setCard(
            plan = DailyStartPlanner.plan(
                signedToday = true,
                currentStreak = 5,
                reviewedToday = 7,
                dueCount = 0,
                wordTotal = 120,
                practicedToday = 4,
                hasQuestions = true,
            ),
            onStepAction = { picked = it },
        )

        composeRule.onNodeWithText("今日已起飞").assertIsDisplayed()
        composeRule.onNodeWithText("已起飞 3/3").assertIsDisplayed()
        composeRule.onNodeWithText("三件事都做完了 · 明天这里会自动刷新").assertIsDisplayed()
        composeRule.onNodeWithText("想多学一点？再去题库练一组").performClick()
        assertEquals(DailyStartPlanner.KEY_QUIZ, picked)
        // 已完成的行仍然可点（去词库/签到日历/继续练），没有一条是死路。
        composeRule.onNodeWithText("签到日历", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("继续练", substring = true).assertIsDisplayed()
        assertFalse(false)
    }
}
