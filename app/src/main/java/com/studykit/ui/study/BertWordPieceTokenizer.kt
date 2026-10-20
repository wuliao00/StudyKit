package com.studykit.ui.study

/**
 * BERT WordPiece 分词器 —— 逐条复刻 bge-small-zh-v1.5 的 `tokenizer.json`。
 *
 * 上游归一化参数：`clean_text=true`、`handle_chinese_chars=true`、`strip_accents=null`、
 * `lowercase=false`。预处理是 `BertPreTokenizer`：空白切断；CJK（`4E00–9FFF`、`3400–4DBF`）
 * 单字成 token；ASCII 标点（`!–/`、`:–@`、`[–\``、`{–~`）与 Unicode `P*` 类单独成 token；
 * 其余连续非空白非标点串成一个词。随后按词表做**贪心最长匹配**，续片加 `##`；
 * 整词任何一段匹配不上就给 `[UNK]`。词长超过 `max_input_chars_per_word=100` 的整个词直接 `[UNK]`。
 *
 * 输出固定是 `[CLS] + tokens + [SEP]`，**不补 `[PAD]`**；超过 [maxLength] 时保留前
 * `maxLength - 2` 个 token 再补 `[SEP]`。特殊 id 从传入词表里查（标准 BERT：`[UNK]=100`、
 * `[CLS]=101`、`[SEP]=102`），词表缺任何一个就在构造时直接失败，不静默猜。
 */
internal class BertWordPieceTokenizer(vocabLines: List<String>) {

    private val vocab: Map<String, Int>
    private val unkId: Int
    private val clsId: Int
    private val sepId: Int

    init {
        val map = HashMap<String, Int>(vocabLines.size * 4 / 3 + 1)
        for ((index, raw) in vocabLines.withIndex()) {
            // 词表里没有空行；仅容忍 Windows 行尾，词条内部的字符（包括空白）原样保留。
            val token = raw.removeSuffix("\r")
            if (token.isEmpty()) continue
            // 同一词条重复出现时以第一次为准，与 `HashMap` 构建顺序一致。
            map.putIfAbsent(token, index)
        }
        vocab = map
        unkId = map["[UNK]"] ?: error("词表缺少 [UNK]")
        clsId = map["[CLS]"] ?: error("词表缺少 [CLS]")
        sepId = map["[SEP]"] ?: error("词表缺少 [SEP]")
    }

    /**
     * 把 [text] 编成 id 序列：`[CLS] ... [SEP]`，不补 `[PAD]`。
     *
     * [maxLength] 是**含两个特殊 token** 的总长度上限；至少为 2（此时只剩 `[CLS][SEP]`）。
     */
    fun encode(text: String, maxLength: Int = 64): IntArray {
        require(maxLength >= 2) { "maxLength 至少为 2，实际=$maxLength" }

        val ids = ArrayList<Int>(minOf(maxLength, 64))
        ids.add(clsId)
        for (word in preTokenize(cleanText(text))) {
            for (id in wordPiece(word)) {
                if (ids.size >= maxLength - 1) return (ids + sepId).toIntArray()
                ids.add(id)
            }
        }
        ids.add(sepId)
        return ids.toIntArray()
    }

    /**
     * 上游 `BertNormalizer.clean_text`：去掉 `U+0000`、`U+FFFD` 与 `Cc` 控制字符，
     * 但 `\t`/`\n`/`\r` 保留下来交给空白切分。不做小写、不做去重音。
     */
    private fun cleanText(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (cp != 0 && cp != 0xFFFD && !isControl(cp)) out.appendCodePoint(cp)
            i += Character.charCount(cp)
        }
        return out.toString()
    }

    private fun isControl(cp: Int): Boolean {
        if (cp == '\t'.code || cp == '\n'.code || cp == '\r'.code) return false
        return Character.getType(cp) == Character.CONTROL.toInt()
    }

    /** `BertPreTokenizer`：空白 / CJK 单字 / 标点切断，其余连续字符为一词。 */
    private fun preTokenize(text: String): List<String> {
        val words = ArrayList<String>()
        val current = StringBuilder()
        var i = 0

        fun flush() {
            if (current.isNotEmpty()) {
                words.add(current.toString())
                current.setLength(0)
            }
        }

        while (i < text.length) {
            val cp = text.codePointAt(i)
            when {
                isWhitespace(cp) -> flush()
                isChineseChar(cp) -> {
                    flush()
                    words.add(String(Character.toChars(cp)))
                }
                isPunctuation(cp) -> {
                    flush()
                    words.add(String(Character.toChars(cp)))
                }
                else -> current.appendCodePoint(cp)
            }
            i += Character.charCount(cp)
        }
        flush()
        return words
    }

    /** WordPiece 贪心最长匹配；任一段匹配失败则整词 `[UNK]`。 */
    private fun wordPiece(word: String): List<Int> {
        if (word.codePointCount(0, word.length) > MAX_INPUT_CHARS_PER_WORD) return listOf(unkId)

        val ids = ArrayList<Int>(4)
        var start = 0
        while (start < word.length) {
            var end = word.length
            var matched = -1
            while (end > start) {
                val piece = if (start == 0) {
                    word.substring(start, end)
                } else {
                    "##" + word.substring(start, end)
                }
                val id = vocab[piece]
                if (id != null) {
                    matched = id
                    break
                }
                end -= Character.charCount(word.codePointBefore(end))
            }
            if (matched < 0) return listOf(unkId)
            ids.add(matched)
            start = end
        }
        return ids
    }

    private fun isWhitespace(cp: Int): Boolean =
        Character.isWhitespace(cp) || Character.isSpaceChar(cp)

    private fun isChineseChar(cp: Int): Boolean =
        cp in 0x4E00..0x9FFF || cp in 0x3400..0x4DBF

    /**
     * ASCII 标点四段（含 `= < > | ~` 这类 Unicode 里归 `S*` 的符号）或 Unicode `P*`。
     * 非 ASCII 只认 `P*`：`＝`（全角等号，`Sm`）不切，与上游 `BertPreTokenizer` 一致。
     */
    private fun isPunctuation(cp: Int): Boolean {
        if (cp < 0x80) {
            return cp in 0x21..0x2F || cp in 0x3A..0x40 ||
                cp in 0x5B..0x60 || cp in 0x7B..0x7E
        }
        return when (Character.getType(cp)) {
            Character.CONNECTOR_PUNCTUATION.toInt(),
            Character.DASH_PUNCTUATION.toInt(),
            Character.START_PUNCTUATION.toInt(),
            Character.END_PUNCTUATION.toInt(),
            Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
            Character.FINAL_QUOTE_PUNCTUATION.toInt(),
            Character.OTHER_PUNCTUATION.toInt(),
            -> true
            else -> false
        }
    }

    private companion object {
        /** 上游 `max_input_chars_per_word`：按 Unicode 码点计，超长整词直接 `[UNK]`。 */
        const val MAX_INPUT_CHARS_PER_WORD = 100
    }
}
