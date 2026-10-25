package com.studykit.domain.design

import org.json.JSONObject

/**
 * `fields_json` 的编解码（`org.json`，与 `questions.options_json` / `mock_sessions.answers`
 * 同一手法）。
 *
 * 为什么不用 kotlinx.serialization：这一列就是一组 `String → String`，没有嵌套、没有多态，
 * 为它引一套序列化框架（再加一个编译器插件）不划算；而 `org.json` 主源集本来就有
 * （Android 系统实现，不额外占包体），JVM 单测侧 `app/build.gradle.kts` 也已有 `org.json`
 * 依赖可跑。
 *
 * 三个约定：
 *  - [encode] **键排序**：同一份表单重复保存得到逐字节相同的 JSON。将来做备份比对、
 *    去重、甚至按内容算哈希时，不必先"规范化"一次。
 *  - [decode] **绝不抛**：空串、`"[]"`、被截断的 JSON、值不是字符串 —— 一律当空表处理。
 *    读路径崩掉的代价是"打开方案就闪退"，而用户手上一份能重建的表单数据并不存在，
 *    宁可显示空表单也不能让编辑页起不来。
 *  - [isEmpty] 只看"有没有任何非空白内容"：一个有 8 个空串的表单等于没填。
 */
object DesignPlanCodec {

    /**
     * 编码为 JSON 对象文本，键按字典序排列。
     *
     * 值原样写入（不 trim）：用户在解释里手敲的换行与缩进是他排版的一部分，
     * 存储层不做二次加工；"空不空"由 [isEmpty] 与生成器各自按 trim 判断。
     *
     * 为什么不是 `JSONObject(fields).toString()`：org.json 的 `JSONObject` 内部是 `HashMap`，
     * `toString()` 按哈希桶顺序输出，排好序再 `put` 也管不住键序（20231013 的源码里就是
     * `new HashMap<>()`，换一个实现/版本顺序还会变）。这里只借 `JSONObject.quote` 做转义
     * （它才是"JSON 字符串转义"这件事的权威实现），拼装由自己按键序完成 —— 同一份表单
     * 逐字节可复现，解码仍走 `JSONObject`，往返一致。
     */
    fun encode(fields: Map<String, String>): String =
        fields.toSortedMap().entries.joinToString(separator = ",", prefix = "{", postfix = "}") { (key, value) ->
            JSONObject.quote(key) + ":" + JSONObject.quote(value)
        }

    /**
     * 解码回 `LinkedHashMap`（键同样排序，方便 UI 逐字段回填时行为稳定）。
     *
     * 坏 JSON 返回空 map，不抛异常 —— 包括 `null`、空串、顶层不是对象（如 `"[]"`）、
     * 截断文本。`null` 值（手改过的库）落成空串：表单里它就是"没填"，而不是字符串 "null"。
     */
    fun decode(json: String?): Map<String, String> {
        if (json.isNullOrBlank()) return emptyMap()
        return try {
            val obj = JSONObject(json)
            val keys = buildList {
                val iterator = obj.keys()
                while (iterator.hasNext()) add(iterator.next())
            }.sorted()
            val out = LinkedHashMap<String, String>(keys.size)
            keys.forEach { key ->
                out[key] = if (obj.isNull(key)) "" else obj.optString(key, "")
            }
            out
        } catch (e: Exception) {
            // 读路径的兜底：坏数据不该让编辑页消失。异常类型不细分是刻意的 ——
            // org.json 在不同实现（Android 系统 / 测试期 org.json:json）下抛的异常并不完全一致。
            emptyMap()
        }
    }

    /** 所有值 trim 后都为空（空 map 也算空）。 */
    fun isEmpty(values: Map<String, String>): Boolean = values.values.all { it.trim().isEmpty() }
}
