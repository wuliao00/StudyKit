package com.studykit.ui.study

/**
 * 词面相似度 —— v3.0-C 的相似度提供方之一，也是本仓当前唯一的提供方。
 *
 * 它**不是**语义模型：只比「题干去掉标点后剩下的字符二元组有没有重合」，外加一条考点标签的规则。
 * 之所以值得做，是因为题库里真正的连排事故恰好是它看得见的那一类 ——
 * 同一道改变个说法再问一遍（`下列句子中没有语病的一项是` vs `下列句子中，没有语病的一项是（　）`），
 * 连着出等于把答案送到用户眼前，交错对这一类是有效的。
 * 它看不见的是"意思相近但用词不同"（` velocity 变化越快` vs `加速度越大`），那类要嵌入向量。
 *
 * 值域严格落在 `[0, 1]`：二元组 Dice 的定义保证上界是 1，下界是 0；考点地板是个常量。
 * 所以阈值可以按同一把尺子放在 `InterleavePolicy` 里，不需要在这层做任何"像不像"的判断。
 *
 * ## 阈值刻度（重要，别和文档混用）
 * 文档 §② 的 `TAU_HI = 0.85 / TAU_LO = 0.30` 是给**余弦相似度**定的。字符二元组 Dice 的分布和它
 * 不是一把尺子：**预期分布（未在本仓实测校准）：无关题偏低、改说法的变式题偏高，标点差异不影响分值**。
 * 本仓没跑过标注集，所以这里不给具体区间——没量过的数字不写。按这把预期，把 0.85 照搬到 Dice 刻度上，
 * 变式题会普遍算不上违规 ⇒ 相邻修复这一遍**永远不触发**，功能开着和没开一样（v3.0-B Task 7
 * 的闸门就是这么静默死掉的）。词面刻度定在 `LexicalInterleave.DEFAULT`（Task 2），
 * 并且是**未经 200+200 标注对校准的初值** —— 校准是语义包计划的质量门，不在这份计划里。
 */
internal object TextSimilarity {

    /**
     * 考点标签相同时的相似度地板值。
     *
     * `questions.concept_tag`（v2.7 起有这一列，`''` = 未标注）按定义就是"同一个考点的变式候选"，
     * 所以两题同标签 ⇒ 无论字面像不像都算易混。取 0.65f：落在 Task 2 的混淆区间
     * `[drillLo=0.40, drillHi=0.75]` 之内（于是会被 drills 强制同场、隔 3..5 题），
     * 又高于 `tauHi=0.60`（于是同时也触发"不许相邻"），两个约束方向一致，不打架。
     */
    const val CONCEPT_FLOOR = 0.65f

    /**
     * 丢掉标点与空白、保留字（字母/数字/常见汉字），字母转小写。
     *
     * **数字是保留的**（`isLetterOrDigit` 含 Nd 类）：`1. 下列…` 归一化成 `1下列…`，
     * 所以只有编号不同的两题干（`1下列…` vs `2下列…`）不会得 1；带圈序号（① 属 Unicode No 类）
     * `isLetterOrDigit` 为假，确实会被丢掉。
     *
     * 用 `Char.isLetterOrDigit()` 判定：它对 CJK 基本区（U+4E00-U+9FFF）为真，所以常见中文题干留下；
     * 但补充平面的汉字（CJK Ext-B，例如 𠮷 U+20BB7）在 Kotlin 的 `Char` 循环里是两段代理（Cs 类），
     * `isLetterOrDigit` 对两段都为假，因此该字符会被静默丢掉 —— 不改成按 codePoint 迭代
     * （本计划不为此加复杂度），代价是补充平面汉字不算字，所以这里的说明不能写成"汉字都保留"。
     * 全角字母数字（U+FF21 这类）同样被 `isLetterOrDigit` 判为真而保留成"全角形态"，
     * 与半角不是同一个字符 —— 题库里没这种混写，不为此加一层映射（加了也没人验证）。
     */
    fun normalize(text: String): String = buildString(text.length) {
        for (ch in text) {
            if (ch.isLetterOrDigit()) append(ch.lowercaseChar())
        }
    }

    /** 相邻两字符一组。长度 1 退化为单字符集合，空串给空集合（宁可不相似，也别把空串当相似） */
    fun bigrams(s: String): Set<String> {
        if (s.isEmpty()) return emptySet()
        if (s.length == 1) return setOf(s)
        val out = HashSet<String>(s.length - 1)
        for (i in 0 until s.length - 1) out.add(s.substring(i, i + 2))
        return out
    }

    /** Dice = 2|A∩B| / (|A|+|B|)。任一为空 ⇒ 0f（没有可比的东西，不硬造相似） */
    fun dice(a: Set<String>, b: Set<String>): Float {
        if (a.isEmpty() || b.isEmpty()) return 0f
        val small: Set<String>
        val large: Set<String>
        if (a.size <= b.size) { small = a; large = b } else { small = b; large = a }
        var inter = 0
        for (gram in small) if (gram in large) inter++
        return 2f * inter / (a.size + b.size)
    }

    /**
     * 题面相似度。同考点（都非空且相等）⇒ 至少 [CONCEPT_FLOOR]；否则只取字面 Dice。
     *
     * 空标签不参与判定：`'' == ''` 在数据里是"两题都没标考点"，不是"同一考点"。
     * 不这样收窄的话，未标注的题库（`concept_tag` 默认 `''`）会全场互相抬到 0.65，
     * 交错退化成"按 id 重排"，而用户看到的是"我的题全被判成易混了"。
     */
    fun questionSimilarity(stemA: String, tagA: String, stemB: String, tagB: String): Float {
        val literal = dice(bigrams(normalize(stemA)), bigrams(normalize(stemB)))
        val sameConcept = tagA.isNotBlank() && tagA == tagB
        return if (sameConcept) maxOf(literal, CONCEPT_FLOOR) else literal
    }
}
