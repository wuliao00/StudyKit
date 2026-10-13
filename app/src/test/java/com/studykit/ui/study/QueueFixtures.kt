package com.studykit.ui.study

/**
 * 簇型相似度替身：同簇 0.9f（> 词面 tauHi 0.60 ⇒ 相邻违规），异簇 0f（< tauLo 0.15 ⇒ 可换位可当缓冲）。
 *
 * 用它而不是用真实词面打分，是因为这一层要钉的是**约束算法**而不是打分器：
 * 簇结构让"该不该分开"没有歧义，测试期望值能写成字面量。打分器另有 `TextSimilarityTest` 钉。
 *
 * [constant] 非 null 时**换掉整把尺子**：除自反（`a == b` 仍给 1f，那是 [Simularity] 的契约）之外
 * 任何一对都给同一个值，簇映射彻底不参与。Task 8 用它造"处处相似"的最坏情形 ——
 * 那种提供方下既没有可换的候选、也没有合格的缓冲卡，于是插池那一遍会把整只池扫完 —— 但"扫完整只池"
 * **不等于**"预算账上那一项走满"：每一只候选只问到左邻那一眼，且只有第 0 轮在扫，
 * 那笔账（走没走满、差多少）唯一住在 [QueueBuilder] 的类 KDoc，这里不复述。
 * 计数口径一个字都没改：每一次 `sim()` 记一笔，包括走 `constant` 那一条。
 *
 * 下游归属（v3.0-C T3 修复轮 1 的 F13 记的账，修复轮 2 的 N4 更正过 Task 4 那一行）：
 *  - [FixtureSim] 与 [FixtureSim.lookups]：**Task 8 是计划里唯一点名这个替身的下游，也是全仓唯一读 [FixtureSim.lookups] 的地方** ——
 *    计划 Task 8 的 `Consumes` 点名 `FixtureSim`（`QueueFixtures.kt`），那几条查找上界断言与实际查表读数都直接读 `sim.lookups`，
 *    除此之外没有别的读者。但它**不是唯一被 `new` 出来的地方**：Task 3 的 `QueueBuilderAdjacentTest` 也直接构造这个替身
 *    （源码里的构造表达式**正好十处**：`:50`（`simOf` 的函数体 —— `:49` 只是它的签名行，不是构造点）`:178` `:193` `:204` `:216` `:291` `:310` `:313` `:340` `:351`。
 *    这十个数的是构造表达式；`:50` 那一条被 `simOf` 的八处调用点各调一次 ⇒ 运行期的构造次数比十还多。
 *    十处都走默认 `constant`、只借它的 0.9/0.0 簇判定，从不读 `lookups`）。
 *    所以早先这句"只有 Task 8 用这个替身"是**错的**（T8 收尾报告 §3 还把"Task 8 是唯一下游"当成"本轮复核仍然成立"再错一遍），
 *    准确说法是"**计划命名的下游只有 T8、`lookups` 的读者只有 T8**；T3 也构造它但不读读数"。
 *    Task 8 落地时给它加了带默认值的 [FixtureSim.constant]（上面那批 T3 老调用点全走默认值，一行都没改），理由见上一段。
 *    **Task 4 不读这个文件**：它自带 `ScoredSim`，并按计划 Task 3 Step 1 那条裁定（计划内部的"三处共用"
 *    说法已按 T4 自己的文本改成「T3 与 T8」）显式给 subject，与这份夹具不共用。
 *  - `queueItems` / `clusterMap` **已删**（v3.0-C 收口批次）：删前全仓 grep 只剩这两条定义、本条账自己，
 *    以及计划文档里同一份代码片段 —— 零调用点。T4 不读这个文件；T8 的 `fiveHundred()` 自己内联造 500 条、
 *    自己手搓簇 id 映射，没等过这两个助手；简报"三处共用（T3/T4/T8）"那句话不构成下游。
 *  - `withCluster` 已删：计划给它的唯一理由是把 subject 复用成簇标记，而 T4 那条注（计划 Step 1 之后）
 *    明确要求 T4 换成显式 subject，于是它没有下游。
 */
internal class FixtureSim(
    private val cluster: Map<Long, Int>,
    private val constant: Float? = null,
) : Simularity {
    var lookups: Int = 0
        private set

    override fun sim(a: Long, b: Long): Float {
        lookups++
        if (a == b) return 1f
        constant?.let { return it }
        val ca = cluster[a] ?: return 0f
        val cb = cluster[b] ?: return 0f
        return if (ca == cb) 0.9f else 0f
    }
}
