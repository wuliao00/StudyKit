package com.studykit.ui.study

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
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
 *
 * 考卷与成绩页都是**长滚动列**：折叠线以下的节点要先 `onNode(hasScrollAction()).performScrollToNode(...)`
 * 再断言可见 / 点击 —— 没滚到位的节点，`assertIsDisplayed` 与 `performClick` 都会以「看不见」为由
 * 失败，那不是被测行为不对。只需要「在树上出现过」的那些（本文件对「交卷」的老口径）仍用 `assertExists`。
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
    fun 无遮挡的作答页不出现待批改提示() {
        // 待批改那一栏只在成绩页出现：作答页提前告诉用户"这几题没答"，等于把交卷前的
        // 空缺当成反馈漏出去（延迟反馈承诺的另一半）
        composeRule.setContent {
            ExamHost(
                question = question,
                selected = null,
                showResult = false,
                onAnswer = {},
                remainingLabel = "44:10",
            )
        }
        composeRule.onNodeWithText("下面哪个数最大？").assertIsDisplayed()
        composeRule.onNodeWithText("待批改", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("判了", substring = true).assertDoesNotExist()
    }

    @Test
    fun 结果页把未答的题列进待批改而不判成错() {
        // 20 题只答 1 题（且答对）：待批改那一栏出现 19 题，分数栏的分母是"判了 1 题"，
        // 屏幕不许出现「答对 1 / 20 题」那种把空白当答错的百分比（R2）。
        val paper = List(20) { question.copy(id = (it + 1).toLong()) }
        composeRule.setContent {
            ExamHost(
                question = question,
                selected = question.answerIndex,
                showResult = true,
                onAnswer = {},
                resultQuestions = paper,
                resultAnswers = mapOf(question.id to MockExamState.Answer(question.answerIndex, correct = true)),
                pendingGrading = paper.filter { it.id != question.id },
            )
        }
        composeRule.onNodeWithText("答对 1 / 1 题").assertIsDisplayed()
        composeRule.onNodeWithText("判了 1 题 · 未答 19 题").assertIsDisplayed()
        // 待批改那一栏在判词卡之下（折叠线以下）：先把它滚进可视区再断言可见。
        // 两句各滚一次：`performScrollToNode` 只把目标滚到视口边上，紧跟其后的那一行仍会在折叠线外
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("待批改 19 题"))
        composeRule.onNodeWithText("待批改 19 题").assertIsDisplayed()
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("这些题交卷时是空的，判不了"))
        composeRule.onNodeWithText("这些题交卷时是空的，判不了").assertIsDisplayed()
        // 旧分母（整卷题数）不能再出现在树上
        composeRule.onNodeWithText("答对 1 / 20 题").assertDoesNotExist()
        // 未答 ≠ 答错：一题没判错就不许出现"错了 N 道"那句
        composeRule.onNodeWithText("错了", substring = true).assertDoesNotExist()
        // 这一卷是用户自己交的，不是时间到
        composeRule.onNodeWithText("时间到，已自动交卷").assertDoesNotExist()
    }

    @Test
    fun 自动交卷的结果页标注时间到() {
        composeRule.setContent {
            ExamHost(
                question = question,
                selected = 0,                       // 答案下标是 1 → 判错
                showResult = true,
                onAnswer = {},
                autoSubmitted = true,
            )
        }
        composeRule.onNodeWithText("时间到，已自动交卷").assertIsDisplayed()
        composeRule.onNodeWithText("答对 0 / 1 题").assertIsDisplayed()
    }

    @Test
    fun 答错的题给出建卡出口并带上学科() {
        val wrong = List(2) { question.copy(id = (it + 1).toLong()) }
        val picked = mutableListOf<String>()
        composeRule.setContent {
            ExamHost(
                question = question,
                selected = 0,
                showResult = true,
                onAnswer = {},
                resultQuestions = wrong,
                resultAnswers = wrong.associate { it.id to MockExamState.Answer(0, correct = false) },
                onGoCapture = { picked.add(it) },
            )
        }
        // 建卡那一行在分数卡之下、折叠线附近：先滚进可视区，否则 isDisplayed 与 performClick
        // 只会以"看不见"为由失败（那不是被测行为不对）
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("去拍这几道"))
        composeRule.onNodeWithText("错了 2 道，去建卡？（拍照 → 预览 → 确认）").assertIsDisplayed()
        composeRule.onNodeWithText("去拍这几道").performClick()
        composeRule.waitForIdle()
        assertEquals("恰好一次，且带的是答错那些题的学科", listOf("数学"), picked)
        // 0 题未答时不该有"待批改 0 题"这种空栏
        composeRule.onNodeWithText("待批改", substring = true).assertDoesNotExist()
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

    /**
     * ⛔ 评审 F1 的渲染守卫：那张卡得把"按下继续之后会剩下多少"写在脸上。
     *
     * 喂给卡片的读数由纯函数 [pausedRemainingLabel] 现算 —— 与 `StudyViewModel.resumeMockExam`
     * 恢复时用的是同一条算术（同一个 `deadlineAfterResume`），于是屏幕上那个数和 VM 真正
     * 算出来的截止时刻不可能分家。场景：10:00 暂停（那时还剩 5 分钟）、11:00 回到页面。
     * 以前这里念的是**冻住的 00:00**（截止时刻与恢复时刻之差），而按下「继续作答」后的第一个
     * tick 就把整卷判了 —— 判分无声发生，用户只知道自己"好像还没答就交卷了"。
     */
    @Test
    fun 恢复提示念的是继续之后真正剩下的时间() {
        val pausedAt = 1_000_000L                 // 暂停那一刻（库里 paused_at 的值）
        val deadline = pausedAt + 5 * 60_000L      // 暂停时手里还剩 5 分钟
        val back = pausedAt + 3_600_000L           // 一小时后才回到页面
        composeRule.setContent {
            ResumeHost(
                state = MockExamUiState(
                    subject = "数学",
                    questions = listOf(question),
                    examName = "期末考",
                    deadlineTs = deadline,
                    paused = true,
                    nowTs = back,
                    pausedAtTs = pausedAt,
                ),
                remainingLabel = pausedRemainingLabel(back, pausedAt, deadline, pauseCountsTime = false),
                onResume = {},
                onDiscard = {},
            )
        }
        // 量具对照：这张卡没组合成功，后面那句就没有意义
        composeRule.onNodeWithText("上次那卷还没交").assertIsDisplayed()
        // 停表补得回来：卡上念 5 分钟，而不是冻在恢复那一刻的 00:00
        composeRule.onNodeWithText("计时已停下，剩余 05:00").assertIsDisplayed()
        composeRule.onNodeWithText("剩余 00:00").assertDoesNotExist()
        // 这张卡仍然只有续与弃两个出口（评审 F1 没把"交卷"从这里漏出去）
        composeRule.onNodeWithText("交卷").assertDoesNotExist()
    }

    /**
     * 时间真的在外面跑完了（设置里"暂停也扣时"）：卡上照实念 00:00 —— 这句话就是那次判分的预告。
     * 「继续作答」按下去走的是 `submitMockExam(auto = true)`，成绩页顶上印「时间到，已自动交卷」；
     * 那一步在 VM 层由 `MockSessionResumeWriterTest` 钉死，这里钉的是**用户按之前看得见什么**。
     */
    @Test
    fun 时间在外面跑完时恢复提示照实念零() {
        val pausedAt = 1_000_000L
        val deadline = pausedAt + 5 * 60_000L
        val back = pausedAt + 3_600_000L
        composeRule.setContent {
            ResumeHost(
                state = MockExamUiState(
                    subject = "数学",
                    questions = listOf(question),
                    deadlineTs = deadline,
                    paused = true,
                    nowTs = back,
                    pausedAtTs = pausedAt,
                ),
                remainingLabel = pausedRemainingLabel(back, pausedAt, deadline, pauseCountsTime = true),
                onResume = {},
                onDiscard = {},
            )
        }
        composeRule.onNodeWithText("计时已停下，剩余 00:00").assertIsDisplayed()
    }
}

/**
 * 恢复提示的测试宿主：把 [MockExamResumePrompt] 单独摆进主题里
 * （口径同 [MockExamBody] —— 整屏要 Room 背后的 [StudyViewModel]，JVM 侧起不来）。
 *
 * `remainingLabel` 默认给既有用例那句 "44:10"（别为了新用例去改老用例的期望），新用例传
 * 纯函数现算出来的那一份，钉的就是"页面喂进卡片的是恢复后的读数"这一根线。
 */
@Composable
private fun ResumeHost(
    state: MockExamUiState,
    onResume: () -> Unit,
    onDiscard: () -> Unit,
    remainingLabel: String? = "44:10",
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
            remainingLabel = remainingLabel,
            onResume = onResume,
            onDiscard = onDiscard,
        )
    }
}

/**
 * 测试宿主：交卷前渲染考卷、交卷后渲染结果，二者共用同一份主题 provider。
 * `selected` / `showResult` 由测试类字段驱动重组（一次 `setContent` 内换屏的唯一姿势）。
 *
 * 结果页那三个参数（[resultQuestions] / [resultAnswers] / [pendingGrading]）默认给"既有用例的那一卷"
 * —— 单题、判词按 `selected` 现算、没有待批改 —— 这样 T12 之前那条用例一个字都不用改。
 */
@Composable
private fun ExamHost(
    question: Question,
    selected: Int?,
    showResult: Boolean,
    onAnswer: (Int) -> Unit,
    remainingLabel: String? = null,
    paused: Boolean = false,
    resultQuestions: List<Question> = emptyList(),
    resultAnswers: Map<Long, MockExamState.Answer>? = null,
    pendingGrading: List<Question> = emptyList(),
    autoSubmitted: Boolean = false,
    onGoCapture: (String) -> Unit = {},
) {
    CompositionLocalProvider(
        LocalAppTheme provides AppThemeVals(
            colors = LightColors,
            texts = buildAppTexts(LightColors),
            settings = AppSettings(glass = GlassLevel.OFF, reduceMotion = true),
        ),
    ) {
        if (showResult) {
            val questions = resultQuestions.ifEmpty { listOf(question) }
            ExamResultBody(
                questions = questions,
                results = resultAnswers ?: mapOf(
                    question.id to MockExamState.Answer(
                        selected = selected,
                        correct = selected == question.answerIndex,
                    ),
                ),
                pendingGrading = pendingGrading,
                autoSubmitted = autoSubmitted,
                onGoCapture = onGoCapture,
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
