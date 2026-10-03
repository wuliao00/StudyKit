package com.studykit.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.studykit.data.AppSettings
import com.studykit.data.GlassLevel
import com.studykit.ui.bulkimport.BulkAction
import com.studykit.ui.bulkimport.BulkEntries
import com.studykit.ui.bulkimport.BulkScreen
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
 * 录入菜单的**渲染守卫**（v2.7.0.1 一键录入）。
 *
 * 决策表（[BulkEntries]）已经由纯单测钉死，这里只补那一层测不到的两件事：
 *  1. 题库那屏的菜单**真的画得出来**，点开之后「拍照录入题目」这一项确实在屏上（接线没接错的证据）；
 *  2. 点它确实把 `QUESTION_CAPTURE` 回报给调用方 —— 也就是相机会被那一击拉起，而不是默默关掉菜单。
 *
 * 只渲染顶栏那一行的菜单组件、不渲染整屏：整屏要 `StudyViewModel`（背后是 Room）与 ImportViewModel
 * （要 StudyKitApp 容器），JVM 侧起不来（口径同 [com.studykit.ui.study.MockExamRenderTest]）。
 * 一个 `@Test` 只 `setContent` 一次。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EntryMenuRenderTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var picked by mutableStateOf<BulkAction?>(null)

    @Test
    fun 题库菜单点开有拍照录入题目点击它回报该动作() {
        picked = null
        composeRule.setContent {
            MenuHost(items = BulkEntries.menuFor(BulkScreen.QUESTION_BANK), onSelect = { picked = it })
        }
        val trigger = composeRule.onNodeWithContentDescription("录入菜单")
        trigger.assertIsDisplayed()
        // 未展开时菜单项不存在（否则用户会以为屏上有一堆按钮）
        composeRule.onNodeWithText("拍照录入题目").assertDoesNotExist()

        trigger.performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("拍照录入题目").assertIsDisplayed()
        composeRule.onNodeWithText("拍照录入题目").performClick()
        composeRule.waitForIdle()

        assertEquals(BulkAction.QUESTION_CAPTURE, picked)
        // 选完必须收起：菜单留在屏上会挡住后面的题目列表
        composeRule.onNodeWithText("批量录入题目").assertDoesNotExist()
    }

    @Test
    fun 入口已齐的屏渲染不出菜单按钮() {
        composeRule.setContent {
            MenuHost(items = BulkEntries.menuFor(BulkScreen.BOOK_SHELF), onSelect = {})
        }
        composeRule.onNodeWithContentDescription("录入菜单").assertDoesNotExist()
    }
}

/** 测试宿主：把菜单放进一条顶栏行里（DropdownMenu 需要锚点），并注入主题。 */
@Composable
private fun MenuHost(
    items: List<BulkAction>,
    onSelect: (BulkAction) -> Unit,
) {
    CompositionLocalProvider(
        LocalAppTheme provides AppThemeVals(
            colors = LightColors,
            texts = buildAppTexts(LightColors),
            settings = AppSettings(glass = GlassLevel.OFF, reduceMotion = true),
        ),
    ) {
        Row {
            EntryMenuButton(items = items, onSelect = onSelect)
        }
    }
}
