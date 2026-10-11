package com.studykit.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.studykit.data.AppSettings
import com.studykit.data.GlassLevel
import com.studykit.ui.components.AppCard
import com.studykit.ui.study.semanticInterleaveHint
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
 * 设置页「相似题打散」那一格的**渲染守卫**（v3.0-C Task 6）。
 *
 * ## 它防的是什么（`AppSettingsSemanticInterleaveTest` 测不到）
 * 那一头钉的是 `toMap/fromMap` 的键值口径，回答不了界面这一头的三件事：
 *  ① 这一格**画的是哪一条布尔** —— `checked` 接错线（写成 `interleavingEnabled`，M-R1）时存储层全绿、
 *    界面上「题面层还没开」却显示成已开；
 *  ② 拨动有没有把新值交给**这一格自己的**回调 —— `onCheckedChange` 串到 `onInterleaving`（M-R2）时
 *    存储层照旧绿，但用户点它其实改的是学科交错那一条；
 *  ③ 标题与说明有没有真上屏，且说明**取自** [semanticInterleaveHint()] —— 这一格不许有第二份文案
 *    （整句由 `QuestionQueuePlannerTest` 的「设置行文案逐字钉住」钉着），所以这里断言的是那个纯函数的
 *    返回值本身，而不是界面里再抄一遍的字面量：抄一遍而那份与纯函数不同，这条就会红（M-R3）。
 *
 * ## 走 Robolectric、只摆这一簇
 * 同 [SettingsBehaviorRenderTest] / [SettingsScreenExamsRenderTest]：整页要 [SettingsViewModel]
 * （背后是 Room），JVM 侧起不来，所以只把 [SchedulePracticeSettings] 真组合一次；
 * 一个测试只能 `setContent` 一次，故两条用例各自起一次宿主。
 * `glass = OFF` + `reduceMotion = true` 让按压 spring 不建补间，测试才等得到 idle。
 *
 * ## 不测什么
 * 题面层打散本身（`planQuestionQueue` 的 `semantic` 分支）由 `QuestionQueuePlannerTest` 覆盖，
 * 这一簇只是读写镜像，不重复；而**此刻还没有生产代码读这一项** —— 组轮入口的接线是 Task 7。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h820dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsSemanticRowRenderTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun setContentWith(
        settings: AppSettings,
        onSemanticInterleave: (Boolean) -> Unit = {},
    ) {
        composeRule.setContent {
            SemanticRowHost(settings = settings, onSemanticInterleave = onSemanticInterleave)
        }
        composeRule.waitForIdle()
    }

    /** 杀 M-R1（`checked` 接成别的布尔）、M-R3（说明不走纯函数 / 那一格整格漏渲染） */
    @Test fun `默认关着且标题与说明都在`() {
        setContentWith(AppSettings(glass = GlassLevel.OFF, reduceMotion = true))
        composeRule.onNodeWithTag(SwitchSemanticTag, useUnmergedTree = true).assertIsOff()
        composeRule.onNodeWithText("相似题打散").assertIsDisplayed()
        composeRule.onNodeWithText(semanticInterleaveHint()).assertIsDisplayed()
    }

    /** 杀 M-R2（`onCheckedChange` 串到邻座那一条回调 / 写成空实现） */
    @Test fun `拨动回调把新值传出去`() {
        var received: Boolean? = null
        setContentWith(
            AppSettings(glass = GlassLevel.OFF, reduceMotion = true),
            onSemanticInterleave = { received = it },
        )
        composeRule.onNodeWithTag(SwitchSemanticTag, useUnmergedTree = true).performClick()
        assertEquals(true, received)
    }
}

/**
 * 只摆「排期与练习」这一簇，不摆整页：整页要 [SettingsViewModel]，JVM 侧起不来。
 * provider 块逐字复用 [SettingsBehaviorRenderTest] 那份 `AppThemeVals(LightColors, buildAppTexts(...),
 * AppSettings(glass = OFF, reduceMotion = true))`；其余三条回调给空实现 —— 本宿主只管新那一格，
 * 前三件的读写由 [SettingsBehaviorRenderTest] 继续钉。
 */
@Composable
private fun SemanticRowHost(
    settings: AppSettings,
    onSemanticInterleave: (Boolean) -> Unit,
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
                    onKernel = {},
                    onConfidence = {},
                    onInterleaving = {},
                    onSemanticInterleave = onSemanticInterleave,
                )
            }
        }
    }
}
