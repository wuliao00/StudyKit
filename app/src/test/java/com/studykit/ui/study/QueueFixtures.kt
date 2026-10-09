package com.studykit.ui.study

/**
 * 簇型相似度替身：同簇 0.9f（> 词面 tauHi 0.60 ⇒ 相邻违规），异簇 0f（< tauLo 0.15 ⇒ 可换位可当缓冲）。
 *
 * 用它而不是用真实词面打分，是因为这一层要钉的是**约束算法**而不是打分器：
 * 簇结构让"该不该分开"没有歧义，测试期望值能写成字面量。打分器另有 `TextSimilarityTest` 钉。
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

/** 只改簇不改其它字段 */
internal fun List<QueueItem>.withCluster(clusterOf: (QueueItem) -> Int): List<QueueItem> =
    map { it.copy(subject = "C${clusterOf(it)}") }

internal fun clusterMap(items: List<QueueItem>, clusterOf: (QueueItem) -> Int): Map<Long, Int> =
    items.associate { it.id to clusterOf(it) }
