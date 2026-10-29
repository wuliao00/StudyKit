package com.studykit.util.importer

import com.studykit.data.entity.Question
import com.studykit.data.question.QuestionType
import org.json.JSONArray

/** 未填学科入库时落成的分组名。键里必须同样归一，否则库里的「未分类」永远对不上导入项的空串 */
const val UNGROUPED_SUBJECT: String = "未分类"

/** 题干与每个选项用控制字符拼键：正文里不会出现，不会把两条不同题误判成同一条 */
const val QUESTION_KEY_SEP: String = "\u0000"

/**
 * 题库导入判重键的**唯一**构造实现。
 *
 * 段序固定为：题干 → 阶段 storageValue → 学科（空串归一为 [UNGROUPED_SUBJECT]）→ 题型 name → 选项 0..n，
 * 各段用 [QUESTION_KEY_SEP] 连接。导入项与库内实体都必须走这里：
 * 两边各拼一份、哪天只改一边，跨批去重就会静默失效。
 *
 * 题型统一用 [QuestionType.name]（`SINGLE`…）而不是 storageValue：库里的列值是 storageValue、
 * 导入项手里是枚举，统一到 name 才能同构 —— 库侧先经 [Question.typeEnum] 归一。
 */
fun questionImportKey(
    stem: String,
    stage: String,
    subject: String,
    type: QuestionType,
    options: List<String>,
): String = buildList(4 + options.size) {
    add(stem)
    add(stage)
    add(subject.ifBlank { UNGROUPED_SUBJECT })
    add(type.name)
    addAll(options)
}.joinToString(QUESTION_KEY_SEP)

/** 导入项 → 判重键（阶段与题干按入口原样，学科交给 [questionImportKey] 归一） */
fun ImportItem.Question.importKey(): String = questionImportKey(
    stem = stem,
    stage = stage.storageValue,
    subject = subject,
    type = type,
    options = options,
)

/**
 * 库内实体 → 判重键。
 * 阶段/题型经枚举归一（脏值兜底规则与判分、筛选一致），选项从 [Question.optionsJson] 解析；
 * 坏 JSON 当空列表，不让一条脏数据把整批导入拦下来。
 */
fun Question.importKey(): String = questionImportKey(
    stem = stem,
    stage = stageEnum.storageValue,
    subject = subject,
    type = typeEnum,
    options = parseImportOptions(optionsJson),
)

private fun parseImportOptions(json: String): List<String> = try {
    val array = JSONArray(json)
    buildList(array.length()) {
        for (i in 0 until array.length()) {
            add(array.optString(i, ""))
        }
    }
} catch (_: Exception) {
    emptyList()
}
