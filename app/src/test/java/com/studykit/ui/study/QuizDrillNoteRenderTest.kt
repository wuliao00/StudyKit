package com.studykit.ui.study

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.studykit.data.AppSettings
import com.studykit.data.GlassLevel
import com.studykit.data.entity.Question
import com.studykit.data.memory.Confidence
import com.studykit.ui.theme.AppThemeVals
import com.studykit.ui.theme.LightColors
import com.studykit.ui.theme.LocalAppTheme
import com.studykit.ui.theme.buildAppTexts
import org.json.JSONArray
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 会话内 [confusableDrillTip] 那句提示的**渲染守卫**（v3.0-C Task 7b）。
 *
 * ## 它防的是什么（`StudyQueueWiringTest` 测不到）
 * 那一头钉的是 VM 状态里的 `drillIds` 有没有真长出来；这一头钉的是**屏幕上那一行**：
 *  - `currentIsDrill` 接错线（接成 `state.finished`、接成"这一场有过 drill"、或者干脆恒真）——
 *    存储侧全绿、屏幕上要么永远不出现、要么每道题都出现；
 *  - 提示文案在界面里**抄了第二份**：断言用的是 [confusableDrillTip()] 的返回值本身，
 *    抄的那份与纯函数不同就红（诚实版射程：与本仓其余文案守卫同一口径 —— 抄一份**逐字相同**的
 *    拷贝在这里测不出来，那是"不许抄"这条纪律的职责，不是这条断言的职责）；
 *  - 提示挤掉了题本身（只渲染那一行、不渲染 [QuestionView]）：每题都另外断言题干仍在。
 *
 * ## 走 Robolectric、只摆「当前这题」那一支
 * 口径同 [MockExamRenderTest] 的 `ExamHost`：整屏 `QuizScreen` 要 [StudyViewModel] 与 `ImportViewModel`
 * （背后是 Room + OCR），JVM 侧起不来；而"这一行挂在哪张题上"这件事全部住在 [QuizQuestionBranch] 里。
 * 一个 `@Test` 只能 `setContent` 一次，翻页靠测试自己那对 `mutableStateOf` 驱动重组
 * （`onSelect` / `onNext` 就是 VM 的 `selectOption` / `nextQuestion` 在那一侧的替身，
 * 姿势与 [MockExamRenderTest] 的 `selected` / `showResult` 同一族）。
 * `glass = OFF` + `reduceMotion = true` 让按压 spring 不建补间，测试才等得到 idle。
 *
 * ## 位置
 * 答完后「下一题」那颗按钮在长滚动列的折叠线以下 —— 先 `performScrollToNode` 再点（口径同
 * [MockExamRenderTest]），不然失败原因是"看不见"而不是被测行为不对。提示行本身在题目**之上**，
 * 不需要滚动就能断言。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h820dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class QuizDrillNoteRenderTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val first = Question(
        id = 1L,
        uuid = "note-q1",
        subject = "数学",
        stem = "第一题：求二次函数的最小值",
        optionsJson = JSONArray().apply { put("甲"); put("乙"); put("丙"); put("丁") }.toString(),
        answerIndex = 1,
        explanation = "配方法",
    )

    private val second = Question(
        id = 2L,
        uuid = "note-q2",
        subject = "数学",
        stem = "第二题：抛物线开口方向由什么决定",
        optionsJson = JSONArray().apply { put("A"); put("B"); put("C"); put("D") }.toString(),
        answerIndex = 0,
        explanation = "二次项系数",
    )

    private var index by mutableStateOf(0)
    private var selected by mutableStateOf<Int?>(null)
    private var drillIds by mutableStateOf(emptySet<Long>())

    private fun startAt(startIndex: Int, drills: Set<Long>) {
        index = startIndex
        selected = null
        drillIds = drills
        composeRule.setContent {
            DrillNoteHost(
                state = QuizUiState(
                    subject = "数学",
                    questions = listOf(first, second),
                    index = index,
                    selected = selected,
                    drillIds = drillIds,
                ),
                onSelect = { i, _ -> selected = i },
                onNext = {
                    selected = null
                    index += 1
                },
            )
        }
        composeRule.waitForIdle()
    }

    /**
     * 两题、`drillIds = {第 2 题}`：停在第 1 题时那一行**整个不存在**（连 testTag 节点都没有，
     * 不是"渲染了但看不见"），答完第 1 题、按「下一题」走到第 2 题时它才出现。
     * 杀的变异（前两条实跑过，红字见报告）：
     *  - 无条件渲染那一行（MU-N2：条件写成 `if (true)`）⇒ 两条用例同时红（停在第 1 题时 tag 就在那儿）；
     *  - 「这一场有过 drill 就每次都挂」（MU-N1：条件写成 `state.drillIds.isNotEmpty()`）⇒ 本条那句
     *    「停在第 1 题不许有 tag」红，而「没有 drill id 时那一行完全不渲染」照旧绿（它配的就是空集那一侧）；
     *  - `currentIsDrill` 恒假（漏接 / 接成别人的布尔）⇒ 本条后半"第 2 题才出现"那两句红 ——
     *    它与 MU-N2 打的是同一处条件的两端，本轮只实跑了 MU-N2 那一端。
     * 两句"题干仍在"是**量具对照**（宿主没组合成功时它们先红）；"提示挤掉题"那一刀本轮没实跑。
     */
    @Test
    fun `提示只挂在带 drill id 的那张题上`() {
        startAt(startIndex = 0, drills = setOf(2L))
        // 量具对照：题本身没摆出来 = Robolectric 没组合成功，后面所有断言都无效
        composeRule.onNodeWithText("第一题：求二次函数的最小值").assertIsDisplayed()
        composeRule.onNodeWithTag(QuizDrillNoteTag, useUnmergedTree = true).assertDoesNotExist()

        // 答第 1 题：点选项「甲」（correctIndex=1 ⇒ 这是答错，判词出现在下一题之前不影响本条）
        composeRule.onNodeWithText("甲").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("第一题：求二次函数的最小值").assertIsDisplayed()
        // 答完这一题仍然不许挂提示——提示说的是"下一张与上一张像"，挂在刚答过的这张上是剧透
        composeRule.onNodeWithTag(QuizDrillNoteTag, useUnmergedTree = true).assertDoesNotExist()

        // 前进到第 2 题（既有的答题姿势：先滚到折叠线以下的「下一题」再点）
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("下一题"))
        composeRule.onNodeWithText("下一题").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("第二题：抛物线开口方向由什么决定").assertIsDisplayed()
        composeRule.onNodeWithText(confusableDrillTip()).assertIsDisplayed()
        composeRule.onNodeWithTag(QuizDrillNoteTag, useUnmergedTree = true).assertIsDisplayed()
    }

    /**
     * 同一个位置（第 2 题）、`drillIds` 为空 ⇒ 那一行**完全不渲染**：tag 节点不存在，
     * 连子串都搜不到（漏网的第二份文案也会被这一句抓到）。
     * 与上一条构成对照：屏幕上唯一改变的输入就是 `drillIds`。
     */
    @Test
    fun `没有 drill id 时那一行完全不渲染`() {
        startAt(startIndex = 1, drills = emptySet())
        composeRule.onNodeWithText("第二题：抛物线开口方向由什么决定").assertIsDisplayed()
        composeRule.onNodeWithTag(QuizDrillNoteTag, useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithText(confusableDrillTip(), substring = true).assertDoesNotExist()
    }
}

/**
 * 只摆「当前这题」那一支（[QuizQuestionBranch]），不摆整屏：整屏要 [StudyViewModel] 与 `ImportViewModel`，
 * 背后是 Room 与 OCR，JVM 侧起不来。provider 块逐字复用 [MockExamRenderTest] 那份。
 * `confidenceEnabled = false` 把信心条那一行关掉，屏幕上就只剩题干、选项与提示行。
 */
@Composable
private fun DrillNoteHost(
    state: QuizUiState,
    onSelect: (Int, Confidence?) -> Unit,
    onNext: () -> Unit,
) {
    CompositionLocalProvider(
        LocalAppTheme provides AppThemeVals(
            colors = LightColors,
            texts = buildAppTexts(LightColors),
            settings = AppSettings(glass = GlassLevel.OFF, reduceMotion = true),
        ),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            QuizQuestionBranch(
                state = state,
                confidenceEnabled = false,
                onSelect = onSelect,
                onNext = onNext,
            )
        }
    }
}
