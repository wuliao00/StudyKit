package com.studykit.ui.study

/**
 * 簇型相似度替身：同簇 0.9f（> 词面 tauHi 0.60 ⇒ 相邻违规），异簇 0f（< tauLo 0.15 ⇒ 可换位可当缓冲）。
 *
 * 用它而不是用真实词面打分，是因为这一层要钉的是**约束算法**而不是打分器：
 * 簇结构让"该不该分开"没有歧义，测试期望值能写成字面量。打分器另有 `TextSimilarityTest` 钉。
 *
 * 下游归属（v3.0-C T3 修复轮 1 的 F13 记的账，给 Task 9 收口时对照，免得留下没人读的助手）：
 *  - [FixtureSim] 与 [FixtureSim.lookups]：Task 4 与 Task 8 都用这个替身，
 *    其中 `lookups` 是 Task 8 查找上界断言要数的计数器（计划里 `QueueBuilderStressTest` 直接读它）。
 *  - [queueItems] / [clusterMap]：简报点名"三处共用（T3/T4/T8）"所以留着，但**计划现在写的 T4/T8 测试文本
 *    并没有调用它们**（T4 用 `drillItems()` 显式给 subject，T8 内联造 500 条）⇒ 本提交里它们是零调用点，
 *    Task 9 收口时若仍然无人调用就删。
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
