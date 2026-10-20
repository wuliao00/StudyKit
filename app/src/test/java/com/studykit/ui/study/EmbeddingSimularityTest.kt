package com.studykit.ui.study

import com.studykit.data.entity.Question
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `EmbeddingSimularity` 的算法闸（v3.1 Task 3）。
 *
 * 夹具用假 embedder 把每个题干映射到一条**手写向量**，所以余弦、夹取、维度判定都是可手推的字面量；
 * 生产侧真模型的分布不在本文件里证明（那是 `BgeEmbedderRobolectricTest` 与后续校准门的事）。
 * 期望值一律写字面量，不读生产常量。
 */
class EmbeddingSimularityTest {

    private fun q(id: Long, stem: String): Question = Question(
        id = id,
        uuid = "embed-$id",
        subject = "数学",
        stem = stem,
        optionsJson = "[]",
        answerIndex = 0,
        explanation = "",
        conceptTag = "",
    )

    /** 记下每个题干被嵌入几次；向量表里没有的题干按 null（= 嵌入失败）处理。 */
    private class CountingEmbedder(
        private val vectors: Map<String, FloatArray?>,
    ) : (String) -> FloatArray? {
        val calls = LinkedHashMap<String, Int>()

        override fun invoke(text: String): FloatArray? {
            calls[text] = (calls[text] ?: 0) + 1
            return vectors[text]
        }
    }

    @Test
    fun `自反相似度为1`() {
        // 契约：sim(a, a) == 1f，先于任何缓存/维度判断（Simularity 的 KDoc 把次序写死）。
        val sim = EmbeddingSimularity(
            listOf(q(1, "甲")),
            CountingEmbedder(mapOf("甲" to floatArrayOf(1f, 0f))),
        )
        assertEquals(1f, sim.sim(1, 1), 0f)
        // 不认识的 id 与自己比也按契约给 1：自反先判，缺数据只影响两个不同 id 之间。
        assertEquals(1f, sim.sim(99, 99), 0f)
    }

    @Test
    fun `相似度对称`() {
        val embedder = CountingEmbedder(
            mapOf(
                "甲" to floatArrayOf(1f, 0f),
                "乙" to floatArrayOf(0.6f, 0.8f),
            ),
        )
        val sim = EmbeddingSimularity(listOf(q(1, "甲"), q(2, "乙")), embedder)
        // cos = 0.6（两侧都是单位向量），正反两个方向必须同一个数。
        assertEquals(0.6f, sim.sim(1, 2), 1e-6f)
        assertEquals(sim.sim(1, 2), sim.sim(2, 1), 0f)
    }

    @Test
    fun `同一题干的两个 id 只嵌入一次`() {
        // 快照按**不同题干**嵌：题干相同的两条题共用一条向量，只付一次前向。
        val embedder = CountingEmbedder(mapOf("同一题干" to floatArrayOf(0.3f, 0.4f)))
        val sim = EmbeddingSimularity(
            listOf(q(1, "同一题干"), q(2, "同一题干")),
            embedder,
        )
        assertEquals(1, embedder.calls["同一题干"])
        assertEquals(0, sim.unresolvedCount)
        assertEquals(1f, sim.sim(1, 2), 1e-6f)
    }

    @Test
    fun `同一对问两次只计算一次余弦`() {
        val embedder = CountingEmbedder(
            mapOf(
                "甲" to floatArrayOf(1f, 0f),
                "乙" to floatArrayOf(0.6f, 0.8f),
            ),
        )
        val sim = EmbeddingSimularity(listOf(q(1, "甲"), q(2, "乙")), embedder)
        sim.sim(1, 2)
        sim.sim(2, 1)
        assertEquals("每次 sim() 都记一笔查找: ${sim.lookups}", 2, sim.lookups)
        assertEquals("正反共用同一条缓存，只应真实算一次", 1, sim.computations)
    }

    @Test
    fun `未知 id 给 0 且不触发计算`() {
        val embedder = CountingEmbedder(mapOf("甲" to floatArrayOf(1f, 0f)))
        val sim = EmbeddingSimularity(listOf(q(1, "甲")), embedder)
        assertEquals(0f, sim.sim(1, 99), 0f)
        assertEquals(0f, sim.sim(99, 1), 0f)
        assertEquals(2, sim.lookups)
        assertEquals(0, sim.computations)
    }

    @Test
    fun `null 向量整题 unresolved 且相似度为 0`() {
        val embedder = CountingEmbedder(
            mapOf(
                "甲" to floatArrayOf(1f, 0f),
                "乙" to null,
            ),
        )
        val sim = EmbeddingSimularity(listOf(q(1, "甲"), q(2, "乙")), embedder)
        assertEquals(1, sim.unresolvedCount)
        assertEquals(0f, sim.sim(1, 2), 0f)
        assertEquals(0f, sim.sim(2, 1), 0f)
        assertEquals("不完整的两条题不该付一次余弦", 0, sim.computations)
    }

    @Test
    fun `维度不一致的题 unresolved 且相似度为 0`() {
        // 首个成功向量是「甲」的二维；「乙」给三维 ⇒ 乙整题 unresolved，但它不妨碍「丙」按二维算。
        val embedder = CountingEmbedder(
            mapOf(
                "甲" to floatArrayOf(1f, 0f),
                "乙" to floatArrayOf(1f, 0f, 0f),
                "丙" to floatArrayOf(0f, 1f),
            ),
        )
        val sim = EmbeddingSimularity(
            listOf(q(1, "甲"), q(2, "乙"), q(3, "丙")),
            embedder,
        )
        assertEquals(1, sim.unresolvedCount)
        assertEquals(0f, sim.sim(1, 2), 0f)
        assertEquals(0f, sim.sim(2, 3), 0f)
        assertEquals(0f, sim.sim(1, 3), 1e-6f)
    }

    @Test
    fun `负余弦夹到 0`() {
        val embedder = CountingEmbedder(
            mapOf(
                "甲" to floatArrayOf(1f, 0f),
                "乙" to floatArrayOf(-1f, 0f),
            ),
        )
        val sim = EmbeddingSimularity(listOf(q(1, "甲"), q(2, "乙")), embedder)
        val value = sim.sim(1, 2)
        assertEquals("余弦 -1 必须夹成 0 而不是原样透传", 0f, value, 0f)
        assertEquals(1, sim.computations)
    }

    @Test
    fun `lookups 与 computations 按次记账`() {
        val embedder = CountingEmbedder(
            mapOf(
                "甲" to floatArrayOf(1f, 0f),
                "乙" to floatArrayOf(0.6f, 0.8f),
                "丙" to floatArrayOf(1f, 1f),
            ),
        )
        val sim = EmbeddingSimularity(
            listOf(q(1, "甲"), q(2, "乙"), q(3, "丙")),
            embedder,
        )
        sim.sim(1, 2)   // lookup 1, compute 1
        sim.sim(2, 1)   // lookup 2, cache hit
        sim.sim(1, 3)   // lookup 3, compute 2
        sim.sim(1, 1)   // lookup 4, 自反早退
        sim.sim(42, 1)  // lookup 5, 未知 id 早退
        assertEquals(5, sim.lookups)
        assertEquals(2, sim.computations)
        // 三个不同题干各只嵌一次（构造时 eager）。
        assertEquals(1, embedder.calls["甲"])
        assertEquals(1, embedder.calls["乙"])
        assertEquals(1, embedder.calls["丙"])
    }
}
