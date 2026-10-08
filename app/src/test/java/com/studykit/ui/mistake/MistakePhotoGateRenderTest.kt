package com.studykit.ui.mistake

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.studykit.data.AppSettings
import com.studykit.data.GlassLevel
import com.studykit.ui.theme.AppThemeVals
import com.studykit.ui.theme.LightColors
import com.studykit.ui.theme.LocalAppTheme
import com.studykit.ui.theme.buildAppTexts
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 错题详情页**照片那一段**的渲染守卫（v3.0-B T14）。
 *
 * 钉的是这个洞的真实现场：拍照录入的错题，照片里就印着正确答案，而详情页从 v2.5 起
 * 在重做阶段无条件把整张照片摆出来（`MistakeDetailScreen` 那个 `if (imageFile != null && imageExists)`）。
 * 纯函数层的 `imageVisible` 判定钉在 `RedoFlowTest`，但**判定接没接到树上**只有渲染守卫抓得到 ——
 * 于是这里只渲染 [MistakePhotoBlock] 这一小段：整屏要 [MistakeViewModel]（背后是 Room），JVM 侧起不来，
 * 口径同 `MockExamRenderTest` 只渲染 `MockExamBody` / `MockExamResumePrompt`。
 *
 * 照片文件一律用 `File("nope.jpg")`：这一段的可见性**不取决于像素能不能解码**，
 * 取决于 `gate.hasImage`（页面把 `imageFile != null && imageExists` 喂进 `buildRedoGate` 的那一位）。
 * 「文件其实不存在」那条路径由页面侧的 `imageExists` 回填负责，不在本文件范围内（见类注释末段）。
 *
 * **未覆盖**：灯箱（`FullImageOverlay`）的开合在页面级（`showFullImage` 由整屏持有），
 * 本文件钉到「照片不可见时那个可点开的节点根本不在树上」为止 —— 节点不在，灯箱就没有入口。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MistakePhotoGateRenderTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `重做阶段没画遮挡块时照片不在树上只剩占位那句`() {
        var opened = 0
        composeRule.setContent {
            PhotoHost(phase = RedoPhase.REDO, hasImage = true, onOpenFullImage = { opened++ })
        }
        // 量具对照：占位卡没组合成功 = Robolectric 没跑起来，后面那句"没有照片"就是假的
        composeRule.onNodeWithText("整张照片扣住，重做后再看").assertIsDisplayed()
        // 洞就在这里：这张照片带着印上去的答案，重做阶段不许在树上
        composeRule.onNodeWithContentDescription("查看大图").assertDoesNotExist()
        assertEquals("照片扣着时也没有点开大图的入口", 0, opened)
    }

    @Test
    fun `核对阶段照片回到树上且仍可点开大图`() {
        var opened = 0
        composeRule.setContent {
            PhotoHost(phase = RedoPhase.CHECK, hasImage = true, onOpenFullImage = { opened++ })
        }
        composeRule.onNodeWithContentDescription("查看大图").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("查看大图").performClick()
        assertEquals("灯箱入口恰好一个", 1, opened)
        // 对照阶段没有任何东西被扣住，占位那句不许还在
        composeRule.onNodeWithText("整张照片扣住，重做后再看").assertDoesNotExist()
    }

    @Test
    fun `画了遮挡块的重做阶段照片可见并写明已遮几处`() {
        composeRule.setContent {
            PhotoHost(phase = RedoPhase.REDO, hasImage = true, shapeCount = 2)
        }
        // 用户已经指明答案在哪几块，剩下的部分不含答案 —— 这才是敢在看图的同时继续重做的原因
        composeRule.onNodeWithContentDescription("查看大图").assertIsDisplayed()
        composeRule.onNodeWithText("已遮 2 处，先回忆，点按全揭").assertIsDisplayed()
        // 整张扣住那句不该出现（那是"一块都没画"的说法）
        composeRule.onNodeWithText("整张照片扣住，重做后再看").assertDoesNotExist()
    }

    @Test
    fun `没有照片的行既不出照片也不出占位卡`() {
        composeRule.setContent { PhotoHost(phase = RedoPhase.REDO, hasImage = false) }
        composeRule.onNodeWithContentDescription("查看大图").assertDoesNotExist()
        // 没照片就不许摆出"照片被扣住了"的架势（那会让人去找一张根本不存在的图）
        composeRule.onNodeWithText("整张照片扣住，重做后再看").assertDoesNotExist()
        composeRule.onNodeWithText("已遮", substring = true).assertDoesNotExist()
    }

    /**
     * T15 那一行接线的守卫：判定算对了但**没接到渲染层**的话，上面四条一条都发现不了 ——
     * 它们看的是照片在不在树上，而这里钉的是罩子有没有真的盖上去、点一下有没有真的揭开。
     */
    @Test
    fun `重做阶段画过块的行挂上罩子，点一下整层全揭`() {
        var opened = 0
        composeRule.setContent {
            PhotoHost(
                phase = RedoPhase.REDO,
                hasImage = true,
                shapeCount = 1,
                shapes = listOf(OcclusionShape.Rect(0.1, 0.1, 0.4, 0.4)),
                onOpenFullImage = { opened++ },
            )
        }
        // 量具对照：这一屏没组合成功的话，下面那句"节点不存在"就是假的
        composeRule.onNodeWithText("已遮 1 处，先回忆，点按全揭").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("遮住答案，点按全揭").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("遮住答案，点按全揭").performClick()
        composeRule.waitForIdle()
        // 全揭是整层一起揭（R1）：点过一次，那一层就不复存在，没有第二块可揭
        composeRule.onNodeWithContentDescription("遮住答案，点按全揭").assertDoesNotExist()
        // 罩子没了，"已遮 N 处"那句必须跟着收回 —— 答案就摆在眼前还说遮着是假话
        composeRule.onNodeWithText("已遮 1 处，先回忆，点按全揭").assertDoesNotExist()
        // 揭开不是把照片拿走
        composeRule.onNodeWithContentDescription("查看大图").assertIsDisplayed()
        assertEquals("重做阶段点照片不该有第二个手势（灯箱仍归对照阶段）", 0, opened)
    }
}

/**
 * 测试宿主：把 [MistakePhotoBlock] 单独摆进主题里，gate 走**生产**的 [buildRedoGate]
 * （不在测试里手搓 `imageVisible`，否则钉的是我自己写的布尔，而不是页面读的那一位）。
 *
 * [shapeCount] 与 [shapes] 是两个独立的喂入口：前者进 gate（那句"已遮 N 处"读它），
 * 后者是 T15 起渲染层真正拿来画罩子的那份列表。老用例保持 `shapes = emptyList()`
 * （它们钉的是照片在不在树上，与块无关），只有最后那条同时喂两者 —— 那才是页面真实的配对。
 */
@Composable
private fun PhotoHost(
    phase: RedoPhase,
    hasImage: Boolean,
    shapeCount: Int = 0,
    shapes: List<OcclusionShape> = emptyList(),
    onOpenFullImage: () -> Unit = {},
) {
    CompositionLocalProvider(
        LocalAppTheme provides AppThemeVals(
            colors = LightColors,
            texts = buildAppTexts(LightColors),
            settings = AppSettings(glass = GlassLevel.OFF, reduceMotion = true),
        ),
    ) {
        MistakePhotoBlock(
            imageFile = if (hasImage) File("nope.jpg") else null,
            gate = buildRedoGate("题干", "", phase, hasImage = hasImage, shapeCount = shapeCount),
            shapes = shapes,
            onOpenFullImage = onOpenFullImage,
        )
    }
}
