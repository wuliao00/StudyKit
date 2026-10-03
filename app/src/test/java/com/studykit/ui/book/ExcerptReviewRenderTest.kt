package com.studykit.ui.book

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.studykit.data.AppSettings
import com.studykit.data.GlassLevel
import com.studykit.data.entity.ChapterTest
import com.studykit.data.entity.Excerpt
import com.studykit.data.memory.ReviewGrade
import com.studykit.data.memory.excerptFrontHalf
import com.studykit.tips.StudyTips
import com.studykit.tips.TipEvent
import com.studykit.tips.TipId
import com.studykit.ui.components.TipCard
import com.studykit.ui.theme.AppThemeVals
import com.studykit.ui.theme.LightColors
import com.studykit.ui.theme.LocalAppTheme
import com.studykit.ui.theme.buildAppTexts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Task 18 的**渲染守卫**（provider 块逐字复用 [com.studykit.ui.habit.IfThenRenderTest] 那一份纪律）。
 *
 * 钉三件纯函数 / DAO 测不到的事：
 *  1. 书摘复习的「先遮后半」这条线真的接上了——未展开只露前半截、看不到自评按钮，展开后才出全文与三档；
 *  2. 章节自测的「先回忆再对照」同理——未展开不露参考答案，展开后自评布尔回调拿得到 `true`；
 *  3. 两个事件映射到正确的贴士：`ChapterFinished → EXPLAIN_WHY`、`ExcerptOnlyNoRecall → RECALL_NOTES`，
 *     且文案确实进语义树（防止有人在别处写回被否决的措辞）。
 *
 * 排期数值（S 变硬 / nextReviewAt / 超纠正 min-only）由 `ExcerptReviewTest` 的纯函数用例覆盖，
 * 主指标聚合由 `RetrievalCountTest` 的 in-memory Room 用例覆盖，这里不重复。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExcerptReviewRenderTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val excerpt = Excerpt(
        uuid = "e-1",
        bookId = 1L,
        content = "记忆巩固靠的是间隔与提取，" + "而不是反复重读同一页——这句话后半截故意留长，用来验证遮挡。",
        pageNo = 63,
    )

    @Test
    fun `书摘复习未展开时只露前半截且看不到自评按钮`() {
        val front = excerptFrontHalf(excerpt.content)
        composeRule.setContent {
            ProvideTheme {
                ExcerptReviewBody(
                    excerpt = excerpt,
                    revealed = false,
                    selectedConfidence = null,
                    confidenceEnabled = true,
                    onReveal = {},
                    onPickConfidence = {},
                    onGrade = {},
                    onOptOut = {},
                )
            }
        }
        composeRule.onNodeWithText(front).assertExists()
        composeRule.onNodeWithText("展开全文对照").assertExists()
        // 未展开：既不该看到全文，也不该看到任何一档自评
        composeRule.onNodeWithText(excerpt.content).assertDoesNotExist()
        composeRule.onNodeWithText("记得").assertDoesNotExist()
    }

    @Test
    fun `书摘复习展开后露全文并出现三档，点记得回调收到 RECALL`() {
        var revealed by mutableStateOf(false)
        var lastGrade: ReviewGrade? = null
        composeRule.setContent {
            ProvideTheme {
                ExcerptReviewBody(
                    excerpt = excerpt,
                    revealed = revealed,
                    selectedConfidence = null,
                    confidenceEnabled = true,
                    onReveal = { revealed = true },
                    onPickConfidence = {},
                    onGrade = { lastGrade = it },
                    onOptOut = {},
                )
            }
        }
        composeRule.onNodeWithText("展开全文对照").performClick()
        composeRule.onNodeWithText(excerpt.content).assertExists()
        composeRule.onNodeWithText("记得").performClick()
        assertEquals(ReviewGrade.RECALL, lastGrade)
    }

    @Test
    fun `章节自测未展开不露参考答案，展开后自评记住回调拿到 true`() {
        val test = ChapterTest(
            uuid = "c-1",
            bookId = 1L,
            chapterLabel = "第三章 · 记忆的巩固",
            question = "巩固记忆的两根支柱是什么？",
            expectedAnswer = "间隔重复与主动提取",
            passed = false,
        )
        var revealed by mutableStateOf(false)
        var graded: Boolean? = null
        composeRule.setContent {
            ProvideTheme {
                ChapterTestQuestionBody(
                    test = test,
                    revealed = revealed,
                    onReveal = { revealed = true },
                    onGrade = { graded = it },
                )
            }
        }
        composeRule.onNodeWithText(test.question).assertExists()
        composeRule.onNodeWithText(test.expectedAnswer).assertDoesNotExist()
        composeRule.onNodeWithText("先在脑子里答一遍，再展开对照").performClick()
        composeRule.onNodeWithText(test.expectedAnswer).assertExists()
        composeRule.onNodeWithText("记住了").performClick()
        assertEquals(true, graded)
    }

    @Test
    fun `ChapterFinished 映射 EXPLAIN_WHY 并渲染贴士卡`() {
        val tip = StudyTips.forEvent(TipEvent.ChapterFinished)
        assertNotNull(tip)
        assertEquals(TipId.EXPLAIN_WHY, tip!!.id)
        composeRule.setContent {
            ProvideTheme { TipCard(tip = tip, showEvidence = true) }
        }
        composeRule.onNodeWithText(StudyTips.TAG_LABEL).assertExists()
        composeRule.onNodeWithText(tip.text).assertExists()
    }

    @Test
    fun `ExcerptOnlyNoRecall 映射 RECALL_NOTES 并渲染贴士卡`() {
        val tip = StudyTips.forEvent(TipEvent.ExcerptOnlyNoRecall)
        assertNotNull(tip)
        assertEquals(TipId.RECALL_NOTES, tip!!.id)
        composeRule.setContent {
            ProvideTheme { TipCard(tip = tip) }
        }
        composeRule.onNodeWithText(tip.text).assertExists()
    }

    /** provider 块与既有两条 render 测试逐字同一份（同 [com.studykit.ui.habit.IfThenRenderTest]） */
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
