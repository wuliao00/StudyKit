package com.studykit.ui.mistake

import com.studykit.data.entity.Mistake
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * 图片遮挡块（v3.0-B，文档 L1884：`extra.shapes = [{t:rect|ellipse|poly, 归一化坐标 0-1}]`）。
 *
 * 坐标全部归一化到 0..1：照片的像素尺寸随拍摄设备与 `MistakeImageStore` 的长边压缩
 * （1600px）而变，只有比例在「画的那次」和「看的那次」之间成立。
 *
 * `kind` 是唯一开关：`Mistake.KIND_OCCLUSION` 才承认 `extra` 里的形状，`KIND_BASIC` 一律当普通卡。
 * 理由是要一条能一眼看穿的规则 —— 万一 `extra` 里躺着脏数据（旧备份恢复、手工改库），
 * 判断不能靠"形状数组非空"这种间接证据，否则一张普通错题会凭空变成遮挡卡。
 *
 * 读侧逐条容错（与 `AppSettings.fromMap`、`shapesFromJson` 的兄弟 `parseOptions` 同纪律）：
 * 单条读不通丢那条，整串读不通丢全表，绝不抛，也绝不拼出半个形状。
 * 本文件保持**纯**：只有 stdlib 与 org.json，不许出现任何 Android API（渲染在 T15 的 `OcclusionOverlay.kt`）。
 */
internal sealed interface OcclusionShape {

    /** 归一化左上/右下（写入前已保证 left<=right、top<=bottom） */
    data class Rect(val left: Double, val top: Double, val right: Double, val bottom: Double) : OcclusionShape

    data class Ellipse(val centerX: Double, val centerY: Double, val radiusX: Double, val radiusY: Double) : OcclusionShape

    /** 至少 3 个点（少于 3 个不构成多边形，读侧直接丢） */
    data class Polygon(val points: List<NormalizedPoint>) : OcclusionShape
}

internal data class NormalizedPoint(val x: Double, val y: Double)

/**
 * 短于这个比例的块多半是误触（照片长边 1600px 时约等于 3px）。
 *
 * 写侧那道阈值（`ui.mistake.MIN_DRAG` = 1%）必须不低于这里，否则会画出「当场看得见、
 * 重新读回来就没了」的块。
 */
private const val MIN_EXTENT = 0.005

/** `t` 字段用的三个 token，与文档 L1884 一致；`poly` 不写成 `polygon`（文档拼法为准） */
private const val TYPE_RECT = "rect"
private const val TYPE_ELLIPSE = "ellipse"
private const val TYPE_POLY = "poly"

private fun clamp01(v: Double): Double = if (v.isNaN()) 0.0 else v.coerceIn(0.0, 1.0)

internal fun shapesToJson(shapes: List<OcclusionShape>): String {
    val arr = JSONArray()
    shapes.forEach { s ->
        val o = when (s) {
            is OcclusionShape.Rect -> JSONObject().put("t", TYPE_RECT).put("l", s.left).put("t2", s.top).put("r", s.right).put("b", s.bottom)
            is OcclusionShape.Ellipse -> JSONObject().put("t", TYPE_ELLIPSE).put("cx", s.centerX).put("cy", s.centerY).put("rx", s.radiusX).put("ry", s.radiusY)
            is OcclusionShape.Polygon -> JSONObject().put("t", TYPE_POLY).put(
                "pts",
                JSONArray().apply { s.points.forEach { p -> put(JSONObject().put("x", p.x).put("y", p.y)) } },
            )
        }
        arr.put(o)
    }
    return JSONObject().put("shapes", arr).toString()
}

internal fun shapesFromJson(raw: String): List<OcclusionShape> {
    if (raw.isBlank()) return emptyList()
    return try {
        val arr = JSONObject(raw).optJSONArray("shapes") ?: return emptyList()
        buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                when (o.optString("t")) {
                    TYPE_RECT -> {
                        // 先夹回 0..1、再把边角排成左上/右下（反接的两次拖拽是真实手势），
                        // **然后**才量尺寸：量的这一步若在排序之前，一条对角线写反的块会算出
                        // 负宽高而被当成误触丢掉 —— 用户画的答案区就这样消失了。
                        val l = clamp01(o.optDouble("l"))
                        val r = clamp01(o.optDouble("r"))
                        val t = clamp01(o.optDouble("t2"))
                        val b = clamp01(o.optDouble("b"))
                        val left = minOf(l, r)
                        val right = maxOf(l, r)
                        val top = minOf(t, b)
                        val bottom = maxOf(t, b)
                        if (right - left >= MIN_EXTENT && bottom - top >= MIN_EXTENT) {
                            add(OcclusionShape.Rect(left, top, right, bottom))
                        }
                    }
                    TYPE_ELLIPSE -> {
                        val rx = o.optDouble("rx")
                        val ry = o.optDouble("ry")
                        if (rx.isFinite() && ry.isFinite() && rx >= MIN_EXTENT && ry >= MIN_EXTENT) {
                            add(OcclusionShape.Ellipse(clamp01(o.optDouble("cx")), clamp01(o.optDouble("cy")), rx.coerceAtMost(1.0), ry.coerceAtMost(1.0)))
                        }
                    }
                    TYPE_POLY -> {
                        val pts = o.optJSONArray("pts")
                        val list = buildList {
                            if (pts != null) {
                                for (j in 0 until pts.length()) {
                                    val p = pts.optJSONObject(j) ?: continue
                                    add(NormalizedPoint(clamp01(p.optDouble("x")), clamp01(p.optDouble("y"))))
                                }
                            }
                        }
                        if (list.size >= 3) add(OcclusionShape.Polygon(list))
                    }
                }
            }
        }
    } catch (e: JSONException) {
        emptyList()
    } catch (e: NullPointerException) {
        // 保险：org.json 在个别 Android 版本上对缺失键的数组访问抛 NPE 而非返回 null。
        // 读侧全程用 opt*，实际抛不出；留着是因为"脏 JSON 只能退化成没形状"这条不许破。
        emptyList()
    }
}

/** 这张错题上画了哪些遮挡块；`kind != KIND_OCCLUSION` 一律空表（见类注释那条开关规则） */
internal fun occlusionShapes(mistake: Mistake): List<OcclusionShape> =
    if (mistake.kind == Mistake.KIND_OCCLUSION) shapesFromJson(mistake.extra) else emptyList()
