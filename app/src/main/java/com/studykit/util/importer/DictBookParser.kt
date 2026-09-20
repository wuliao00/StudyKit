package com.studykit.util.importer

import org.json.JSONArray
import org.json.JSONObject

/** 在线词库（kajweb/dict）的一条书目信息 */
data class DictBookInfo(
    val id: String,
    val title: String,
    val wordNum: Int,
    val sizeBytes: Int,
    val introduce: String,
    val tags: List<String>,
    val downloadUrl: String,
)

/**
 * 词库数据解析。两件事：
 * 1. 目录 JSON → [DictBookInfo] 列表。缺 `offlinedata` 的条目直接丢弃 ——
 *    没有下载地址的书目在界面上只能显示成"点不动的行"，不如不出现；
 * 2. 词表 NDJSON → `ImportPlan`。**词表文件整体不是合法 JSON**，是一行一个对象，
 *    所以只能逐行 `JSONObject(line)`，任何一行坏了只影响它自己（进待修正区）。
 *
 * 字段路径来自 2026-09-20 对 `PEPXiaoXue3_2.zip` 的实测抽样（72 行：`trans` 100% 有、
 * `sentence` 约 95% 有），不是照文档猜的。
 */
object DictBookParser {

    fun parseCatalogue(json: String): List<DictBookInfo> {
        val books = JSONObject(json).optJSONObject("data")?.optJSONArray("normalBooksInfo") ?: JSONArray()
        return buildList {
            for (i in 0 until books.length()) {
                val book = books.optJSONObject(i) ?: continue
                val id = book.optString("id")
                val url = book.optString("offlinedata")
                if (id.isEmpty() || url.isEmpty()) continue
                add(
                    DictBookInfo(
                        id = id,
                        title = book.optString("title").ifEmpty { id },
                        wordNum = book.optInt("wordNum"),
                        sizeBytes = book.optInt("size"),
                        introduce = book.optString("introduce"),
                        tags = tagNamesOf(book.optJSONArray("tags")),
                        downloadUrl = url,
                    ),
                )
            }
        }
    }

    /** 整份 NDJSON → 解析计划（与四个行解析器共用 ImportPlan 形态，下游无需分支） */
    fun parseNdjson(text: String): ImportPlan =
        TextCleaner.splitLines(text).map { parseEntry(line = it.text, lineNo = it.line) }.toPlan()

    fun parseEntry(line: String, lineNo: Int): LineResult {
        if (line.isBlank()) return LineResult.Blank
        val root = runCatching { JSONObject(line) }.getOrNull()
            ?: return bad(lineNo, line, RejectReason.EMPTY_FIELDS)
        // `wordHead` 在 content.word 这一层，不在 content.word.content 里 —— 计划把它挂在 inner 上，
        // 那个路径永远取不到值，兜底等于没写
        val wordObj = root.optJSONObject("content")?.optJSONObject("word")
        val inner = wordObj?.optJSONObject("content")
        val headWord = root.optString("headWord")
            .ifEmpty { wordObj?.optString("wordHead").orEmpty() }
        val meanings = buildList {
            inner?.optJSONArray("trans")?.let { array ->
                for (i in 0 until array.length()) {
                    array.optJSONObject(i)?.optString("tranCn")
                        ?.takeIf { it.isNotBlank() }?.let { add(it) }
                }
            }
        }
        if (headWord.isBlank() || meanings.isEmpty()) {
            return bad(lineNo, line, RejectReason.EMPTY_FIELDS)
        }
        return LineResult.Ok(
            ImportItem.Word(
                sourceLine = lineNo,
                // 整行 JSON 平均三百多字节，一本 3000 词的库留着原文就是 1MB 级的无用地占内存；
                // 这里只用于展示与「这一条是什么」，故留词头。坏行仍保留原文（见 bad()）。
                raw = headWord,
                word = TextCleaner.normalizeAccents(headWord),
                meaning = meanings.joinToString("；"),
                example = firstSentence(inner),
            ),
        )
    }

    private fun tagNamesOf(tags: JSONArray?): List<String> {
        if (tags == null) return emptyList()
        return buildList {
            for (t in 0 until tags.length()) {
                tags.optJSONObject(t)?.optString("tagName")
                    ?.takeIf { it.isNotEmpty() }?.let { add(it) }
            }
        }
    }

    private fun firstSentence(inner: JSONObject?): String =
        inner?.optJSONObject("sentence")?.optJSONArray("sentences")
            ?.optJSONObject(0)?.optString("sContent").orEmpty().trim()

    private fun bad(lineNo: Int, raw: String, reason: RejectReason): LineResult.Bad =
        LineResult.Bad(RejectedLine(sourceLine = lineNo, raw = raw, reason = reason))
}
