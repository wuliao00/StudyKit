package com.studykit.ui.study

import com.studykit.data.entity.Question
import kotlin.math.sqrt

/**
 * 嵌入提供方的 [Simularity] 实现（v3.1 Task 3）。
 *
 * 构造时对 `base + extra` 里**每个不同题干**各嵌入一次（eager），存 `id -> FloatArray`；
 * 之后只按 id 回答。与 [QuestionSimularity] 一样，**不解释 id 从哪来**。
 *
 * 不可用的判定收在 [unresolvedCount] 这**一处**，由装配层 [planQuestionQueue] 决定回退词面：
 *  - 嵌入返回 null（模型/资产加载失败，或单条文本嵌入失败）；
 *  - 向量维度与**首个成功向量**不一致。
 * 只有 `unresolvedCount == 0` 时才允许整轮走嵌入刻度；有任何一条没嵌入成功就整轮回退，
 * 免得同一轮里一半题按余弦、一半题按 Dice 排在同一个队列里。
 *
 * 分值口径：
 *  - 自反 `sim(a, a) == 1f`（先于任何缓存/维度判断，同 [Simularity] 契约）；
 *  - 未知 id、任一方 unresolved ⇒ `0f`；
 *  - 否则余弦，并**夹到 `[0, 1]`**：负值记 0（"方向相反"在交错语境里就当"不像"，
 *    不能让它比 0 更小去影响 `violates` / 落点判定）；全零向量没有方向，也给 `0f`。
 *
 * 缓存与计数口径照 [QuestionSimularity]：记忆化的粒度是**对**，正反共用一条；[lookups] 每次 `sim()` +1，
 * [computations] 每次真实余弦 +1。两个计数器都是非线程安全的可变状态、不提供 reset ——
 * 按「一次同步构建一个队列」的用法设计（组轮入口把它包在 `Dispatchers.Default` 里）。
 */
internal class EmbeddingSimularity(
    questions: List<Question>,
    embed: (String) -> FloatArray?,
) : Simularity {

    private val vectors: Map<Long, FloatArray?>
    private val pairs = HashMap<Pair<Long, Long>, Float>(questions.size * 2)

    /** `sim()` 被调用的次数（含缓存命中与早退）。口径与 [QuestionSimularity.lookups] 相同。 */
    var lookups: Int = 0
        private set

    /** 真正算了一次余弦的次数（缓存未命中、且两侧都有可用向量）。 */
    var computations: Int = 0
        private set

    /** 嵌入失败或维度不一致的**题数**。0 表示整轮都可以走嵌入刻度。 */
    val unresolvedCount: Int

    init {
        // 先按题干去重，保证同一题干只付一次前向。
        val idsByStem = LinkedHashMap<String, MutableList<Long>>()
        for (question in questions) {
            idsByStem.getOrPut(question.stem) { mutableListOf() } += question.id
        }
        val resolved = HashMap<Long, FloatArray>(questions.size)
        val unresolvedIds = HashSet<Long>()
        var dimension = -1
        for ((stem, ids) in idsByStem) {
            val vector = embed(stem)
            if (vector == null) {
                unresolvedIds += ids
                continue
            }
            if (dimension < 0) dimension = vector.size
            if (vector.size != dimension) {
                unresolvedIds += ids
                continue
            }
            for (id in ids) resolved[id] = vector
        }
        val byId = HashMap<Long, FloatArray?>(questions.size)
        for (question in questions) byId[question.id] = resolved[question.id]
        vectors = byId
        unresolvedCount = questions.count { byId[it.id] == null }
    }

    override fun sim(a: Long, b: Long): Float {
        lookups++
        if (a == b) return 1f
        val va = vectors[a] ?: return 0f
        val vb = vectors[b] ?: return 0f
        val key = if (a < b) a to b else b to a
        pairs[key]?.let { return it }
        computations++
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in va.indices) {
            val x = va[i].toDouble()
            val y = vb[i].toDouble()
            dot += x * y
            normA += x * x
            normB += y * y
        }
        val denominator = sqrt(normA) * sqrt(normB)
        val cosine = if (denominator == 0.0) 0f else (dot / denominator).toFloat()
        val value = cosine.coerceIn(0f, 1f)
        pairs[key] = value
        return value
    }
}
