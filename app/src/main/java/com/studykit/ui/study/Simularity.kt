package com.studykit.ui.study

import com.studykit.data.entity.Question

/**
 * 相似度提供方。id 是各内容类型自己的主键（题库轮里就是 `Question.id`），
 * 提供方**不解释 id 从哪来** —— 这正是把词面实现换成嵌入实现时提供方这一侧唯一要动的地方（计划 D1 留下的缝；
 * 语义包还带来它自己的刻度，那一份在 `InterleavePolicy` 侧另开，不在这条缝上）。
 *
 * 约定（`QuestionSimularity` 与测试替身都要守住）：
 *  - `sim(a, a) == 1f`（自反）；
 *  - `sim(a, b) == sim(b, a)`（对称）；
 *  - 值域 `[0, 1]`；
 *  - 不认识的 id 给 `0f`（"没有依据"当作不像，绝不因为缺数据而阻断交错）。
 *    两条规则的先后：自反先判，所以两侧同一个 id 时给 `1f`，哪怕这个 id 提供方并不认识 ——
 *    「同一条题和自己不像」讲不通，真正需要"没有依据 ⇒ 当它不像"的是**两个不同 id** 之间查不到东西。
 *  - 同一对 id 反复调用必须给**同一个**分值（只由这两个入参决定，与调用次数、调用顺序、外部状态都无关）—— `QueueBuilder.report` 会把每一对相邻读两遍（一遍给 `maxAdjSim`、一遍走 `violates`），那两个数对得上的前提就是这条。
 */
internal fun interface Simularity {
    fun sim(a: Long, b: Long): Float
}

/**
 * 题面相似度的生产实现：构造时按 `Question.id` 把题目收成一份快照，之后只按 id 回答。
 *
 * 为什么要它而不是直接把 `(Long, Long) -> Float` 传来传去：`QueueBuilder` 在一轮修复里会
 * 反复问同一对（换位的窗口扫、插入前查两个邻居、drills 复扫），不记忆化的话重复计算把
 * 500 题那一档的预算直接撑爆（偏差 D5 的整个理由）。两个计数器是给测试钉上界用的，
 * 不是诊断装饰 —— 但口径要说准：Task 8 的查找上界是在它自己的测试替身上数的，公式取的是
 * `LexicalInterleave.DEFAULT` 的 `lookahead` 与 `maxRepairRounds`；[lookups] 在这里是这一层的证据，
 * 说明同一个口径（一次 `sim()` 记一笔）在本类就数得出来。
 * 两个计数器都是非线程安全的可变状态、不提供 reset —— 按「一次同步构建一个队列」的用法设计。
 *
 * 记忆化的粒度是**对**，不是单题的二元组：[pairs] 保证每对不同的题最多真实调用一次
 * [TextSimilarity.questionSimilarity]（正向与反向共用同一条目），此后两侧都是缓存命中。
 * 不再单设「按 id 缓存二元组」的第二层 —— 它对「每对最多算一次」这条预算没有任何加成，
 * 只是多一份状态；一条题只出现在一对里时，它的二元组本来也只算一次。
 * 分值由 [TextSimilarity.questionSimilarity] 单点定义（字面 Dice 加同考点地板），
 * 这里不重抄那套算术，免得两份口径各说一套。
 */
internal class QuestionSimularity(questions: List<Question>) : Simularity {

    private val byId: Map<Long, Question> = questions.associateBy { it.id }
    private val pairs = HashMap<Pair<Long, Long>, Float>(questions.size * 2)

    /** `sim()` 被调用的次数（含缓存命中）。约束算法的工作量口径。 */
    var lookups: Int = 0
        private set

    /** 真正落到 [TextSimilarity] 的次数（缓存未命中）。成对记忆化有效性的口径。 */
    var computations: Int = 0
        private set

    override fun sim(a: Long, b: Long): Float {
        lookups++
        if (a == b) return 1f
        val qa = byId[a] ?: return 0f
        val qb = byId[b] ?: return 0f
        val key = if (a < b) a to b else b to a
        pairs[key]?.let { return it }
        computations++
        val value = TextSimilarity.questionSimilarity(
            stemA = qa.stem, tagA = qa.conceptTag,
            stemB = qb.stem, tagB = qb.conceptTag,
        )
        pairs[key] = value
        return value
    }
}
