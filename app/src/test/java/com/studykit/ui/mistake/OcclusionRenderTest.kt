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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 遮挡渲染层（[OcclusionImage] / [OcclusionEditor] / 详情页那一格 [MistakePhotoSection]）的守卫
 * （v3.0-B T15 起，T16 接编辑器、T17 接详情页）。
 *
 * 各组用例各挡一种塌法：
 * - 前三个钉 [shapePixelRect]：它是「归一化的落库坐标」与「屏幕上那块罩子」之间**唯一**一处算术
 *   （R2：编辑器与渲染层共用它，谁都不许再抄一份）。宽高各走自己的轴这条必须单独钉 ——
 *   照片不是正方形，串了轴的话块会画歪，而库里那份 JSON 看不出任何异常。
 * - 接一条钉「画罩子」与「点按全揭」是**两个判定**（T17 拆开；Canvas 画出来是像素、钉不住，
 *   能钉的是那两个条件分别是谁）。
 * - 中间三个钉手势的存在性：有块才挂点按层（R1，全揭只有一次机会）、点一次恰好回调一次、
 *   没块与已揭开时那个节点**根本不该在树上**。
 * - 接着四条钉编辑器（T16）：真的拖一段就恰好落一块、块数那句跟着改，且编辑态**不带**"点按全揭"
 *   那个手势；当前工具是交回调用方的（R4），编辑器自己不私藏；连拖两笔必须落**两**块
 *   （回调那份草稿得是最新的，否则后一块把前一块顶掉，T17 的详情页编辑直接踩在这上面）。
 * - 最后五条钉详情页那一格（T17 的 [MistakePhotoSection]）：入口文案跟着已存块数换、进编辑态出
 *   编辑器提示且没有全揭手势、保存交的是**草稿**那份、取消一次都不写，以及最要紧的一条 ——
 *   重做阶段一块都没画时（照片本就整张扣着）**编辑器根本不许出现**，因为编辑器渲染的是没盖东西的
 *   原图，在这里允许打开等于把 T14 刚封住的印刷答案重新摊开。
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
    fun `画罩子与能点揭是两个判定_编辑器那种调用只画不揭`() {
        // 纯判定那两条（`OcclusionOverlay`）：渲染层里那块 Canvas 画不画是像素，钉不住；
        // 但"画与揭分别由哪两个条件决定"钉得住 —— 把它们捆回一个 flag（T15 那版的写法）
        // 这条立刻红，而红的那一句正是详情页编辑的死因：编辑器不给回调，于是连块都看不见。
        assertTrue(occlusionCoversPainted(shapeCount = 2, revealed = false))
        assertFalse(occlusionRevealTappable(shapeCount = 2, revealed = false, hasRevealCallback = false))
        // 详情页重做阶段：同一对形状 + 给了全揭回调 ⇒ 既画也点得开
        assertTrue(occlusionRevealTappable(shapeCount = 2, revealed = false, hasRevealCallback = true))
        // 已经揭开 ⇒ 两件事一起停：罩子不许在摊开的照片上留着，也不许还吃点击
        assertFalse(occlusionCoversPainted(shapeCount = 2, revealed = true))
        assertFalse(occlusionRevealTappable(shapeCount = 2, revealed = true, hasRevealCallback = true))
        // 没块就没有层，也就没有那个空转的点击层（读屏用户不该去点一张不动的图）
        assertFalse(occlusionCoversPainted(shapeCount = 0, revealed = false))
        assertFalse(occlusionRevealTappable(shapeCount = 0, revealed = false, hasRevealCallback = true))
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

    @Test
    fun `连拖两笔就落两块_第二次拿到的是最新那份草稿`() {
        // 拖拽回调是从 `pointerInput` 里发出的，而那个 block 不跟手重开：不加
        // `rememberUpdatedState` 的话，第二笔拿到的是**第一次组合**时那份草稿，
        // 前一块被后一块顶掉 —— 数量那句会停在"已加 1 处遮挡"，而库里也确实只剩一块。
        // 这条只有连拖两次才暴露，单次拖拽的用例（上面那条）抓不到。
        showAt { Host { EditorHarness(onAdd = {}) } }
        composeTestRule.onNodeWithContentDescription(DRAG_AREA).assertIsDisplayed()
        injectDrag()
        composeTestRule.onNodeWithText("已加 1 处遮挡").assertExists()
        injectDrag()
        composeTestRule.onNodeWithText("已加 2 处遮挡").assertExists()
    }

    // ── 详情页的补画入口 + 内嵌编辑器（T17）──────────────────────────────

    @Test
    fun `详情页入口按已存块数换文案，点它才进编辑`() {
        var started = 0
        showAt {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                SectionHarness(
                    phase = RedoPhase.CHECK,
                    saved = listOf(DRAFT_A, DRAFT_B),
                    onStartEditing = { started++ },
                )
            }
        }
        composeTestRule.onNodeWithText(occlusionEntryLabel(2)).assertExists()
        // 文案必须真的跟着块数换（写死"在照片上画遮挡"的话这条就红了）
        composeTestRule.onNodeWithText(occlusionEntryLabel(0)).assertDoesNotExist()
        composeTestRule.onNodeWithText(occlusionEntryLabel(2)).performClick()
        assertEquals("入口只负责进编辑，不该顺手写库", 1, started)
    }

    @Test
    fun `编辑态出现编辑器那句提示，且此时没有点按全揭那个手势`() {
        var saved = 0
        showAt {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                SectionHarness(
                    phase = RedoPhase.REDO,
                    saved = listOf(DRAFT_A, DRAFT_B),
                    editing = true,
                    onSave = { saved++ },
                )
            }
        }
        composeTestRule.onNodeWithText(imageEditorHint()).assertExists()
        // 编辑期间罩子照样画（用户要看的正是"我盖对地方没有"），但**点不开**：
        // 一次误触把刚盖好的罩子揭掉是帮倒忙，全揭只归重做阶段那一屏。
        composeTestRule.onNodeWithContentDescription("遮住答案，点按全揭").assertDoesNotExist()
        composeTestRule.onNodeWithText("已加 2 处遮挡").assertExists()
        // 编辑态不渲染照片段：那句"已遮 N 处，先回忆，点按全揭"与入口钮都该让位
        composeTestRule.onNodeWithText(imageCoverHint(2)).assertDoesNotExist()
        composeTestRule.onNodeWithText(occlusionEntryLabel(2)).assertDoesNotExist()
        composeTestRule.onNodeWithText("保存").performClick()
        assertEquals("保存把这一份草稿交出去，一次就好", 1, saved)
    }

    @Test
    fun `重做阶段一块都没画时编辑器根本不出现（那时打开就是把答案摊开）`() {
        showAt {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                SectionHarness(
                    phase = RedoPhase.REDO,
                    saved = emptyList(),
                    editing = true,
                    draft = emptyList(),
                )
            }
        }
        // 这一屏的照片整张是扣住的（`imageCovered`）：编辑器渲染的是**没盖东西的原图**，
        // 允许在这里打开就等于把 T14 刚封住的印刷答案重新摊开，所以编辑态自己也要过同一道闸。
        composeTestRule.onNodeWithText(imageEditorHint()).assertDoesNotExist()
        composeTestRule.onNodeWithText("整张照片扣住，重做后再看").assertIsDisplayed()
        composeTestRule.onNodeWithText(occlusionEntryLabel(0)).assertDoesNotExist()
    }

    @Test
    fun `取消编辑不写库，草稿整份交给回调`() {
        val drafts = mutableListOf<List<OcclusionShape>>()
        var cancelled = 0
        showAt {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                SectionHarness(
                    phase = RedoPhase.CHECK,
                    saved = listOf(DRAFT_A),
                    editing = true,
                    // 草稿比已存的多一块：交出去的必须是草稿，不是已存的那份
                    draft = listOf(DRAFT_A, DRAFT_B),
                    onSave = { drafts.add(it) },
                    onCancelEditing = { cancelled++ },
                )
            }
        }
        composeTestRule.onNodeWithText("取消").performClick()
        assertEquals("取消只是丢弃本地态，一次都不写", 0, drafts.size)
        assertEquals(1, cancelled)
        composeTestRule.onNodeWithText("保存").performClick()
        assertEquals(listOf(listOf(DRAFT_A, DRAFT_B)), drafts)
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
 * 编辑器宿主：草稿按**生产的形状**持有 —— 一串 JSON（建卡流的 `shapesRaw`、详情页的
 * `occlusionDraftRaw` 都是这一位，因为 `List<OcclusionShape>` 过不了 Bundle），块列表是它的派生 val。
 *
 * 这个形状不是洁癖，而是这条量具**能不能红**的关键：`onAdd` 闭包拿的是派生出来的那个 val，
 * 不是 state 本身。若渲染层发出的回调不是最新那一份（见 `OcclusionEditor` 的 `onAddNow`），
 * 第二笔就会用第一次组合时的旧列表覆盖草稿 ⇒ 前一块被后一块顶掉；而用 `mutableStateOf<List>` 当草稿
 * 的宿主测不出这件事（那种写法读 state 读到的是当下值）。
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
    var raw by remember { mutableStateOf("{}") }
    val shapes = shapesFromJson(raw)
    val editor: @Composable () -> Unit = {
        EditorBody(
            shapes = shapes,
            type = type,
            onTypeChange = onTypeChange,
            onAdd = {
                raw = shapesToJson(shapes + it)
                onAdd(it)
            },
            onUndo = { raw = shapesToJson(shapes.dropLast(1)) },
            onClear = { raw = "{}" },
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

/** 两块固定的草稿形状：详情页那组用例只关心"交出去的是哪一份"，不关心几何（几何归纯层钉） */
private val DRAFT_A = OcclusionShape.Rect(0.1, 0.2, 0.5, 0.4)
private val DRAFT_B = OcclusionShape.Ellipse(0.6, 0.7, 0.1, 0.08)

/**
 * 详情页照片那一段的宿主（T17）。
 *
 * `gate` 走**生产**的 [buildRedoGate]，且 `shapeCount` 由 `saved.size` 喂进去 —— 页面上这两件事
 * 本来就是同一个 `occlusionShapes(current)` 的两半（一半进判定、一半给渲染层），在这里分成两参数
 * 喂就是假场景。编辑态的草稿由参数给定（生产里它是那一串 JSON 串），回调一律记进调用方的清单：
 * VM 那一行 `setOcclusion` 本文件不测 —— VM 级用例本仓已有（`ui/study/MockSessionResumeWriterTest`
 * 真起 `StudyViewModel` + 内存 Room），但那条覆盖的是套卷恢复与交卷，错题侧的 `setOcclusion`
 * 还没排进去，所以本文件钉到"把哪一份草稿交出去"为止。
 */
@Composable
private fun SectionHarness(
    phase: RedoPhase,
    saved: List<OcclusionShape>,
    editing: Boolean = false,
    draft: List<OcclusionShape> = saved,
    onSave: (List<OcclusionShape>) -> Unit = {},
    onCancelEditing: () -> Unit = {},
    onStartEditing: () -> Unit = {},
) {
    MistakePhotoSection(
        imageFile = File("nope.jpg"),
        gate = buildRedoGate("题干", "", phase, hasImage = true, shapeCount = saved.size),
        shapes = saved,
        editing = editing,
        draft = draft,
        editorType = EditorShape.RECT,
        onTypeChange = {},
        onAdd = {},
        onUndo = {},
        onClear = {},
        onStartEditing = onStartEditing,
        onCancelEditing = onCancelEditing,
        onSave = onSave,
        onOpenFullImage = {},
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
