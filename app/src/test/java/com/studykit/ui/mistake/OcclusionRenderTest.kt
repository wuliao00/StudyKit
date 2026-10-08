package com.studykit.ui.mistake

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
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
 * 遮挡渲染层（[OcclusionImage]）的守卫（v3.0-B T15）。
 *
 * 两组用例各挡一种塌法：
 * - 前三个钉 [shapePixelRect]：它是「归一化的落库坐标」与「屏幕上那块罩子」之间**唯一**一处算术
 *   （R2：编辑器与渲染层共用它，谁都不许再抄一份）。宽高各走自己的轴这条必须单独钉 ——
 *   照片不是正方形，串了轴的话块会画歪，而库里那份 JSON 看不出任何异常。
 * - 后三个钉手势的存在性：有块才挂点按层（R1，全揭只有一次机会）、点一次恰好回调一次、
 *   没块与已揭开时那个节点**根本不该在树上**。
 *
 * 照片一律给 `File("nope.jpg")`：故意用不存在的文件。coil 加载失败只让图区空白，
 * 而遮挡层与手势是 Compose 自己的 `Canvas` + `pointerInput`，与像素能不能解码无关 ——
 * 这样测试就不必在仓库里造一个真 JPEG，也顺手证明了「解码失败不该让遮罩手势一起消失」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OcclusionRenderTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // 遮挡块的像素矩形换算（⛔ 归一化 -> 屏幕的唯一一处算术，单独钉死）
    @Test
    fun `归一化换算按宽高各走自己的轴`() {
        val r = shapePixelRect(OcclusionShape.Rect(0.1, 0.2, 0.5, 0.6), 100f, 100f)
        assertEquals(10f, r.left, 1e-3f)
        assertEquals(20f, r.top, 1e-3f)
        assertEquals(50f, r.right, 1e-3f)
        assertEquals(60f, r.bottom, 1e-3f)

        val e = shapePixelRect(OcclusionShape.Ellipse(0.5, 1.0, 0.5, 0.5), 200f, 100f)
        assertEquals(0f, e.left, 1e-3f)
        assertEquals(50f, e.top, 1e-3f)
        assertEquals(200f, e.right, 1e-3f)
        assertEquals(150f, e.bottom, 1e-3f)
    }

    @Test
    fun `多边形给的是外接框，用于画框不需要裁剪`() {
        val p = shapePixelRect(
            OcclusionShape.Polygon(listOf(NormalizedPoint(0.1, 0.2), NormalizedPoint(0.4, 0.0), NormalizedPoint(0.2, 0.5))),
            100f,
            200f,
        )
        assertEquals(10f, p.left, 1e-3f)
        assertEquals(0f, p.top, 1e-3f)
        assertEquals(40f, p.right, 1e-3f)
        assertEquals(100f, p.bottom, 1e-3f)
    }

    @Test
    fun `宽高不同也不串轴`() {
        val r = shapePixelRect(OcclusionShape.Rect(0.0, 0.0, 1.0, 1.0), 300f, 100f)
        assertEquals(300f, r.right, 1e-3f)
        assertEquals(100f, r.bottom, 1e-3f)
    }

    @Test
    fun `有遮挡块时挂可点按的全揭层，点一次回调到`() {
        var reveals = 0
        showAt { Host { OcclusionImage(model = File("nope.jpg"), shapes = listOf(OcclusionShape.Rect(0.1, 0.1, 0.4, 0.4)), onRevealAll = { reveals++ }) } }
        composeTestRule.onNodeWithContentDescription("遮住答案，点按全揭").performClick()
        assertEquals(1, reveals)
        composeTestRule.onNodeWithContentDescription("遮住答案，点按全揭").assertIsDisplayed()
    }

    @Test
    fun `没有遮挡块时不挂点击层（没东西可揭就不该有那个手势）`() {
        var reveals = 0
        showAt { Host { OcclusionImage(model = File("nope.jpg"), shapes = emptyList(), onRevealAll = { reveals++ }) } }
        composeTestRule.onNodeWithContentDescription("遮住答案，点按全揭").assertDoesNotExist()
        assertEquals(0, reveals)
    }

    @Test
    fun `已经揭开就不再挂遮罩层`() {
        showAt { Host { OcclusionImage(model = File("nope.jpg"), shapes = listOf(OcclusionShape.Rect(0.1, 0.1, 0.4, 0.4)), revealed = true, onRevealAll = { }) } }
        composeTestRule.onNodeWithContentDescription("遮住答案，点按全揭").assertDoesNotExist()
    }

    private fun showAt(block: @Composable () -> Unit) = composeTestRule.setContent { Host { block() } }
}

/**
 * 测试宿主：只把 [OcclusionImage] 摆进主题里，不渲染整屏（整屏要 [MistakeViewModel]，
 * 背后是 Room，JVM 侧起不来）。provider 块照 `MockExamRenderTest` 的 `ExamHost` 抄：
 * 玻璃关掉、动效关掉，剩下的才是被测的那两层。
 */
@Composable
private fun Host(content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalAppTheme provides AppThemeVals(
            colors = LightColors,
            texts = buildAppTexts(LightColors),
            settings = AppSettings(glass = GlassLevel.OFF, reduceMotion = true),
        ),
    ) {
        content()
    }
}
