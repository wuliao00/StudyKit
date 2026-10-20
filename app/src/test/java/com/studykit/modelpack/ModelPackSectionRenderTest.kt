package com.studykit.modelpack

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.studykit.data.AppSettings
import com.studykit.data.GlassLevel
import com.studykit.ui.settings.ModelPackSection
import com.studykit.ui.settings.ModelPackTags
import com.studykit.ui.theme.AppThemeVals
import com.studykit.ui.theme.LightColors
import com.studykit.ui.theme.LocalAppTheme
import com.studykit.ui.theme.buildAppTexts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * [ModelPackSection] 的渲染守卫（计划 T6）。
 *
 * 只摆这一个分区、不摆整页设置：整页要 SettingsViewModel（背后 Room），JVM 侧起不来；
 * 而这个分区真正要防的是"六种行状态各画对没有、按钮回调接没接对线" —— 那些纯逻辑测试看不见。
 * 模板照 SettingsSemanticRowRenderTest：AppTheme provides + createComposeRule + NATIVE 图形。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h820dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ModelPackSectionRenderTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** 回调记录器：每次点击断言"传出去的 id/布尔"而不只是"按钮在"。 */
    class Recorder {
        var downloadId: String? = null
        var cancelId: String? = null
        var deleteId: String? = null
        var toggle: Pair<String, Boolean>? = null
        var goToRemoveId: String? = null
        var dismissed = false
    }

    private fun setContentWith(state: ModelPackUiState): Recorder {
        val recorder = Recorder()
        composeRule.setContent { Host(state = state, recorder = recorder) }
        composeRule.waitForIdle()
        return recorder
    }

    private fun rowState(
        status: PackRowStatus,
        id: String = PackIds.VOICE_TTS,
        kind: PackKind = PackKind.VOICE_TTS,
        sizeBytes: Long = 0L,
        statusLine: String? = null,
    ): ModelPackUiState = ModelPackUiState(
        rows = listOf(
            PackRowUi(
                id = id,
                title = ModelPackCopy.packTitle(kind),
                hint = ModelPackCopy.packHint(kind, sizeBytes.takeIf { it > 0L }),
                status = status,
                sizeBytes = sizeBytes,
                statusLine = statusLine,
            ),
        ),
        installedBytes = if (status is PackRowStatus.Installed) sizeBytes else 0L,
    )

    @Test
    fun `六个未上架行都画出开发中与总账`() {
        val state = ModelPackUiState(rows = ModelPackRows.build(PackRegistry.all, emptyMap(), emptySet()))
        setContentWith(state)

        PackIds.ALL.forEach { id -> composeRule.onNodeWithTag(ModelPackTags.row(id)).assertIsDisplayed() }
        composeRule.onAllNodesWithText(ModelPackCopy.plannedBadge()).assertCountEquals(6)
        composeRule.onNodeWithText(ModelPackCopy.sectionHint()).assertIsDisplayed()
        composeRule.onNodeWithText(ModelPackCopy.budgetSummary(0L, BudgetGuard.DEFAULT_BUDGET_BYTES))
            .assertIsDisplayed()
        // 未上架行没有下载按钮（不该露出可点空壳）
        assertEquals(0, composeRule.onAllNodesWithTag(ModelPackTags.download(PackIds.VOICE_TTS)).fetchSemanticsNodes().size)
    }

    @Test
    fun `未安装行显示说明与下载按钮且回调带 id`() {
        val state = rowState(PackRowStatus.NotInstalled, sizeBytes = 30L * 1024 * 1024)
        val recorder = setContentWith(state)

        composeRule.onNodeWithText(ModelPackCopy.packHint(PackKind.VOICE_TTS, 30L * 1024 * 1024)).assertIsDisplayed()
        composeRule.onNodeWithTag(ModelPackTags.download(PackIds.VOICE_TTS)).performClick()
        assertEquals(PackIds.VOICE_TTS, recorder.downloadId)
    }

    @Test
    fun `下载中显示进度与取消按钮且回调带 id`() {
        val state = rowState(PackRowStatus.Downloading(0.42f))
        val recorder = setContentWith(state)

        composeRule.onNodeWithText(ModelPackCopy.downloadProgress(0.42f)).assertIsDisplayed()
        composeRule.onNodeWithTag(ModelPackTags.cancel(PackIds.VOICE_TTS)).performClick()
        assertEquals(PackIds.VOICE_TTS, recorder.cancelId)
    }

    @Test
    fun `校验中显示正在校验且没有可点按钮`() {
        val state = rowState(PackRowStatus.Verifying)
        setContentWith(state)

        composeRule.onNodeWithText(ModelPackCopy.verifying()).assertIsDisplayed()
        assertEquals(0, composeRule.onAllNodesWithTag(ModelPackTags.cancel(PackIds.VOICE_TTS)).fetchSemanticsNodes().size)
    }

    @Test
    fun `已装行显示开关与删除且回调正确`() {
        val state = rowState(
            status = PackRowStatus.Installed(enabled = true),
            sizeBytes = 30L * 1024 * 1024,
            statusLine = ModelPackCopy.installedSize(30L * 1024 * 1024),
        )
        val recorder = setContentWith(state)

        composeRule.onNodeWithText(ModelPackCopy.installedSize(30L * 1024 * 1024)).assertIsDisplayed()
        composeRule.onNodeWithTag(ModelPackTags.toggle(PackIds.VOICE_TTS)).assertIsOn().performClick()
        assertEquals(PackIds.VOICE_TTS to false, recorder.toggle)
        composeRule.onNodeWithTag(ModelPackTags.delete(PackIds.VOICE_TTS)).performClick()
        assertEquals(PackIds.VOICE_TTS, recorder.deleteId)
    }

    @Test
    fun `损坏行显示重新下载且回调带 id`() {
        val state = rowState(PackRowStatus.Corrupt, statusLine = ModelPackCopy.corrupt())
        val recorder = setContentWith(state)

        composeRule.onNodeWithText(ModelPackCopy.corrupt()).assertIsDisplayed()
        composeRule.onNodeWithTag(ModelPackTags.redownload(PackIds.VOICE_TTS)).performClick()
        assertEquals(PackIds.VOICE_TTS, recorder.downloadId)
    }

    @Test
    fun `空态给一句人话而不是留白`() {
        setContentWith(ModelPackUiState())
        composeRule.onNodeWithText(ModelPackCopy.emptyState()).assertIsDisplayed()
    }

    @Test
    fun `预算弹层显示建议包名与去卸载回调`() {
        val reject = BudgetRejectUi(
            incomingId = PackIds.EMBED_ZH_SMALL_INT8,
            removalId = PackIds.VOICE_TTS,
            title = ModelPackCopy.budgetDialogTitle(),
            body = ModelPackCopy.budgetRejectWithRemoval(
                ModelPackCopy.packTitle(PackKind.VOICE_TTS),
                ModelPackCopy.packTitle(PackKind.EMBED),
            ),
        )
        val recorder = setContentWith(ModelPackUiState(budgetReject = reject))

        composeRule.onNodeWithText(reject.body).assertIsDisplayed()
        composeRule.onNodeWithText(ModelPackCopy.actionGoUninstall()).performClick()
        assertEquals(PackIds.VOICE_TTS, recorder.goToRemoveId)
        assertEquals(true, recorder.dismissed)
    }

    @Test
    fun `无单包可腾时弹层只给知道了`() {
        val reject = BudgetRejectUi(
            incomingId = PackIds.LLM_ZH_06B_INT4,
            removalId = null,
            title = ModelPackCopy.budgetDialogTitle(),
            body = ModelPackCopy.budgetRejectNoSingleRemoval(100L * 1024 * 1024),
        )
        val recorder = setContentWith(ModelPackUiState(budgetReject = reject))

        composeRule.onNodeWithText(ModelPackCopy.actionKnown()).assertIsDisplayed()
        composeRule.onNodeWithText(ModelPackCopy.actionKnown()).performClick()
        assertNull(recorder.goToRemoveId)
        assertEquals(true, recorder.dismissed)
    }

    @Test
    fun `一次性提示会上屏`() {
        val state = ModelPackUiState(rows = emptyList(), message = "已取消下载。")
        setContentWith(state)
        composeRule.onNodeWithText("已取消下载。").assertIsDisplayed()
    }
}

/**
 * 只摆模型包这一簇，不摆整页：整页要 SettingsViewModel（背后 Room），JVM 侧起不来。
 * provider 块逐字复用 SettingsBehaviorRenderTest 那份
 * AppThemeVals(LightColors, buildAppTexts(LightColors), AppSettings(glass = OFF, reduceMotion = true))。
 */
@Composable
private fun Host(state: ModelPackUiState, recorder: ModelPackSectionRenderTest.Recorder) {
    CompositionLocalProvider(
        LocalAppTheme provides AppThemeVals(
            colors = LightColors,
            texts = buildAppTexts(LightColors),
            settings = AppSettings(glass = GlassLevel.OFF, reduceMotion = true),
        ),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            ModelPackSection(
                state = state,
                onDownload = { recorder.downloadId = it },
                onCancel = { recorder.cancelId = it },
                onDelete = { recorder.deleteId = it },
                onToggle = { id, enabled -> recorder.toggle = id to enabled },
                onGoToRemove = { recorder.goToRemoveId = it },
                onDismissBudgetReject = { recorder.dismissed = true },
            )
        }
    }
}
