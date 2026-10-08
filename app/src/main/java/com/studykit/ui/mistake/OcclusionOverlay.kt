package com.studykit.ui.mistake

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.studykit.ui.theme.AppTheme
import java.io.File

/**
 * 罩子画不画（T17 与手势拆开的那一半）：有块、且此刻不该露出，就画。
 * 与"能不能点揭"**无关** —— 编辑器不给全揭回调，块照样得画出来，否则用户看不见自己盖在哪。
 */
internal fun occlusionCoversPainted(shapeCount: Int, revealed: Boolean): Boolean =
    shapeCount > 0 && !revealed

/**
 * 点按全揭那一层挂不挂：罩子正在画（否则没有东西可揭）**且**调用方真给了回调。
 * 编辑态走 `hasRevealCallback = false` —— 一次误触把刚盖好的罩子揭掉是帮倒忙，
 * 全揭只归重做阶段那一屏（裁定见 `MistakeDetailScreen.MistakePhotoSection` 的注释）。
 */
internal fun occlusionRevealTappable(shapeCount: Int, revealed: Boolean, hasRevealCallback: Boolean): Boolean =
    occlusionCoversPainted(shapeCount, revealed) && hasRevealCallback

/**
 * 照片 + 遮挡块 + 点按全揭（v3.0-B T15）。
 *
 * 渲染是**一个 Canvas**（文档 L1884「单 View 自绘，不引 SVG 库」）：图在下、罩在上，
 * 罩的几何全部由 [shapePixelRect] 从归一化坐标换算，`Canvas` 里按类型分派 drawRect/drawOval/drawPath。
 *
 * 揭开是**整层一起揭**（[onRevealAll]），不做"逐块点揭"：逐块点按要先证明用户点的是块而不是图，
 * 而这一块屏上本来就只有一张图和一层罩子，猜错一次的代价是把答案亮出来。
 * 语义与 `RedoGate` 那条一致：先回忆，再揭。
 *
 * 罩子色取已有令牌 `AppTheme.colors.lightbox`（灯箱压暗那一档，两主题同为不透明黑）并**不加 alpha**：
 * 罩住就必须真的看不见，半透的罩子等于把答案压暗了继续给人看。这里没有新造色值。
 *
 * 画罩子与挂手势是**两个判定**（[occlusionCoversPainted] / [occlusionRevealTappable]，T17 拆开）：
 * 画不画只看"有没有块、此刻该不该露出"，挂不挂点按层才看有没有 [onRevealAll]。
 * 捆着写的后果是编辑器（它刻意不给回调）连块都画不出来 —— 用户拖完只看见"已加 N 处"那句变了，
 * 照片上什么都没盖，于是详情页补画遮挡这一步没法判断自己盖对了地方没有。
 * 拆开之后编辑态看得见罩子、点不开罩子。没块可揭的照片也不许带一个空转的点击层
 * （那会让读屏用户去点一张不动的图）。
 *
 * 圆角与发丝描边沿用图片卡那一口径（`radius.lg` + 1dp `divider`，与 `MistakeDetailScreen`
 * 的照片段、`MistakeCaptureScreen` 的预览段同一条），所以换掉 `AsyncImage` 之后页面分层不变。
 *
 * @param photoDescription 底层照片的读屏描述。默认「错题照片」；详情页那张图在没画块时是
 * 可点开的灯箱入口，那边传「查看大图」（T14 的渲染守卫钉的就是这个串）。
 */
@Composable
internal fun OcclusionImage(
    model: File,
    shapes: List<OcclusionShape>,
    modifier: Modifier = Modifier,
    onRevealAll: (() -> Unit)? = null,
    revealed: Boolean = false,
    photoDescription: String = "错题照片",
) {
    val colors = AppTheme.colors
    // 局部 val：布尔推不出智能转换，`revealTappable` 为真时 onRevealAll 必然非空仍要再判一次
    val reveal = onRevealAll
    val paintsCover = occlusionCoversPainted(shapes.size, revealed)
    val revealTappable = occlusionRevealTappable(shapes.size, revealed, reveal != null)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppTheme.radius.lg))
            .border(width = 1.dp, color = colors.divider, shape = RoundedCornerShape(AppTheme.radius.lg)),
    ) {
        AsyncImage(
            model = model,
            contentDescription = photoDescription,
            contentScale = ContentScale.FillWidth,
            modifier = Modifier.fillMaxWidth(),
        )
        if (paintsCover) {
            // 只有真挂得起"点按全揭"的那一层才带手势与读屏标签：编辑态那块是**给看的**罩子，
            // 不是控件，给它一个"点按全揭"的标签等于骗读屏用户去点一个按不动的东西。
            val coverModifier = if (revealTappable && reveal != null) {
                Modifier
                    .matchParentSize()
                    // 这层就是那个"揭开"的控件：没有标签的可点 Canvas 对读屏是一处空白
                    .semantics { contentDescription = "遮住答案，点按全揭" }
                    .pointerInput(shapes) { detectTapGestures { reveal() } }
            } else {
                Modifier.matchParentSize()
            }
            Canvas(modifier = coverModifier) {
                shapes.forEach { s ->
                    val r = shapePixelRect(s, size.width, size.height)
                    when (s) {
                        is OcclusionShape.Rect -> drawRect(color = colors.lightbox, topLeft = r.topLeft, size = r.size)
                        is OcclusionShape.Ellipse -> drawOval(color = colors.lightbox, topLeft = r.topLeft, size = r.size)
                        is OcclusionShape.Polygon -> {
                            // 逐点走同一个换算函数（不另起一份乘法）；Path().apply{} + drawPath 的手法照
                            // ui/stats/LoadBandChart.kt。多边形在编辑器里画不出来（计划 D7），
                            // 但格式与这里都支持它：老数据或别处写进来的 poly 照样能被盖住。
                            val pts = s.points.map { normalizedToPx(it.x, it.y, size.width, size.height) }
                            // 点数 >=3 由 shapesFromJson 保证（少于 3 个的那条读侧直接丢）
                            val path = Path().apply {
                                moveTo(pts.first().x, pts.first().y)
                                pts.drop(1).forEach { p -> lineTo(p.x, p.y) }
                                close()
                            }
                            drawPath(path, color = colors.lightbox)
                        }
                    }
                }
            }
        }
    }
}

/**
 * 归一化 -> 像素的**唯一一次**乘法：u 只乘宽、v 只乘高。
 *
 * 收成一处是为了 R2：[shapePixelRect] 的三种形状与 Canvas 里多边形的描点都从这里取值，
 * 于是「渲染」与「编辑器预览」不可能各自四舍五入到不同的地方。本仓已经在"同一份算术抄两遍"
 * 上摔过不止一次。
 */
private fun normalizedToPx(u: Double, v: Double, widthPx: Float, heightPx: Float): Offset =
    Offset((u * widthPx).toFloat(), (v * heightPx).toFloat())

/**
 * 归一化 -> 像素。宽高各走自己的轴（照片不是正方形时这是唯一正确的读法）。纯算术，单独钉
 * （`OcclusionRenderTest` 前三条）。
 *
 * 不需要 Context、也不读 `LocalDensity`：DrawScope 的 `size` 已经是像素。多边形给的是**外接框**
 * —— 用来量块在哪儿、要不要重绘，裁剪用不上（Canvas 里画 poly 走逐点描边）。
 */
internal fun shapePixelRect(shape: OcclusionShape, widthPx: Float, heightPx: Float): Rect = when (shape) {
    is OcclusionShape.Rect -> Rect(
        normalizedToPx(shape.left, shape.top, widthPx, heightPx),
        normalizedToPx(shape.right, shape.bottom, widthPx, heightPx),
    )

    is OcclusionShape.Ellipse -> Rect(
        normalizedToPx(shape.centerX - shape.radiusX, shape.centerY - shape.radiusY, widthPx, heightPx),
        normalizedToPx(shape.centerX + shape.radiusX, shape.centerY + shape.radiusY, widthPx, heightPx),
    )

    is OcclusionShape.Polygon -> {
        val pts = shape.points.map { normalizedToPx(it.x, it.y, widthPx, heightPx) }
        Rect(pts.minOf { it.x }, pts.minOf { it.y }, pts.maxOf { it.x }, pts.maxOf { it.y })
    }
}
