package com.studykit.util.importer

/** 归一化后的保留项 + 被跳过的展示文本（回显给用户，让他知道少导了哪些） */
data class Deduped<T>(val kept: List<T>, val skipped: List<String>)

/** 词面归一：去掉所有空白再转小写。`Apple`、`APPLE`、`A pple` 都算同一个词 */
fun normalizeKey(word: String): String = word.filterNot { it.isWhitespace() }.lowercase()

/**
 * 去重的通用形态：`keyOf` 决定「算不算同一个」，`displayOf` 决定被跳过时回显什么文本。
 * 每个键只有**首现的那一条**进 `kept`（原顺序不变），后到的进 `skipped`。
 *
 * 刻意返回条目本身，而不是把保留的键做成 `Set` 让调用方再 `in` 一遍过滤：那种写法对批内重复是错的 ——
 * 第二条的键也在保留集合里，于是被判为应保留，一次贴进两份相同清单就会写双份。按下标走一遍才是对的。
 *
 * `existingKeys` 要传**已归一化**的键；手上是库内原文的话走 [dedupeWords]，归一由它负责。
 */
fun <T> dedupeBy(
    items: List<T>,
    keyOf: (T) -> String,
    displayOf: (T) -> String,
    existingKeys: Set<String> = emptySet(),
): Deduped<T> {
    val seen = HashSet<String>(existingKeys.size + items.size)
    seen.addAll(existingKeys)
    val kept = ArrayList<T>(items.size)
    val skipped = ArrayList<String>()
    items.forEach { item ->
        if (seen.add(keyOf(item))) kept.add(item) else skipped.add(displayOf(item))
    }
    return Deduped(kept = kept, skipped = skipped)
}

/**
 * 单词去重。`words.word` **没有唯一索引**（计划 Task 1 已核实），SQLite 不会替我们挡重复，
 * 所以「跳过重复」必须显式做，而且批内互重也要算 —— 一次贴进两份相同清单很常见。
 * `skipped` 回的是用户写的那个词面原样，不是归一化键；入库用的 `kept` 同样是原文。
 */
fun dedupeWords(items: List<ImportItem.Word>, existing: Set<String>): Deduped<ImportItem.Word> =
    dedupeBy(
        items = items,
        keyOf = { normalizeKey(it.word) },
        displayOf = { it.word },
        existingKeys = existing.mapTo(HashSet(existing.size)) { normalizeKey(it) },
    )
