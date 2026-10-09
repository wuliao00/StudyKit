package com.studykit.ui.study

/**
 * 簇型相似度替身：同簇 0.9f（> 词面 tauHi 0.60 ⇒ 相邻违规），异簇 0f（< tauLo 0.15 ⇒ 可换位可当缓冲）。
 *
 * 用它而不是用真实词面打分，是因为这一层要钉的是**约束算法**而不是打分器：
 * 簇结构让"该不该分开"没有歧义，测试期望值能写成字面量。打分器另有 `TextSimilarityTest` 钉。
 *
 * 下游归属（v3.0-C T3 修复轮 1 的 F13 记的账，修复轮 2 的 N4 更正过 Task 4 那一行）：
 *  - [FixtureSim] 与 [FixtureSim.lookups]：**只有 Task 8** 用这个替身 —— 计划 Task 8 的 `Consumes` 点名
 *    `FixtureSim`（`QueueFixtures.kt`），两条查找上界断言直接读 `sim.lookups`。
 *    **Task 4 不读这个文件**：它自带 `ScoredSim`，并按计划 Task 3 Step 1 那条裁定（计划内部的"三处共用"
 *    说法已按 T4 自己的文本改成「T3 与 T8」）显式给 subject，与这份夹具不共用。
 *  - [queueItems] / [clusterMap]：本提交里**零调用点**，而且计划里也没有任何一条测试文本调用它们
 *    （T4 不读这个文件；T8 的 `fiveHundred()` 自己内联造 500 条、自己手搓簇 id 映射）。
 *    它们唯一还可能被读到的地方是 Task 8 的 `fiveHundred()` —— 那两个助手干的正是它手写的那两件事；
 *    除此之外没有预期读者，Task 9 收口时按这条账删除（简报"三处共用（T3/T4/T8）"那句话不构成下游）。
 *  - `withCluster` 已删：计划给它的唯一理由是把 subject 复用成簇标记，而 T4 那条注（计划 Step 1 之后）
 *    明确要求 T4 换成显式 subject，于是它没有下游。
 */
internal class FixtureSim(private val cluster: Map<Long, Int>) : Simularity {
    var lookups: Int = 0
        private set

    override fun sim(a: Long, b: Long): Float {
        lookups++
        if (a == b) return 1f
        val ca = cluster[a] ?: return 0f
        val cb = cluster[b] ?: return 0f
        return if (ca == cb) 0.9f else 0f
    }
}

/** 造 n 条同簇/异簇混合的队列项：ids 从 [base] 递增，urgency 递减，簇按 [clusters] 轮着给 */
internal fun queueItems(base: Long, n: Int, clusters: List<Int>): List<QueueItem> =
    (0 until n).map { i ->
        QueueItem(
            id = base + i,
            urgency = (n - i).toFloat(),
            subject = "S${clusters[i % clusters.size]}",
        )
    }

internal fun clusterMap(items: List<QueueItem>, clusterOf: (QueueItem) -> Int): Map<Long, Int> =
    items.associate { it.id to clusterOf(it) }
