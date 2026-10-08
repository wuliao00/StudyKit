package com.studykit.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.studykit.data.AppSettings
import com.studykit.data.GlassLevel
import com.studykit.data.memory.ExamEntry
import com.studykit.ui.theme.AppThemeVals
import com.studykit.ui.theme.LightColors
import com.studykit.ui.theme.LocalAppTheme
import com.studykit.ui.theme.buildAppTexts
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 考试清单行的**渲染守卫**（v3.0-B T9）。
 *
 * 只渲染 [ExamRowBlock]，不构造 [SettingsViewModel]（背后是 Room，JVM 侧起不来；仓里没有 VM 级
 * 测试先例）—— 同 [SettingsBehaviorRenderTest] / [com.studykit.ui.study.MockExamRenderTest] 的手法：
 * Robolectric + `@GraphicsMode(NATIVE)` + 手写 `LocalAppTheme provides AppThemeVals(...)` 宿主。
 *
 * 纯函数那半边（解析/校验/文案）由 `ExamEditorTest` 逐字钉住；这里管的是**接上屏之后**才成立的三件事：
 * ① 五个字段 + ramp 说明真画出来了（字段接错线、说明漏渲染，纯函数全绿界面照样坏）；
 * ② 坏草稿不落库：日期填废后把 450ms 防抖整段跨过去（虚拟时钟显式推进），`onPatch` 仍不被调；
 * ③ 好草稿会落库：改完名字防抖走完后恰好回调一次，其余字段原样带着。
 *
 * 断言里的说明句是**字面量**，不调 `rampHint` 现取 —— 文案被改了要连同这条守卫一起改（本仓口径）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h820dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsScreenExamsRenderTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val entry = ExamEntry(
        name = "期末考",
        epochDay = LocalDate.parse("2026-12-19").toEpochDay(),
        subjects = listOf("数学", "物理"),
        rSprint = 0.95,
        windowDays = 21,
    )

    @Test
    fun `清单行渲染出五个字段与 ramp 说明，删除按钮存在`() {
        val patches = mutableListOf<ExamEntry>()
        composeRule.setContent { ExamRowHost(entry = entry, onPatch = { patches.add(it) }, onRemove = {}) }
        composeRule.waitForIdle()

        // 量具对照：连行标题都没摆出来 = Robolectric 没组合成功，后面的断言全无效
        composeRule.onNodeWithText("第 1 场").assertIsDisplayed()
        composeRule.onNodeWithText("期末考").assertIsDisplayed()
        composeRule.onNodeWithText("2026-12-19").assertIsDisplayed()
        composeRule.onNodeWithText("数学、物理").assertIsDisplayed()
        composeRule.onNodeWithText("95").assertIsDisplayed()
        composeRule.onNodeWithText("21").assertIsDisplayed()
        // placeholder 填了字就没了，label 是认出并排两格是谁的唯一信息源 —— 必须在屏上
        composeRule.onNodeWithText("冲刺保留 %").assertIsDisplayed()
        composeRule.onNodeWithText("提前天数").assertIsDisplayed()
        composeRule.onNodeWithText(
            "考前 21 天内，这科卡片的目标保留率线性提到冲刺值；复习量会涨，曲线里能看到涌量。",
        ).assertIsDisplayed()
        composeRule.onNodeWithText("删除").assertExists()

        // 静置不写：edited 恒 false，防抖协程一进 LaunchedEffect 就 return
        assertTrue(patches.isEmpty())
    }

    @Test
    fun `日期填错时那一格标错且不回调`() {
        val patches = mutableListOf<ExamEntry>()
        // 显式驱动虚拟时钟：仓里还没有 mainClock 先例，这里补一个 —— waitForIdle 不保证 450ms
        // 防抖已过，只有把时间整段跨过去，才钉得住"坏草稿永不落库"。
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent { ExamRowHost(entry = entry, onPatch = { patches.add(it) }, onRemove = {}) }
        composeRule.waitForIdle()

        // 先清空再整段敲入：插入光标的位置不保证，清空后输入才让字段终值是确定的
        val dateField = composeRule.onNodeWithText("2026-12-19")
        dateField.performTextClearance()
        dateField.performTextInput("乱七八糟")
        composeRule.waitForIdle()

        // 把 450ms 防抖整段跨过去并跑掉挂起的协程与下一帧：坏草稿到点也不许落库。
        // 错误串的 assertIsDisplayed 放在跨帧之后 —— autoAdvance=false 时 waitForIdle 只做重组
        // 不产出新帧，未量位置的节点会被判成"没显示"。
        composeRule.mainClock.advanceTimeBy(500_000_000L)
        composeRule.waitForIdle()

        // 逐格错误串落在本格下面（字面量，与 ExamEditorTest 同一句）
        composeRule.onNodeWithText("yyyy-MM-dd 填", substring = true).assertIsDisplayed()
        assertEquals(0, patches.size)
    }

    @Test
    fun `改对名字后防抖走完恰好回调一次`() {
        val patches = mutableListOf<ExamEntry>()
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent { ExamRowHost(entry = entry, onPatch = { patches.add(it) }, onRemove = {}) }
        composeRule.waitForIdle()

        val nameField = composeRule.onNodeWithText("期末考")
        nameField.performTextClearance()
        nameField.performTextInput("期末考试")
        composeRule.waitForIdle()
        // 防抖未到期：不该已经回调
        assertTrue(patches.isEmpty())

        composeRule.mainClock.advanceTimeBy(500_000_000L)
        composeRule.waitForIdle()

        assertEquals(1, patches.size)
        val patched = patches[0]
        assertEquals("期末考试", patched.name)
        // 没动的字段原样带过（fallback 取的是本行 entry，不是默认值）
        assertEquals(entry.epochDay, patched.epochDay)
        assertEquals(entry.subjects, patched.subjects)
        assertEquals(entry.rSprint, patched.rSprint, 1e-9)
        assertEquals(entry.windowDays, patched.windowDays)
    }
}

/**
 * 只摆清单这一行，不摆整页：整页要 [SettingsViewModel]，JVM 侧起不来。
 * provider 块逐字复用 [SettingsBehaviorRenderTest] 那份 `AppThemeVals(LightColors, buildAppTexts(...),
 * AppSettings(glass = OFF, reduceMotion = true))`；`reduceMotion = true` 让按压 spring 不建补间，
 * 测试才等得到 idle。
 */
@Composable
private fun ExamRowHost(
    entry: ExamEntry,
    onPatch: (ExamEntry) -> Unit,
    onRemove: () -> Unit,
) {
    CompositionLocalProvider(
        LocalAppTheme provides AppThemeVals(
            colors = LightColors,
            texts = buildAppTexts(LightColors),
            settings = AppSettings(glass = GlassLevel.OFF, reduceMotion = true),
        ),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            ExamRowBlock(index = 0, entry = entry, onPatch = onPatch, onRemove = onRemove)
        }
    }
}
