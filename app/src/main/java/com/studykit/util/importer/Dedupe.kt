package com.studykit.util.importer

/** 归一化后的保留项 + 被跳过的原始键（回显给用户，让他知道少导了哪些） */
data class Deduped<T>(val kept: List<T>, val skipped: List<String>)

/** 词面归一：去掉所有空白再转小写。`Apple`、`APPLE`、`A pple` 都算同一个词 */
fun normalizeKey(word: String): String = word.filterNot { it.isWhitespace() }.lowercase()

/**
 * 导入去重。`words.word` **没有唯一索引**（计划 Task 1 已核实），SQLite 不会替我们挡重复，
 * 所以「跳过重复」必须在这里显式做，而且**批内互重也要算** —— 一次贴进两份相同清单很常见。
 *
 * 归一化键只用于比较；`kept` 与 `skipped` 一律回原文，否则入库的就是被改脏的内容、
 * 「已跳过」列表也没法让用户认出来。`existing` 传库内已有词面（见 `WordRepository.wordTexts`）。
 */
fun dedupeWords(items: List<ImportItem.Word>, existing: Set<String>): Deduped<ImportItem.Word> {
    val seen = HashSet<String>(existing.size + items.size)
    existing.forEach { seen.add(normalizeKey(it)) }
    val kept = ArrayList<ImportItem.Word>(items.size)
    val skipped = ArrayList<String>()
    items.forEach { item ->
        if (seen.add(normalizeKey(item.word))) kept.add(item) else skipped.add(item.word)
    }
    return Deduped(kept = kept, skipped = skipped)
}

/** 题目/笔记没有天然唯一键，退化为「整条内容归一化后比较、去重」；同样只回原文 */
fun dedupeStrings(keys: List<String>): Deduped<String> {
    val seen = HashSet<String>(keys.size)
    val kept = ArrayList<String>(keys.size)
    val skipped = ArrayList<String>()
    keys.forEach { key ->
        if (seen.add(normalizeKey(key))) kept.add(key) else skipped.add(key)
    }
    return Deduped(kept = kept, skipped = skipped)
}
