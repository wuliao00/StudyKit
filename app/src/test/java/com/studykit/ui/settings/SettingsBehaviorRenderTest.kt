package com.studykit.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.studykit.data.AppSettings
import com.studykit.data.GlassLevel
import com.studykit.ui.components.AppCard
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
 * 设置页「排期与练习」这一簇的**渲染守卫**（v2.7 计划 B Task 20）。
 *
 * ## 它防的是什么（纯函数测不到）
 * [com.studykit.data.AppSettingsTest] 与 [AppSettingsInterleavingTest] 钉的是 `toMap/fromMap` 的
 * 键值口径；它们回答不了「页面上这两枚磁贴 / 两颗开关有没有真接上 `schedulingKernel` /
 * `confidenceEnabled` / `interleavingEnabled` 这三条写回」——磁贴画错 id、开关接反、副标题把
 * 那句诚实口径写丢了，纯数据测试全绿，界面却是坏的。这一条把 [SchedulePracticeSettings] 真组合出来，
 * 走一遍点击，断言写回的值与界面读数一致。
 *
 * ## 为什么只有一个 @Test / 走 Robolectric
 * 同 [com.studykit.ui.habit.CheckInSheetRenderTest] / [com.studykit.ui.study.RecallGateRenderTest]：
 * 整页要 [SettingsViewModel]（背后是 Room），JVM 侧起不来，所以只渲染这一簇；
 * 一个测试只能 `setContent` 一次，四段塞进同一方法，靠 `clue` 的自定义消息区分哪段红。
 *
 * ## 不测什么
 * 交错「打散」本身只有 `QuestionOrdering.interleaveBySubject` 一处判定，这一簇只是镜像读写，
 * 判定正确性由既有测试覆盖，这里不重复。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsBehaviorRenderTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** 与页面同一份快照：写回回调翻这个 state 触发重组，测试就照着它读界面选中态与 toMap。 */
    private var current by mutableStateOf(AppSettings())

    private fun clue(hint: String, block: () -> Unit) {
        try {
            block()
        } catch (e: AssertionError) {
            throw AssertionError("$hint\n\n原始信息：${e.message}", e)
        }
    }

    @Test
    fun 默认态渲染_点旧内核写回对_开关往返() {
        composeRule.setContent {
            BehaviorHost(
                settings = current,
                onKernel = { id ->
                    // 与 SettingsViewModel.setSchedulingKernel 同一道白名单兜底
                    current = current.copy(
                        schedulingKernel = if (id in AppSettings.KERNEL_IDS) id else current.schedulingKernel,
                    )
                },
                onConfidence = { current = current.copy(confidenceEnabled = it) },
                onInterleaving = { current = current.copy(interleavingEnabled = it) },
            )
        }
        composeRule.waitForIdle()

        // ── 第一段：默认态渲染（对照组，真机上这簇一直是好的，这段红 = 量具坏了）──
        clue("量具不可信：这簇连『排期内核』标题都没摆出来 —— Robolectric 没能组合 SchedulePracticeSettings") {
            composeRule.onNodeWithText("排期内核").assertIsDisplayed()
        }
        clue(
            "诚实口径丢了：副标题必须逐字含『切回旧内核后，评分历史不丢，但记忆强度读数是换算近似』—— " +
                "spec §2.1 承诺切内核不丢历史但读数是近似，这句不能省",
        ) {
            composeRule
                .onNodeWithText("切回旧内核后，评分历史不丢，但记忆强度读数是换算近似", substring = true)
                .assertIsDisplayed()
        }
        clue("默认 FSRS：FSRS 磁贴该选中、旧内核磁贴不该选中") {
            composeRule.onNodeWithText("FSRS 新内核").assertIsSelected()
            composeRule.onNodeWithText("半衰期旧内核").assertIsNotSelected()
        }

        // ── 第二段：KERNEL_IDS 白名单即 UI 只画这两项 ──
        clue(
            "UI 画的内核项必须恰好等于 AppSettings.KERNEL_IDS 白名单（{FSRS, HALF_LIFE}）—— " +
                "多一项少一项都说明设置页与白名单不一致，白名单外的值也就进不来",
        ) {
            assertEquals(setOf("FSRS", "HALF_LIFE"), AppSettings.KERNEL_IDS)
            assertEquals("设置页只该画这两项内核", 2, KernelChoices.size)
            assertEquals(AppSettings.KERNEL_IDS, KernelChoices.map { it.first }.toSet())
            composeRule.onNodeWithText("FSRS 新内核").assertIsDisplayed()
            composeRule.onNodeWithText("半衰期旧内核").assertIsDisplayed()
        }

        // ── 第三段：点击 HALF_LIFE → 写回值正确 → toMap 落地 → 选中态翻转 ──
        composeRule.onNodeWithText("半衰期旧内核").performClick()
        composeRule.waitForIdle()
        clue("点旧内核没把 schedulingKernel 写成 HALF_LIFE（current=${current.schedulingKernel}）—— 磁贴与写回接错线") {
            assertEquals("HALF_LIFE", current.schedulingKernel)
        }
        clue("写回后 toMap 的 scheduling_kernel 键不应该是 HALF_LIFE") {
            assertEquals("HALF_LIFE", current.toMap()[AppSettings.KEY_SCHEDULING_KERNEL])
        }
        clue("写回后界面应重新选中旧内核、取消选中 FSRS") {
            composeRule.onNodeWithText("半衰期旧内核").assertIsSelected()
            composeRule.onNodeWithText("FSRS 新内核").assertIsNotSelected()
        }

        // ── 第四段：开关往返（先自评把握再评分 + 交错练习）──
        // 默认都是开：点一下关，再点一下开，值与 toMap/fromMap 往返都对得上。
        assertTrue(current.confidenceEnabled)
        composeRule.onNodeWithTag(SwitchConfidenceTag).performClick()
        composeRule.waitForIdle()
        clue("点信心开关没把 confidenceEnabled 关掉（current=${current.confidenceEnabled}）—— 开关接错线") {
            assertFalse(current.confidenceEnabled)
            assertEquals("false", current.toMap()[AppSettings.KEY_CONFIDENCE_ENABLED])
            assertEquals(false, AppSettings.fromMap(current.toMap()).confidenceEnabled)
        }
        composeRule.onNodeWithTag(SwitchConfidenceTag).performClick()
        composeRule.waitForIdle()
        clue("再点一次信心开关应关回开（往返）") {
            assertTrue(current.confidenceEnabled)
        }

        assertTrue(current.interleavingEnabled)
        composeRule.onNodeWithTag(SwitchInterleavingTag).performClick()
        composeRule.waitForIdle()
        clue("点交错开关没把 interleavingEnabled 关掉（current=${current.interleavingEnabled}）—— 镜像写回接错线") {
            assertFalse(current.interleavingEnabled)
            assertEquals("false", current.toMap()[AppSettings.KEY_INTERLEAVING])
        }
        composeRule.onNodeWithTag(SwitchInterleavingTag).performClick()
        composeRule.waitForIdle()
        clue("再点一次交错开关应关回开（往返）") {
            assertTrue(current.interleavingEnabled)
            // 整簇快照能原样过一轮 toMap/fromMap，说明三个写回都落在真实键上
            assertEquals(current, AppSettings.fromMap(current.toMap()))
        }
    }
}

/**
 * 只摆这一簇，不摆整页：整页要 [SettingsViewModel]（背后是 Room），JVM 侧起不来。
 * provider 块逐字复用 [com.studykit.ui.habit.CheckInSheetRenderTest] / [com.studykit.ui.study.ConfidencePillRenderTest]
 * 那份 `AppThemeVals(LightColors, buildAppTexts(LightColors), AppSettings(...))`；
 * `reduceMotion = true` 让按压 spring 不建补间，测试才等得到 idle。
 */
@Composable
private fun BehaviorHost(
    settings: AppSettings,
    onKernel: (String) -> Unit,
    onConfidence: (Boolean) -> Unit,
    onInterleaving: (Boolean) -> Unit,
) {
    CompositionLocalProvider(
        LocalAppTheme provides AppThemeVals(
            colors = LightColors,
            texts = buildAppTexts(LightColors),
            settings = AppSettings(glass = GlassLevel.OFF, reduceMotion = true),
        ),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            AppCard(modifier = Modifier.fillMaxSize()) {
                SchedulePracticeSettings(
                    settings = settings,
                    onKernel = onKernel,
                    onConfidence = onConfidence,
                    onInterleaving = onInterleaving,
                )
            }
        }
    }
}
