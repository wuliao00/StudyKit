package com.studykit.ui.mistake

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
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
 * 三组用例各挡一种塌法：
 * - 前三个钉 [shapePixelRect]：它是「归一化的落库坐标」与「屏幕上那块罩子」之间**唯一**一处算术
 *   （R2：编辑器与渲染层共用它，谁都不许再抄一份）。宽高各走自己的轴这条必须单独钉 ——
 *   照片不是正方形，串了轴的话块会画歪，而库里那份 JSON 看不出任何异常。
 * - 中间三个钉手势的存在性：有块才挂点按层（R1，全揭只有一次机会）、点一次恰好回调一次、
 *   没块与已揭开时那个节点**根本不该在树上**。
 * - 最后两个钉编辑器（T16）：真的拖一段就恰好落一块、块数那句跟着改，且编辑态**不带**"点按全揭"
 *   那个手势；当前工具是交回调用方的（R4），编辑器自己不私藏。
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

    // ── 编辑器（T16）：同一批渲染件的另一侧用法 ───────────────────────────

    @Test
    fun `编辑器里拖一段就落一块，且编辑器不带全揭手势`() {
        val drafts = mutableListOf<OcclusionShape>()
        showAt { Host { EditorHarness(onAdd = { drafts.add(it) }) } }
        // 量具对照：拖拽区（那层带手势的画布）没组合成功的话，后面那次滑动就是空的
        composeTestRule.onNodeWithContentDescription(DRAG_AREA).assertIsDisplayed()
        composeTestRule.onNodeWithText(imageEditorHint()).assertIsDisplayed()
        injectDrag()
        assertEquals("一次拖拽恰好落一块", 1, drafts.size)
        // 限高那一档里画布之下的文字被挤成零尺寸（见 EditorHost 注释），所以钉"在树上"而不是"看得见"
        composeTestRule.onNodeWithText("已加 1 处遮挡").assertExists()
        // 编辑时不该有"点按全揭"那个手势：块还没画完，点一下把刚画的罩子揭掉是帮倒忙
        composeTestRule.onNodeWithContentDescription("遮住答案，点按全揭").assertDoesNotExist()
    }

    @Test
    fun `编辑器把当前工具交回调用方，自己不私藏`() {
        // R4：一个屏幕上的"当前工具"是页面态，得跟着其它输入态一起活过进程重建
        val seen = mutableListOf<EditorShape>()
        showAt { Host { EditorHarness(bounded = false, onTypeChange = { seen.add(it) }) } }
        composeTestRule.onNodeWithText("矩形").assertIsDisplayed()
        composeTestRule.onNodeWithText("椭圆").performClick()
        composeTestRule.waitForIdle()
        assertEquals(listOf(EditorShape.ELLIPSE), seen)
        // 一块都没画时清空那颗钮根本不该出现（撤销则是不禁用的那种"看得见但按不动"）
        composeTestRule.onNodeWithText("清空").assertDoesNotExist()
    }

    private fun showAt(block: @Composable () -> Unit) = composeTestRule.setContent { Host { block() } }

    /**
     * 注入一次对角拖拽（起点 20,20 → 终点 120,120）。
     *
     * 分四步推、而不是一句 `swipeFromTo` 跳到位：手法照 `RecallGateRenderTest.injectSwipe`，
     * `detectDragGestures` 要先过 touchSlop 才开始跟手，一帧跳到位会把整段位移喂给 slop 判定。
     * 终点 120px 只是"手指挪够了"的意思，具体归一化成多少由视图实际宽高决定 ——
     * 这条用例钉的是「一次拖拽落恰好一块」，不是某个具体比例（那个归 `OcclusionEditorTest`）。
     */
    @OptIn(ExperimentalTestApi::class)
    private fun injectDrag() {
        composeTestRule.onNodeWithContentDescription(DRAG_AREA).performTouchInput {
            down(Offset(20f, 20f))
            repeat(4) { moveBy(Offset(25f, 25f)) }
            up()
        }
        composeTestRule.waitForIdle()
    }
}

/** 编辑器的拖拽区描述（R8：手势层当控件用就要有读屏标签，测试也按它定位） */
private const val DRAG_AREA = "遮挡绘制区，拖拽画一块"

/**
 * 编辑器宿主：块列表由宿主持有（生产里是 `MistakeCaptureScreen` 的 `shapesRaw` 那一串），
 * 测试只往里看回调落了几块。渲染整块 [OcclusionEditor] 而不是只渲染画布 ——
 * 「拖一段 -> 计数那句跟着变」这条链只有整块在树上才成立。
 *
 * 两种量具形状（都是为了绕开"假照片解不出像素"这件事，不改被测代码）：
 * - `bounded = true`：整块限高。Robolectric 的窗口只有约 320x470 像素，而 coil 没有 painter 时
 *   会把图区撑满可用高度，于是画布拿到真实尺寸（可拖）、后面的兄弟节点被挤成零尺寸 ——
 *   所以限高那一档只用来测**拖拽**，那块之下出现的文字一律 `assertExists`（口径同
 *   `MockExamRenderTest` 那句「没滚到位的节点，assertIsDisplayed 与 performClick 都会以看不见为由失败」）。
 * - `bounded = false`：套进 `verticalScroll`，即生产里 `MistakeCaptureScreen` 根列的形状 ——
 *   无限高时图区不抢空间，下面两排按钮回到可见区，`performClick` 才按得动。
 * 生产里照片有真实宽高比、又住在滚动列里，两种抢位都不会发生；这两档只是量具。
 */
@Composable
private fun EditorHarness(
    type: EditorShape = EditorShape.RECT,
    onTypeChange: (EditorShape) -> Unit = {},
    onAdd: (OcclusionShape) -> Unit = {},
    bounded: Boolean = true,
) {
    var shapes by remember { mutableStateOf(emptyList<OcclusionShape>()) }
    val editor: @Composable () -> Unit = {
        EditorBody(
            shapes = shapes,
            type = type,
            onTypeChange = onTypeChange,
            onAdd = {
                shapes = shapes + it
                onAdd(it)
            },
            onUndo = { shapes = shapes.dropLast(1) },
            onClear = { shapes = emptyList() },
        )
    }
    if (bounded) {
        Column(modifier = Modifier.height(300.dp)) { editor() }
    } else {
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) { editor() }
    }
}

@Composable
private fun EditorBody(
    shapes: List<OcclusionShape>,
    type: EditorShape,
    onTypeChange: (EditorShape) -> Unit,
    onAdd: (OcclusionShape) -> Unit,
    onUndo: () -> Unit,
    onClear: () -> Unit,
) {
    OcclusionEditor(
        photo = File("nope.jpg"),
        shapes = shapes,
        type = type,
        onTypeChange = onTypeChange,
        onAdd = onAdd,
        onUndo = onUndo,
        onClear = onClear,
    )
}

/**
 * 测试宿主：只把被测那一段摆进主题里，不渲染整屏（整屏要 [MistakeViewModel]，
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
