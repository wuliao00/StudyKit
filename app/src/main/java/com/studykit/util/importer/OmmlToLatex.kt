package com.studykit.util.importer

/**
 * OMML（Word 原生公式）→ 线性 LaTeX 片段（v3.5.1）。
 *
 * 为什么需要它：`<m:t>` 里只有字符，没有结构。只按顺序拼字符的话，
 * 分数 `1/2` 会变成 `12`、`x²` 会变成 `x2`、`√3` 会变成 `3` —— 这不是"不好看"，
 * 是**把题目算错了**。这里按 OMML 的结构（`m:f` / `m:sSup` / `m:sSub` / `m:rad` / `m:d` …）
 * 还原成 `\frac{1}{2}`、`x^{2}`、`\sqrt{3}`，外面用 `$…$` 包一层，任何人都看得出这是公式。
 *
 * 取舍：
 * - **认不出的标签按子节点拼接**，绝不丢字。题库里最重要的是内容在，格式可以后补；
 * - 不做完整 OMML 规范：`m:eqArr`、`m:m` 这类只保留行/列分隔，够读就行；
 * - 纯函数、零 Android 依赖，JVM 单测直接覆盖。
 */
object OmmlToLatex {

    /** 一段 `<m:oMath>` 的内容 → `$…$`；解析不出来就返回原文（不吞字） */
    fun renderMath(innerXml: String): String {
        val root = runCatching { parse(innerXml) }.getOrNull()
            ?: return innerXml.trim().let { if (it.isBlank()) "" else "\$$it\$" }
        val body = renderNodes(root).trim()
        if (body.isEmpty()) return ""
        return "\$$body\$"
    }

    private class El(
        val name: String,
        val attrs: Map<String, String> = emptyMap(),
        val kids: MutableList<El> = mutableListOf(),
        var text: String = "",
    )

    // ── 渲染 ──────────────────────────────────────────────────────────────

    private fun renderNodes(nodes: List<El>): String = nodes.joinToString("") { render(it) }

    private fun render(el: El): String = when (el.name) {
        // 文字节点
        "m:t" -> el.text

        // 分数
        "m:f" -> "\\frac{${child(el, "m:num")}}{${child(el, "m:den")}}"

        // 上下标
        "m:sSup" -> "${base(el)}^{${child(el, "m:sup")}}"
        "m:sSub" -> "${base(el)}_{${child(el, "m:sub")}}"
        "m:sSubSup" -> "${base(el)}_{${child(el, "m:sub")}}^{${child(el, "m:sup")}}"
        "m:sPre" -> "_{${child(el, "m:sub")}}^{${child(el, "m:sup")}}${child(el, "m:e")}"

        // 根式
        "m:rad" -> {
            val deg = child(el, "m:deg")
            if (deg.isBlank()) "\\sqrt{${child(el, "m:e")}}" else "\\sqrt[$deg]{${child(el, "m:e")}}"
        }

        // 括号：OMML 的 m:d 自带 begChr/endChr
        "m:d" -> {
            val beg = el.attrs["m:begChr"] ?: el.attrs["begChr"] ?: "("
            val end = el.attrs["m:endChr"] ?: el.attrs["endChr"] ?: ")"
            "$beg${child(el, "m:e")}$end"
        }

        // 大型运算符（求和/积分/连乘）
        "m:nary" -> {
            val chr = el.attrs["m:chr"] ?: el.attrs["chr"] ?: "∑"
            val op = when (chr) {
                "∑", "\\sum" -> "\\sum"
                "∏" -> "\\prod"
                "∫" -> "\\int"
                "∬" -> "\\iint"
                "∭" -> "\\iiint"
                "∮" -> "\\oint"
                else -> chr
            }
            val sub = child(el, "m:sub")
            val sup = child(el, "m:sup")
            buildString {
                append(op)
                if (sub.isNotBlank()) append("_{$sub}")
                if (sup.isNotBlank()) append("^{$sup}")
                append("{${child(el, "m:e")}}")
            }
        }

        // 上划线 / 重音
        "m:bar" -> "\\overline{${child(el, "m:e")}}"
        "m:acc" -> {
            val chr = el.attrs["m:chr"] ?: el.attrs["chr"] ?: "^"
            val cmd = when (chr) {
                "ˆ", "^" -> "\\hat"
                "˜", "~" -> "\\tilde"
                "¯" -> "\\bar"
                "→" -> "\\vec"
                "˙" -> "\\dot"
                else -> "\\hat"
            }
            "$cmd{${child(el, "m:e")}}"
        }

        // 函数（sin/cos/log…）
        "m:func" -> "${child(el, "m:fName")}${child(el, "m:e")}"

        // 极限/上下限
        "m:limLow" -> "${base(el)}_{{${child(el, "m:lim")}}}"
        "m:limUpp" -> "${base(el)}^{{${child(el, "m:lim")}}}"

        // 矩阵/方程组：行列用分隔符保留
        "m:m" -> el.kids.filter { it.name == "m:mr" }.joinToString("; ") { row ->
            row.kids.joinToString(",") { render(it) }
        }
        "m:eqArr" -> el.kids.joinToString(" \\\\ ") { render(it) }

        // 其余容器（m:r / m:e / m:num / m:oMathPara / m:box / 未知标签）都按子节点拼接
        else -> renderNodes(el.kids) + el.text
    }

    /** 上下标的底数：长度大于 1 时包一层花括号，避免 `\frac` 这类宏只吃到第一个字符 */
    private fun base(el: El): String {
        val raw = child(el, "m:e")
        val needsWrap = raw.length > 1 && !(raw.startsWith("{") && raw.endsWith("}")) &&
            !(raw.startsWith("\\") && raw.count { it == ' ' } == 0 && raw.length <= 6)
        return if (needsWrap) "{$raw}" else raw
    }

    private fun child(el: El, name: String): String {
        val found = el.kids.firstOrNull { it.name == name } ?: return ""
        return render(found).trim()
    }

    // ── 极简 XML 解析 ─────────────────────────────────────────────────────

    private fun parse(src: String): List<El> {
        val root = El("#root")
        val stack = ArrayDeque<El>()
        stack.addLast(root)
        var i = 0
        while (i < src.length) {
            val lt = src.indexOf('<', i)
            if (lt < 0) {
                stack.last().text += unescape(src.substring(i))
                break
            }
            if (lt > i) stack.last().text += unescape(src.substring(i, lt))
            val gt = src.indexOf('>', lt + 1)
            if (gt < 0) break
            val tag = src.substring(lt + 1, gt)
            when {
                tag.startsWith("!--") -> {
                    val end = src.indexOf("-->", lt)
                    i = if (end < 0) src.length else end + 3
                    continue
                }

                tag.startsWith("?") || tag.startsWith("!") -> {
                    i = gt + 1
                    continue
                }

                tag.startsWith("/") -> {
                    if (stack.size > 1) stack.removeLast()
                    i = gt + 1
                }

                tag.endsWith("/") -> {
                    val name = tag.dropLast(1).trim().substringBefore(' ')
                    val parent = stack.last()
                    parent.kids += El(name, attributes(tag))
                    i = gt + 1
                }

                else -> {
                    val el = El(tag.trim().substringBefore(' '), attributes(tag))
                    stack.last().kids += el
                    stack.addLast(el)
                    i = gt + 1
                }
            }
        }
        return root.kids
    }

    private fun attributes(tag: String): Map<String, String> {
        val out = HashMap<String, String>(2)
        val body = tag.trim().removeSuffix("/")
        var i = body.indexOf(' ')
        if (i < 0) return out
        val regex = Regex("([\\w:.-]+)\\s*=\\s*\"([^\"]*)\"")
        regex.findAll(body.substring(i)).forEach { m -> out[m.groupValues[1]] = m.groupValues[2] }
        return out
    }

    private fun unescape(value: String): String {
        if (!value.contains('&')) return value
        return value
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&amp;", "&")
    }
}
