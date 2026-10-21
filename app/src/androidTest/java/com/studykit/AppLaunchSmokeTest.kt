package com.studykit

import android.os.Build
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import com.studykit.ui.settings.SwitchSemanticTag
import org.junit.Assert.fail
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test

/**
 * 冷启动冒烟：起**真正的** `MainActivity`，走它自己的免责声明/首启闸门与导航栈，
 * 进设置页，断言「相似题打散」那一格（标题 + 开关本体）存在。
 *
 * ## 它守的是什么
 * `QuizScreen` 的路由边与设置页那一格此前只有 JVM 侧的渲染守卫
 * （`SettingsSemanticRowRenderTest` 单独摆那一簇，不看真实 Activity + NavHost）。
 * 这条用例把「真实启动 → 首页齿轮 → 设置页 → 那一格」整条边钉一次：装配层把路由或那一格
 * 删掉/接错时，JVM 渲染守卫仍然会绿，而这里会红。
 *
 * ## 只读，不写库
 * 设备上的库是用户的真实数据。因此本用例**只导航、不拨开关**，也**不点**
 * 「同意并继续」「跳过」「开始使用」这类会写 settings 表的首启动作：
 *  - 如果设备还没过首启闸门，用例当场失败并说明前提（先在设备上人工过一遍首启再跑），
 *    而不是替用户点下那个同意；
 *  - Android 13+ 的 POST_NOTIFICATIONS 是一次系统弹窗、不是库写入，`@BeforeClass` 里用
 *    `pm grant` 提前给掉，免得它盖在 Activity 上挡住点击。给不到就忽略，由失败信息自行暴露。
 *
 * ## 诚实声明
 * **首次真实执行：2026-10-20，vivo V2156A / Android 11。** 通过
 * `.\gradlew.bat :app:connectedDebugAndroidTest` 真跑；执行前设备已人工过完首启
 * （本用例仍不替用户点「同意 / 跳过 / 开始使用」）。
 * 结果与证据见 `CHANGELOG.md` 的 `[3.0.0]` 段。
 */
class AppLaunchSmokeTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    /**
     * 首屏要么是首页（齿轮的 contentDescription 是「设置」），要么还停在首启两道闸上。
     * 后者的文案各取一个稳定标记：免责声明「同意并继续」、首启引导「跳过」/「开始使用」。
     */
    private fun firstScreenArrived(): Boolean =
        hasContentDescription("设置") || hasText("同意并继续") || hasText("跳过") || hasText("开始使用")

    private fun hasText(text: String): Boolean =
        rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private fun hasContentDescription(description: String): Boolean =
        rule.onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty()

    private fun hasSemanticSwitch(): Boolean =
        rule.onAllNodesWithTag(SwitchSemanticTag, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .isNotEmpty()

    @Test
    fun `从冷启动走到设置页能看到相似题打散那一格`() {
        rule.waitUntil(SCREEN_TIMEOUT_MS) { firstScreenArrived() }

        // 首启闸门必须先由人手过一次：本用例不许代表用户写下「同意/已看过引导」。
        if (!hasContentDescription("设置")) {
            fail(
                "设备还停在首启闸门（免责声明/首启引导）上，看不到首页的齿轮。" +
                    "本用例刻意不点『同意并继续』『跳过』『开始使用』——那会写 settings 表，" +
                    "而设备上的库是用户的真实数据。请先在设备上人工打开一次 StudyKit 过完首启，再重跑本用例。",
            )
        }

        rule.onNodeWithContentDescription("设置").performClick()
        rule.waitUntil(SCREEN_TIMEOUT_MS) { hasSemanticSwitch() }

        // 设置页是一整列 verticalScroll（不是 LazyColumn），那一格在折叠以下，先滚到它。
        rule.onNodeWithTag(SwitchSemanticTag, useUnmergedTree = true).performScrollTo().assertExists()
        rule.onNodeWithText("相似题打散").performScrollTo().assertIsDisplayed()
    }

    companion object {
        /** 真机上 Room 首帧 + 导航转场都在这个量级之内；给足余量，失败时也不至于磨蹭太久。 */
        private const val SCREEN_TIMEOUT_MS = 20_000L

        /**
         * Android 13+ 冷启动会弹 POST_NOTIFICATIONS 系统对话框；它是系统窗口，会盖住 Activity
         * 并吞掉注入的点击。测试跑之前先在 shell 侧给掉这条权限（不是库写入，也不改用户数据）。
         * 拿不到权限就算了：真正的失败会由 `firstScreenArrived` 的超时暴露。
         */
        @BeforeClass
        @JvmStatic
        fun grantNotificationPermissionIfNeeded() {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
            runCatching {
                val instrumentation = InstrumentationRegistry.getInstrumentation()
                instrumentation.uiAutomation.executeShellCommand(
                    "pm grant ${instrumentation.targetContext.packageName} android.permission.POST_NOTIFICATIONS",
                ).close()
            }
        }
    }
}
