package com.studykit.ui.study

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.studykit.data.AppSettings
import com.studykit.data.GlassLevel
import com.studykit.data.entity.Question
import com.studykit.ui.theme.AppThemeVals
import com.studykit.ui.theme.LightColors
import com.studykit.ui.theme.LocalAppTheme
import com.studykit.ui.theme.buildAppTexts
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 模考的**渲染守卫**：这一条就是"延迟反馈"承诺的机检证据。
 *
 * 交卷前在考卷上作答，屏幕上【不许】出现任何判对错的文案（`QuizFeedback` 的
 * "回答正确 / 回答错误" 那句任务级判定）——出现即说明有人把练习模式的即时反馈
 * 手滑接回了模考页，模考就不再是模考。交卷进到结果页之后，同一套判定【必须】出现。
 *
 * 一个 `@Test` 只 `setContent` 一次（口径同 [RecallGateRenderTest] / [ConfidencePillRenderTest]）：
 * 前一段渲染考卷（[MockExamBody]）、点一个错误选项、断言没有判词；翻一个状态标记切到
 * 结果页（[ExamResultBody]）、断言判词浮现。provider 块逐字复用信心条那份。
 *
 * 只渲染这两段内部 Composable、不渲染整屏：整屏要 [StudyViewModel]（背后是 Room），
 * JVM 侧起不来；而"反馈在不在"这件事全部住在这两段里。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MockExamRenderTest {

    @get:Rule
    val composeRule = createComposeRule()

    // 答案下标 1（"乙"）；测试里点下标 0（"甲"）= 答错，交卷后判词应是"回答错误…"。
    private val question = Question(
        id = 1L,
        uuid = "u1",
        subject = "数学",
        stem = "下面哪个数最大？",
        optionsJson = JSONArray().apply { put("甲"); put("乙"); put("丙"); put("丁") }.toString(),
        answerIndex = 1,
        explanation = "按大小比较，乙最大。",
    )

    private var selected by mutableStateOf<Int?>(null)
    private var showResult by mutableStateOf(false)

    @Test
    fun 交卷前答一题不出判词_交卷后结果页才出现判词() {
        composeRule.setContent {
            ExamHost(
                question = question,
                selected = selected,
                showResult = showResult,
                onAnswer = { selected = it },
            )
        }
        composeRule.waitForIdle()

        // ── 量具对照：考卷没摆出来 = Robolectric 没组合成功，后面断言无效 ──
        // assertIsDisplayed 不成立会直接抛，这一句本身就是"量具可信"的闸门。
        composeRule.onNodeWithText("下面哪个数最大？").assertIsDisplayed()

        // ── ① 交卷前：顶栏「交卷」在（考卷体是可滚动的长列，交卷按钮落在折叠线以下，
        //    故按 spec「onNodeWithText("交卷") 存在」的口径断言它在树里，位置无关），但没有任何判词 ──
        composeRule.onNodeWithText("交卷").assertExists()
        composeRule.onNodeWithText("回答错误", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("回答正确", substring = true).assertDoesNotExist()

        // 点一个【错误】选项（甲 = 下标 0，正确答案是乙 = 下标 1）
        composeRule.onNodeWithText("甲").performClick()
        composeRule.waitForIdle()

        // ── ② 作答之后、交卷之前：依旧不许出现判词（这就是延迟反馈的机检点）──
        composeRule.onNodeWithText("回答错误", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("回答正确", substring = true).assertDoesNotExist()

        // ── ③ 交卷进结果页：判词浮现（同样位置无关——判词在长滚动结果里，故 assertExists 钉"出现了"这件事）──
        showResult = true
        composeRule.waitForIdle()
        composeRule.onNodeWithText("回答错误", substring = true).assertExists()
    }

    @Test
    fun 限时卷在作答页显示剩余时间那一格() {
        composeRule.setContent {
            ExamHost(
                question = question,
                selected = null,
                showResult = false,
                onAnswer = {},
                remainingLabel = "45:00",
            )
        }
        // 量具对照：考卷没摆出来 = Robolectric 没组合成功，后面那句没有意义
        composeRule.onNodeWithText("下面哪个数最大？").assertIsDisplayed()
        composeRule.onNodeWithText("剩余 45:00").assertIsDisplayed()
    }

    @Test
    fun 不限时卷不显示剩余那一格() {
        // 设置的 examLimitMinutes=0 时 machine 给 null 文案，页面就不许出现那一格
        // （「剩余 00:00」会被读成"时间到了"，而这一卷压根没限时）
        composeRule.setContent {
            ExamHost(question = question, selected = null, showResult = false, onAnswer = {})
        }
        composeRule.onNodeWithText("下面哪个数最大？").assertIsDisplayed()
        composeRule.onNodeWithText("剩余", substring = true).assertDoesNotExist()
    }

    @Test
    fun 暂停态把交卷按钮换成继续() {
        // paused=true 时「交卷」不该在树里，「继续」必须在 —— 暂停中还能交卷是个真事故：
        // 用户以为停表了，结果按下的是判分。
        composeRule.setContent {
            ExamHost(
                question = question,
                selected = null,
                showResult = false,
                onAnswer = {},
                remainingLabel = "44:10",
                paused = true,
            )
        }
        composeRule.onNodeWithText("已暂停").assertIsDisplayed()
        composeRule.onNodeWithText("交卷").assertDoesNotExist()
        // 「继续」在长滚动卷面的折叠线以下，口径同本文件对「交卷」的那句：assertExists 钉"在树里"
        composeRule.onNodeWithText("继续").assertExists()
        // 暂停那一格顶替剩余：同时出现两个时间读数，用户不知道看哪个
        composeRule.onNodeWithText("剩余", substring = true).assertDoesNotExist()
    }

    @Test
    fun 恢复提示只给继续与丢弃两个出口() {
        var resumed = 0
        var discarded = 0
        composeRule.setContent {
            ResumeHost(
                state = MockExamUiState(
                    subject = "数学",
                    questions = List(20) { question.copy(id = it.toLong()) },
                    answers = mapOf(1L to 0, 2L to 1),
                    index = 3,
                    sessionId = 7L,
                    examName = "期末考",
                    deadlineTs = 46_000_000L,
                    paused = true,
                ),
                onResume = { resumed++ },
                onDiscard = { discarded++ },
            )
        }
        composeRule.onNodeWithText("上次那卷还没交").assertIsDisplayed()
        composeRule.onNodeWithText("期末考 · 数学").assertIsDisplayed()
        composeRule.onNodeWithText("已答 2 / 20 题").assertIsDisplayed()
        composeRule.onNodeWithText("计时已停下，剩余 44:10").assertIsDisplayed()
        composeRule.onNodeWithText("继续作答").performClick()
        composeRule.onNodeWithText("丢弃本卷").performClick()
        composeRule.waitForIdle()
        assertEquals("两个按钮各恰好回调一次", 1, resumed)
        assertEquals("两个按钮各恰好回调一次", 1, discarded)
        // 提示卡上没有交卷入口：暂停中的卷必须先选「继续」才谈得上交卷
        composeRule.onNodeWithText("交卷").assertDoesNotExist()
    }
}

/**
 * 恢复提示的测试宿主：把 [MockExamResumePrompt] 单独摆进主题里
 * （口径同 [MockExamBody] —— 整屏要 Room 背后的 [StudyViewModel]，JVM 侧起不来）。
 */
@Composable
private fun ResumeHost(
    state: MockExamUiState,
    onResume: () -> Unit,
    onDiscard: () -> Unit,
) {
    CompositionLocalProvider(
        LocalAppTheme provides AppThemeVals(
            colors = LightColors,
            texts = buildAppTexts(LightColors),
            settings = AppSettings(glass = GlassLevel.OFF, reduceMotion = true),
        ),
    ) {
        MockExamResumePrompt(
            state = state,
            remainingLabel = "44:10",
            onResume = onResume,
            onDiscard = onDiscard,
        )
    }
}

/**
 * 测试宿主：交卷前渲染考卷、交卷后渲染结果，二者共用同一份主题 provider。
 * `selected` / `showResult` 由测试类字段驱动重组（一次 `setContent` 内换屏的唯一姿势）。
 */
@Composable
private fun ExamHost(
    question: Question,
    selected: Int?,
    showResult: Boolean,
    onAnswer: (Int) -> Unit,
    remainingLabel: String? = null,
    paused: Boolean = false,
) {
    CompositionLocalProvider(
        LocalAppTheme provides AppThemeVals(
            colors = LightColors,
            texts = buildAppTexts(LightColors),
            settings = AppSettings(glass = GlassLevel.OFF, reduceMotion = true),
        ),
    ) {
        if (showResult) {
            ExamResultBody(
                questions = listOf(question),
                results = mapOf(
                    question.id to MockExamState.Answer(
                        selected = selected,
                        correct = selected == question.answerIndex,
                    ),
                ),
                onBack = {},
            )
        } else {
            MockExamBody(
                question = question,
                selectedIndex = selected,
                answeredCount = if (selected != null) 1 else 0,
                position = 0,
                total = 1,
                remainingLabel = remainingLabel,
                paused = paused,
                onAnswer = onAnswer,
                onPrev = {},
                onNext = {},
                onSubmit = {},
                onPause = {},
                onResume = {},
            )
        }
    }
}
