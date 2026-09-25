package com.studykit.ui.habit

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.studykit.data.AppSettings
import com.studykit.data.GlassLevel
import com.studykit.data.entity.Contract
import com.studykit.ui.theme.AppThemeVals
import com.studykit.ui.theme.LightColors
import com.studykit.ui.theme.LocalAppTheme
import com.studykit.ui.theme.buildAppTexts
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 契约达成仪式那一页的**渲染守卫**。
 *
 * ## 为什么现在值得再试一次
 *
 * 本仓曾在同一件事上烧掉 10 轮 CI，最后把测试整批撤回（走查报告 §6）。撤下来时留在
 * `ContractRitual.kt` 里的理由是："同一份测试单独跑绿、进全量跑就红成
 * `UncaughtExceptionsBeforeTest`，红的不是断言而是**别处**残留的未捕获异常"，
 * 并点名那条异常来自 `StudyKitApp.onCreate` 挂在 `Dispatchers.Default` 上的
 * `ReminderScheduler.applyInterval`。那一次没有处理异常本身，只是把测试删了 ——
 * 于是这一页"真的摆出来了"从此没有 CI 防线。
 *
 * 这一轮把那个源头处理了：`applyInterval` 里 `WorkManager.getInstance(...)` 包进 try/catch，
 * 失败落 ERROR 而**不再让异常逃出协程**。所以这条测试值得再试。
 * 但"值得再试"不等于"能过"：**它必须在全量跑里连续两遍绿才算解锁**，单独跑绿不算数 ——
 * 那正是上次骗过我的那个形状。
 *
 * ## 判别力从哪来
 *
 * 三段各钉一种坏法，第一段是**对照组**：
 * ① 内容齐全。这一段红说明量具坏了（Robolectric 压根没把它组合起来），后面几段绿着也不作数。
 *    这一页在真机上一直是好的，所以它不该红。
 * ② 计数行按参数出现 / 消失。
 * ③ 长承诺把卡撑满时「收下」仍在屏内 —— 钉的是 `weight(1f, fill = false)` 加
 *    "出口那一截不滚"。写坏了的症状是出口被顶到折叠线以下，**数据、语义树、文本全都对，
 *    只有界面用不了**；纯 JVM 单测结构上抓不到这一类。
 *
 * ## 为什么只有一个 `@Test` 方法
 *
 * 同 `CheckInSheetRenderTest`：本仓实测过同一个测试类里多方法会互相污染（先跑那条即使通过
 * 也在 teardown 漏出未捕获异常，把后跑的毒成 `UncaughtExceptionsBeforeTest`，与成败无关）。
 * 多段塞进一个方法，靠自定义消息区分是哪一段红的。
 *
 * ## 不测什么
 *
 * - **不测点击**：Robolectric 的输入注入打不进 dialog 窗口（本仓实测），而这一页真机上走的
 *   正是 `Dialog` 窗口。这里只断"内容在不在语义树里、在不在可视区"。
 * - **不测入场动效**：`reduceMotion = true` 让 `rememberRitualEntrance` 走早退分支拿到常量 1f，
 *   否则 `animateFloatAsState` 会让测试规则等不到 idle。动效本身由真机录屏覆盖
 *   （`walk/v07_ritual.mp4` / `walk/v07_sequence.mp4`）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ContractRitualRenderTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val settledAt = LocalDate.of(2026, 9, 25)
        .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun contract(promise: String) = Contract(
        id = 7L,
        uuid = "u-7",
        habitId = 3L,
        deadlineEpochDay = LocalDate.of(2026, 9, 24).toEpochDay(),
        goalCount = 5,
        promiseText = promise,
        consequenceText = "当天不许刷剧",
        signedBy = "",
        signedAtEpochDay = LocalDate.of(2026, 8, 25).toEpochDay(),
        status = Contract.STATUS_ACHIEVED,
        settledAt = settledAt,
    )

    private val shortPromise = "如果到了【睡前】，我就完成当日打卡。"

    /** 真机上把卡片撑满的那一档长度 */
    private val longPromise = "如果到了【睡前】，我就完成当日打卡；" + "这一句故意写得很长".repeat(12)

    @Test
    fun 仪式页内容齐全_计数行按参数出现_长承诺下出口仍在屏内() {
        var promise by mutableStateOf(shortPromise)
        var positionLine by mutableStateOf<String?>("还有 3 张待收下")

        composeRule.setContent {
            RitualHost(contract = contract(promise), positionLine = positionLine)
        }
        composeRule.waitForIdle()

        // ──  对照组：真机上这一页一直是好的，所以这一段红 = 量具坏了 ──
        clue("量具不可信：仪式页连「契约达成 / 习惯名 / 进度 / 落款 / 收下」都没摆出来 —— " +
            "Robolectric 没能组合 ContractRitualLayer，本测试后面几段的判定无效") {
            for (label in listOf(
                "契约达成", "阅读", "进度 5/5 次",
                "署名：未署名 · 对账于 2026-09-25", "收下",
            )) {
                composeRule.onNodeWithText(label).assertIsDisplayed()
            }
            composeRule.onNodeWithText("「$shortPromise」").assertIsDisplayed()
        }

        // ── ② 计数行按参数出现 / 消失 ──
        clue("一批多张时那行「还有 n 张待收下」没摆出来") {
            composeRule.onNodeWithText("还有 3 张待收下").assertIsDisplayed()
        }
        positionLine = null
        composeRule.waitForIdle()
        clue("单张时不该有计数行（仪式那句「一句多一句都算吵」的纪律还在）") {
            composeRule.onNodeWithText("还有 3 张待收下").assertDoesNotExist()
        }

        // ── ③ 长承诺下出口仍可达（评审 M3）──
        assertTrue("夹具自己坏了：长承诺没长到能挤出口的那一档", longPromise.length > 100)
        promise = longPromise
        positionLine = "还有 2 张待收下"
        composeRule.waitForIdle()
        clue("长承诺（${longPromise.length} 字）把「收下」顶出了可视区 —— " +
            "`weight(1f, fill = false)` 与「出口那一截不滚」这两手之一失效了。" +
            "这种坏法数据与语义树全对，只有界面用不了") {
            composeRule.onNodeWithText("收下").assertIsDisplayed()
            composeRule.onNodeWithText("「$longPromise」").assertIsDisplayed()
        }
    }

    private companion object {
        /**
         * 把"哪一段红的"写进失败信息。三段共用一个 `@Test`，没有这层包装就只知道一条断言
         * 失败，分不清是量具坏了还是守卫命中 —— 而那正是上次让我误判"守卫在工作"的地方。
         */
        fun clue(hint: String, block: () -> Unit) {
            try {
                block()
            } catch (e: AssertionError) {
                throw AssertionError("$hint\n\n原始信息：${e.message}", e)
            }
        }
    }
}

/**
 * 一次 `setContent` 之内换状态：`promise` / `positionLine` 是外层 `mutableStateOf`，
 * 翻它们触发重组 —— `setContent` 一个测试只能调一次（第二次直接抛
 * `Cannot call setContent twice per test!`）。
 *
 * `reduceMotion = true` 只为让入场走早退分支拿到常量 1f，测试规则才等得到 idle；
 * 布局、裁切、语义树那几件事一点没少走。
 */
@Composable
private fun RitualHost(contract: Contract, positionLine: String?) {
    CompositionLocalProvider(
        LocalAppTheme provides AppThemeVals(
            colors = LightColors,
            texts = buildAppTexts(LightColors),
            settings = AppSettings(glass = GlassLevel.OFF, reduceMotion = true),
        ),
    ) {
        ContractRitualLayer(
            contract = contract,
            habitName = "阅读",
            completedCount = 5,
            positionLine = positionLine,
            onDismiss = {},
        )
    }
}
