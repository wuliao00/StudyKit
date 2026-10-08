package com.studykit.ui.mistake

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.studykit.data.entity.Mistake
import com.studykit.ui.components.AppButton
import com.studykit.ui.components.AppButtonTone
import com.studykit.ui.theme.AppTheme
import java.io.File

// 拖拽画罩（v3.0-B T16，文档 L1884「建卡编辑器拖拽加罩」）。
//
// 这个文件分两段：**上半是纯的**（枚举、换算、文案、落库那一对列），**下半才是 Compose**。
// 纯的那段单独存在是因为它承担的正是这台设备最容易静默失败的一步：手指轨迹 -> 落库 JSON。
// 块画歪半格、误触留了个 0 尺寸的块、写了 extra 却忘了改 kind —— 三种都不会报错，
// 只会让用户下一次复习时看见一道没盖住的答案。全都钉在 `OcclusionEditorTest`（JVM，无需 Robolectric）。

/**
 * 编辑器支持的两型（计划 D7）：矩形与椭圆。
 *
 * 多边形要"采点 + 闭合"的第二手势：格式（T13）与渲染（T15）都带着它，但这里先不给手势 ——
 * 以后加手势时不用动数据格式，也不用动迁移。
 */
internal enum class EditorShape {
    RECT,
    ELLIPSE,
}

/**
 * 归一化下 1% 以内当误触。
 *
 * 这个阈值必须**不低于**读侧那条（`Occlusion.MIN_EXTENT` = 0.5%）：写侧收得比读侧松的话，
 * 会画出「当场看得见、重新读回来就没了」的块 —— 用户画的答案区在下一次进详情页时静默消失。
 */
private const val MIN_DRAG = 0.01

/**
 * 一次拖拽 -> 一个遮挡块。坐标必须已经归一化（0..1），composable 那边负责除以视图宽高。
 * 返回 null = 这块不该存在（太小或夹不出来），调用方直接忽略这次拖拽。
 *
 * 顺序是**先夹回图内、再排边角、然后才量尺寸**，与 `shapesFromJson` 那条读取同一个口径：
 * 量尺寸放在排序之前的话，一条对角线写反的拖拽会算出负宽高而被当成误触丢掉。
 */
internal fun dragToShape(
    startU: Double,
    startV: Double,
    endU: Double,
    endV: Double,
    type: EditorShape,
): OcclusionShape? {
    val l = minOf(startU, endU).coerceIn(0.0, 1.0)
    val r = maxOf(startU, endU).coerceIn(0.0, 1.0)
    val t = minOf(startV, endV).coerceIn(0.0, 1.0)
    val b = maxOf(startV, endV).coerceIn(0.0, 1.0)
    if (r - l < MIN_DRAG || b - t < MIN_DRAG) return null
    return when (type) {
        EditorShape.RECT -> OcclusionShape.Rect(l, t, r, b)
        EditorShape.ELLIPSE -> OcclusionShape.Ellipse((l + r) / 2, (t + b) / 2, (r - l) / 2, (b - t) / 2)
    }
}

/**
 * 存档里的工具名 -> [EditorShape]；认不出的（含 null / 空串 / 大小写不同 / `POLYGON`）一律回 [EditorShape.RECT]。
 * 与 `decodeRedoPhase` 同一条纪律：重建进程时读到脏串宁可回到默认工具，也别拿"猜出来的那一档"直接开画。
 */
internal fun decodeEditorShape(raw: String?): EditorShape =
    EditorShape.entries.firstOrNull { it.name == raw } ?: EditorShape.RECT

/** 建卡那句说明（文档 L1898 逐字）。与 [coveredLabel] / [imageCoverHint] 同一族：文案收在纯函数里才钉得进测试 */
internal fun imageEditorHint(): String = "拖拽添加遮挡；复习时先回忆、点按再揭。"

/** 块数那句：空表也要说清"现在一块都没有"，别留一片让人猜是不是编辑器坏了 */
internal fun editorShapeCountLabel(count: Int): String =
    if (count == 0) "还没有遮挡块" else "已加 $count 处遮挡"

/** 遮挡卡落库的那一对列（`mistakes.kind` + `mistakes.extra`） */
internal data class OcclusionColumns(val kind: Int, val extra: String)

/**
 * ⛔ `kind` 与 `extra` 成对决定（T13 那条「kind 是唯一开关」全靠这一步成立）。
 *
 * 读侧只认 `kind == KIND_OCCLUSION` 的行，所以：
 * - 有块 → `KIND_OCCLUSION` + 那份 JSON；
 * - 没块（包括脏串读不出块）→ `KIND_BASIC` + `{}`。
 *
 * 两种写歪的后果都是静默的：只写 extra 不改 kind = 一张看不出问题的普通卡，遮挡块躺在库里没人读；
 * 只改 kind 不写 extra = 页面承认这是遮挡卡、读到零块，于是照片一格都不盖，还附赠一句「已遮 0 处」。
 *
 * `extra` 写的是**重新编码**的那份（`shapesToJson(shapesFromJson(raw))`），不是原样转手：
 * 库里因此只留读侧本来就会读成的那一版，不留两份真值。
 */
internal fun occlusionColumns(shapesJson: String): OcclusionColumns {
    val shapes = shapesFromJson(shapesJson)
    return if (shapes.isEmpty()) {
        OcclusionColumns(kind = Mistake.KIND_BASIC, extra = "{}")
    } else {
        OcclusionColumns(kind = Mistake.KIND_OCCLUSION, extra = shapesToJson(shapes))
    }
}

/**
 * 拖拽画罩编辑器。
 *
 * 手势手法照 `ui/habit/SnakeBoard.kt`（本仓唯一的 `detectDragGestures` 站点）：`onDragStart` 记下起点、
 * `onDrag` 跟着手指走、`onDragEnd` 落块，`onDragCancel` 两个坐标一起清掉。
 * 像素 -> 归一化只在这一个 composable 里做（`onSizeChanged` 拿到的宽高 + 拖拽自己的坐标），
 * 换算本身交给纯的 [dragToShape]；已定型的块与预览块都走渲染层那一个 [shapePixelRect]（R2）。
 *
 * 三处刻意的形状：
 * - **`type` 由调用方持有**（[onTypeChange] 回传），编辑器自己不存：一个屏幕上的"当前工具"是页面态，
 *   进程重建后应与其它输入态一起恢复（`MistakeCaptureScreen` 存的是枚举名串）。
 * - **已定型的块用 [OcclusionImage] 画**，`onRevealAll` 不给（默认 null）⇒ 编辑时没有"点按全揭"那个手势。
 * - **拖拽中的预览是半透明 `warning` + `warningInk` 描边**（R3）：它还没被承诺成罩子，
 *   就不该长得像罩子（实底 lightbox）。松手后那一块换成实底，用户看得懂"这下真的盖住了"。
 *
 * 无图时整块不该出现 —— 由调用方保证（`MistakeCaptureScreen` 里那张照片可用才渲染这一块），
 * 参数类型也是非空 [File]：没有照片就没有可拖的画布，这个类型层面就不成立。
 */
@Composable
internal fun OcclusionEditor(
    photo: File,
    shapes: List<OcclusionShape>,
    type: EditorShape,
    onTypeChange: (EditorShape) -> Unit,
    onAdd: (OcclusionShape) -> Unit,
    onUndo: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var dragStart by remember { mutableStateOf<Offset?>(null) }   // 视图内像素坐标
    var dragCurrent by remember { mutableStateOf<Offset?>(null) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    // 拖拽中的那块先在本地算出来：Canvas 的 content 是带接收者的 lambda，里面不能非局部 return，
    // 所以"算不出就不画"这件事必须在进 Canvas 之前决定（这条坑写在注释里，免得下次又试）。
    val liveShape = remember(dragStart, dragCurrent, viewSize, type) {
        val a = dragStart
        val b = dragCurrent
        if (a == null || b == null || viewSize.width <= 0 || viewSize.height <= 0) {
            null
        } else {
            dragToShape(
                startU = a.x.toDouble() / viewSize.width,
                startV = a.y.toDouble() / viewSize.height,
                endU = b.x.toDouble() / viewSize.width,
                endV = b.y.toDouble() / viewSize.height,
                type = type,
            )
        }
    }
    Column(modifier = modifier) {
        Text(text = imageEditorHint(), style = texts.caption, color = colors.secondaryText)
        Spacer(Modifier.height(AppTheme.space.sm))
        Box(
            Modifier
                .fillMaxWidth()
                .onSizeChanged { viewSize = it }
                .pointerInput(type, viewSize) {
                    detectDragGestures(
                        onDragStart = { off ->
                            dragStart = off
                            dragCurrent = off
                        },
                        onDrag = { change, _ ->
                            dragCurrent = change.position
                            change.consume()
                        },
                        // 终点读 dragCurrent：这一版 foundation 的 onDragEnd 是 () -> Unit，
                        // 松手那一帧不再额外给坐标，而最后一帧 onDrag 写的就是手指最后报出的位置。
                        onDragEnd = {
                            val a = dragStart
                            val b = dragCurrent
                            val s = viewSize
                            if (a != null && b != null && s.width > 0 && s.height > 0) {
                                dragToShape(
                                    startU = a.x.toDouble() / s.width,
                                    startV = a.y.toDouble() / s.height,
                                    endU = b.x.toDouble() / s.width,
                                    endV = b.y.toDouble() / s.height,
                                    type = type,
                                )?.let(onAdd)
                            }
                            dragStart = null
                            dragCurrent = null
                        },
                        onDragCancel = {
                            dragStart = null
                            dragCurrent = null
                        },
                    )
                }
                // 这块画布就是个控件（拖一下落一块）：没有标签的可点区域对读屏是一处空白
                .semantics { contentDescription = "遮挡绘制区，拖拽画一块" },
        ) {
            // 已定型的块：复用渲染层，`onRevealAll` 不给（编辑时不该有"点按全揭"那个手势）
            OcclusionImage(model = photo, shapes = shapes)
            val live = liveShape
            if (live != null) {
                Canvas(Modifier.matchParentSize()) {
                    val r = shapePixelRect(live, size.width, size.height)
                    val fill = colors.warning.copy(alpha = 0.35f)
                    val edge = Stroke(width = 2.dp.toPx())
                    when (live) {
                        is OcclusionShape.Rect -> {
                            drawRect(color = fill, topLeft = r.topLeft, size = r.size)
                            drawRect(color = colors.warningInk, topLeft = r.topLeft, size = r.size, style = edge)
                        }

                        is OcclusionShape.Ellipse -> {
                            drawOval(color = fill, topLeft = r.topLeft, size = r.size)
                            drawOval(color = colors.warningInk, topLeft = r.topLeft, size = r.size, style = edge)
                        }

                        // 编辑器画不出多边形（D7）。这一支只为补全 sealed 的分派：
                        // 真从别处塞进来一个 poly 就按外接框预览，几何仍走同一个 shapePixelRect。
                        is OcclusionShape.Polygon -> drawRect(color = fill, topLeft = r.topLeft, size = r.size)
                    }
                }
            }
        }
        Spacer(Modifier.height(AppTheme.space.xs))
        Text(
            text = editorShapeCountLabel(shapes.size),
            style = texts.caption,
            color = if (shapes.isEmpty()) colors.secondaryText else colors.successInk,
        )
        Spacer(Modifier.height(AppTheme.space.sm))
        Row(horizontalArrangement = Arrangement.spacedBy(AppTheme.space.sm)) {
            AppButton(
                text = "矩形",
                secondary = type == EditorShape.RECT,
                onClick = { onTypeChange(EditorShape.RECT) },
                modifier = Modifier.weight(1f),
            )
            AppButton(
                text = "椭圆",
                secondary = type == EditorShape.ELLIPSE,
                onClick = { onTypeChange(EditorShape.ELLIPSE) },
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(AppTheme.space.sm))
        Row(horizontalArrangement = Arrangement.spacedBy(AppTheme.space.sm)) {
            AppButton(
                text = "撤销上一块",
                secondary = true,
                enabled = shapes.isNotEmpty(),
                onClick = onUndo,
                modifier = Modifier.weight(1f),
            )
            // 只有两块以上才给"清空"：一块都没有或只有一块时，撤销那颗钮就是全部需要的出口
            if (shapes.size > 1) {
                AppButton(
                    text = "清空",
                    secondary = true,
                    tone = AppButtonTone.Warning,
                    onClick = onClear,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
