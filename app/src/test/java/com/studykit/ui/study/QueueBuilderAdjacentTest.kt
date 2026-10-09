package com.studykit.ui.study

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `QueueBuilder` 第一遍（相邻修复）的约束算法用例（v3.0-C Task 3）。
 *
 * `policy` 一律用 [LexicalInterleave.DEFAULT]（tauHi=0.60 / tauLo=0.15 / lookahead=12 /
 * maxRepairRounds=4），簇型替身 `FixtureSim` 只给 0.9/0.0 两个值，于是"违规"与"可换"没有灰区。
 * 期望值全部手推后实跑核对，⛔ 标记的那几条钉字面量、不读生产常量。
 */
class QueueBuilderAdjacentTest {

    private val p = LexicalInterleave.DEFAULT

    /**
     * 三簇各 3 题，但**排成可修的违规**（文档 L1265「三簇近重复卡合成队列」那条）。
     *
     * 原稿这里写的是 `A A A B B B C C C`，派发前手推否掉了：第一遍只往后找换位候选，
     * 队尾那一段（位置 6/7/8 的 C C C）后面没有任何异簇项可换，只能靠插缓冲卡，
     * 而拉前上限是 `n×10%` ⇒ 9 题的会话上限就是 **0**。现在这个顺序（A A B B C C A B C）
     * 每一处违规后面都有异簇项可换。队尾连续同簇那一类**没有消失**，
     * 它被单独钉成「不足十题的会话一张缓冲都拉不进来」和「不可满足时如实上报」两条。
     */
    private fun threeClusters(): List<QueueItem> = listOf(
        QueueItem(1, 9f, "A"), QueueItem(2, 8f, "A"), QueueItem(3, 7f, "B"),
        QueueItem(4, 6f, "B"), QueueItem(5, 5f, "C"), QueueItem(6, 4f, "C"),
        QueueItem(7, 3f, "A"), QueueItem(8, 2f, "B"), QueueItem(9, 1f, "C"),
    )

    /** 违规集中在**队尾**的一类：第一遍往后找不到东西可换、9 题的拉前上限又是 0 ⇒ 只能如实上报 */
    private fun tailHeavy(): List<QueueItem> = listOf(
        QueueItem(1, 9f, "A"), QueueItem(2, 8f, "B"), QueueItem(3, 7f, "C"),
        QueueItem(4, 6f, "A"), QueueItem(5, 5f, "B"), QueueItem(6, 4f, "C"),
        QueueItem(7, 3f, "A"), QueueItem(8, 2f, "A"), QueueItem(9, 1f, "A"),
    )

    private fun simOf(items: List<QueueItem>): FixtureSim =
        FixtureSim(items.associate { it.id to it.subject[0].code.toInt() })

    /**
     * 「两侧都查」专用的是**非传递**的按对打分器：1 与 2、3..10 全都像（所以窗口内没有可换的），
     * 但 3..10 之间两两不像，池卡 90 只跟指定的那一侧像。
     *
     * 为什么不能用 [FixtureSim]：簇是传递的，"与右邻像"的那张卡必然"与左邻也像"（两邻同簇），
     * 于是它连单侧检查都过不了 —— 「只查一侧」这个变异在簇型替身下**永不现形**，
     * 只能换成手写按对分值。`similarTo` 给的是池卡唯一相似的那一个队列 id。
     */
    private fun nonTransitiveSim(similarTo: Long): Simularity = Simularity { a, b ->
        when {
            a == b -> 1f
            (a == 1L && b in 2L..10L) || (b == 1L && a in 2L..10L) -> 0.90f
            (a == 90L && b == similarTo) || (b == 90L && a == similarTo) -> 0.90f
            else -> 0f
        }
    }

    /** 位置 0/1 违规（0.90f > tauHi），窗口里唯一候选 3 与左邻的分值由 `candidate` 给定 */
    private fun tauLoCandidateSim(candidate: Float): Simularity = Simularity { a, b ->
        when {
            a == b -> 1f
            (a == 1L && b == 2L) || (b == 1L && a == 2L) -> 0.90f
            (a == 1L && b == 3L) || (b == 1L && a == 3L) -> candidate
            else -> 0f
        }
    }

    @Test fun `无违规时输出就是 urgency 降序原样`() {
        val crisp = listOf(QueueItem(1, 3f, "A"), QueueItem(2, 2f, "B"), QueueItem(3, 1f, "C"))
        val plan = QueueBuilder.build(crisp, simOf(crisp), emptyList(), emptyMap(), p)
        assertEquals(listOf(1L, 2L, 3L), plan.queue.map { it.id })
        assertEquals(0, plan.pulled)
        assertTrue(plan.unresolved.isEmpty())
    }

    @Test fun `urgency 相同按 id 升序定序不吃集合迭代序`() {
        val tied = listOf(QueueItem(7, 5f, "A"), QueueItem(3, 5f, "B"), QueueItem(9, 5f, "C"), QueueItem(1, 5f, "D"))
        val plan = QueueBuilder.build(tied, simOf(tied), emptyList(), emptyMap(), p)
        assertEquals(listOf(1L, 3L, 7L, 9L), plan.queue.map { it.id })
    }

    @Test fun `恰好等于 tauHi 的相邻不算违规而高出一点就算`() {
        // ⛔ 比较方向：文档 L1243 写的是 `sim(...) > TAU_HI`，等于不算违规。
        // Task 2 原有一条测试自称钉住了这件事，但它只读 policy 自己的字段，任何实现改动都红不了，
        // 评审后已删（T2 修复轮 1）。真正钉住它的是下面这两条：分值恰好压在 0.60f 上，与高出 0.01f。
        // 期望值全部写字面量，不读生产常量。
        val items = listOf(QueueItem(1, 2f, "A"), QueueItem(2, 1f, "A"))
        val onBoundary = Simularity { a, b -> if (a == b) 1f else 0.60f }
        val plan = QueueBuilder.build(items, onBoundary, emptyList(), emptyMap(), p)
        assertTrue("等于 tauHi 被判成违规: ${plan.unresolved}", plan.unresolved.isEmpty())
        assertEquals(0.60f, plan.maxAdjSim!!, 1e-6f)
        val justOver = Simularity { a, b -> if (a == b) 1f else 0.61f }
        val over = QueueBuilder.build(items, justOver, emptyList(), emptyMap(), p)
        assertEquals(listOf(0), over.unresolved)
    }

    @Test fun `恰好等于 tauLo 的候选不算可换而低于一点就算`() {
        // ⛔ 另一侧的比较方向：换位候选要 `sim < tauLo`，**严格**小于才合格（文档 L1244）。
        // 这条关掉变异扫描留下的「tauLo 的严格方向没人钉」缺口：把 `<` 写成 `<=`，
        // 前半段（分值恰好 0.15f）就会换位成功，末序从 [1,2,3] 变成 [1,3,2] ⇒ 当场红。
        // 期望值全部写字面量，不读 policy.tauLo。
        val items = listOf(QueueItem(1, 3f, "A"), QueueItem(2, 2f, "A"), QueueItem(3, 1f, "A"))
        val onBoundary = QueueBuilder.build(items, tauLoCandidateSim(0.15f), emptyList(), emptyMap(), p)
        assertEquals(listOf(1L, 2L, 3L), onBoundary.queue.map { it.id })
        assertEquals(listOf(0), onBoundary.unresolved)             // 位置 1/2 那对不像，只有 0/1 违规
        val justUnder = QueueBuilder.build(items, tauLoCandidateSim(0.14f), emptyList(), emptyMap(), p)
        assertEquals(listOf(1L, 3L, 2L), justUnder.queue.map { it.id })
        assertTrue("低于 tauLo 的候选没被换上: ${justUnder.unresolved}", justUnder.unresolved.isEmpty())
    }

    @Test fun `三簇近重复修完没有相邻违规`() {
        val items = threeClusters()
        val plan = QueueBuilder.build(items, simOf(items), emptyList(), emptyMap(), p)
        assertTrue("仍有未解决: ${plan.unresolved}", plan.unresolved.isEmpty())
        val adj = plan.queue.zipWithNext().maxOfOrNull { (a, b) -> simOf(items).sim(a.id, b.id) } ?: 0f
        assertTrue("maxAdjSim=$adj", adj <= p.tauHi)
        assertEquals(adj, plan.maxAdjSim!!, 1e-6f)
    }

    @Test fun `修完是不增不减不重的排列`() {
        val items = threeClusters()
        val plan = QueueBuilder.build(items, simOf(items), emptyList(), emptyMap(), p)
        assertEquals(items.size, plan.queue.size)
        assertEquals(items.map { it.id }.sorted(), plan.queue.map { it.id }.sorted())
        assertEquals(items.toSet(), plan.queue.toSet())
    }

    @Test fun `优先窗口内换位而不是拉缓冲卡`() {
        // A A B：位置 0/1 违规，位置 2 是异簇 ⇒ 应该把 2 换到 1，pulled 必须留在 0
        val items = listOf(QueueItem(1, 3f, "A"), QueueItem(2, 2f, "A"), QueueItem(3, 1f, "B"))
        val pool = listOf(QueueItem(90, 0f, "Z"))
        val plan = QueueBuilder.build(items, simOf(items), pool, emptyMap(), p)
        assertEquals(0, plan.pulled)
        assertEquals(listOf(1L, 3L, 2L), plan.queue.map { it.id })
        assertTrue(plan.unresolved.isEmpty())
    }

    @Test fun `窗口内没有可换的才从池里插一张缓冲`() {
        // 10 条全同簇：位置 0 后面全是同簇（窗口内无可换），只能插池卡。
        // **必须是 10 条**：拉前上限是 `n×10/100`，4 条的会话上限是 0，那样这条测试会
        // 因为"上限挡着"而通过，跟"窗口里没有可换的"这件事完全无关（派发前手推发现的）。
        val items = (1L..10L).map { QueueItem(it, (11 - it).toFloat(), "A") }
        val poolItem = QueueItem(90, 0f, "Z")
        val sim = FixtureSim(items.associate { it.id to 1 } + (poolItem.id to 9))
        val plan = QueueBuilder.build(items, sim, listOf(poolItem), emptyMap(), p)
        assertEquals(1, plan.pulled)
        assertTrue(plan.queue.map { it.id }.contains(90L))
        assertEquals(11, plan.queue.size)
    }

    @Test fun `拉前条数受上限约束等于条数的一成`() {
        // 10 条全同簇 ⇒ 上限 10/10 = 1 张缓冲卡，哪怕池里有 9 张可用
        val items = (1L..10L).map { QueueItem(it, (11 - it).toFloat(), "A") }
        val pool = (90L..98L).map { QueueItem(it, 0f, "Z$it") }
        val sim = FixtureSim(items.associate { it.id to 1 } + pool.associate { it.id to 2 })
        val plan = QueueBuilder.build(items, sim, pool, emptyMap(), p)
        assertEquals(1, plan.pulled)                     // ⛔ 文档 L1264 的 10% 上限，字面量钉死
        assertEquals(11, plan.queue.size)
        assertTrue(plan.unresolved.isNotEmpty())          // 只拉一张不可能修干净：必须如实上报
    }

    @Test fun `不足十题的会话一张缓冲都拉不进来`() {
        // ⛔ 上限的整数除法后果：9 题 ⇒ 9*10/100 = 0。这条把它钉成行为而不是留作意外。
        // 它是上一节「队尾连续同簇修不动」的根本原因：那种位置只能插缓冲卡，而小会话插不了。
        val items = tailHeavy()
        val pool = (90L..95L).map { QueueItem(it, 0f, "Z$it") }
        val sim = FixtureSim(items.associate { it.id to (it.subject[0].code) } + pool.associate { it.id to 'Z'.code })
        val plan = QueueBuilder.build(items, sim, pool, emptyMap(), p)
        assertEquals(0, plan.pulled)
        assertEquals(9, plan.queue.size)
        // 有东西可修、但 cap 拦着不让拉 ⇒ 只能进 unresolved。不 assert 这一句的话，
        // "pulled == 0" 在一个本来就没有违规的夹具上也会通过，那就什么都没钉住。
        assertEquals(listOf(6, 7), plan.unresolved)
    }

    @Test fun `池卡与右邻相似时不许插`() {
        // 变异「插入只查左邻」的杀手：违规对 1X/2X 的**右邻** 2 与池卡 90 像、左邻 1 不像。
        // 只查左侧的实现会接受 90 并把它插到下标 1 ⇒ pulled 变 1、队列变 11 条 ⇒ 这条红。
        // 必须 ≥10 条：拉前上限 = 10*10/100 = 1，于是"没插"只能归因于邻居检查而不是上限（上限此时是开着的）。
        val items = (1L..10L).map { QueueItem(it, (11 - it).toFloat(), "A") }
        val plan = QueueBuilder.build(items, nonTransitiveSim(similarTo = 2L), listOf(QueueItem(90, 0f, "Z")), emptyMap(), p)
        assertEquals(0, plan.pulled)
        assertEquals(10, plan.queue.size)
        assertEquals(listOf(0), plan.unresolved)              // 违规还在原地，没被挪成两个
    }

    @Test fun `池卡与左邻相似时不许插`() {
        // 上一条的镜像，变异「插入只查右邻」的杀手：池卡 90 与**左邻** 1 像、与右邻 2 不像。
        // 与 [p] 的簇型替身区分不开的那件事，在这里靠非传递的按对分值分开：
        // 簇是传递的，两邻同簇 ⇒ 像一侧就必然像两侧，「只查一侧」在簇型替身下永不现形。
        val items = (1L..10L).map { QueueItem(it, (11 - it).toFloat(), "A") }
        val plan = QueueBuilder.build(items, nonTransitiveSim(similarTo = 1L), listOf(QueueItem(90, 0f, "Z")), emptyMap(), p)
        assertEquals(0, plan.pulled)
        assertEquals(10, plan.queue.size)
        assertEquals(listOf(0), plan.unresolved)
    }

    @Test fun `池里已经在队列的 id 不许重复拉入`() {
        // 变异「删掉 `it.id !in inQueue`」的杀手。夹具要点（16 条）：
        //  - 违规在 i=0，窗口 [2,13] 全是簇 1 ⇒ 换不动，只能插；cap = 16*10/100 = 1 ⇒ 上限是开着的；
        //  - 池卡与队列**同 id**（15），所以在 FixtureSim 里属于簇 2（异簇）⇒ 两侧邻居检查都过；
        //  - 于是拦下它的只有去重那一条。简报原来的 10 条夹具做不到：那张池卡与队列同簇，
        //    相似度先把它拒了，删掉去重检查这条测试照样绿（白绿，T3 修复轮 1 的取证）。
        // 实跑末序（去重生效）：[1, 15, 2, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 3, 16]，
        // 下标 1 的那个 15 是**队列里本来那条**（第 2 轮被 i=0 的窗口捞回来的），不是插入。
        // 把 `it.id !in inQueue &&` 删掉再跑同一份夹具：`pulled` 从 0 变成 1（`expected:<0> but was:<1>`）
        // —— 那张同 id 的卡真的被插进队列了，第一个断言当场红。
        val items = (1L..16L).map { QueueItem(it, (17 - it).toFloat(), if (it == 15L) "B" else "A") }
        val sim = FixtureSim(items.associate { it.id to (if (it.id == 15L) 2 else 1) })
        val plan = QueueBuilder.build(items, sim, listOf(QueueItem(15, 0.1f, "Z")), emptyMap(), p)
        assertEquals(0, plan.pulled)
        assertEquals(16, plan.queue.size)
        assertEquals(16, plan.queue.map { it.id }.distinct().size)
    }

    @Test fun `不可满足时如实上报而不是假装干净`() {
        // 3 条同簇、无池、窗口内无异簇：任何换位都还在同簇内 ⇒ 修不好
        val items = listOf(QueueItem(1, 3f, "A"), QueueItem(2, 2f, "A"), QueueItem(3, 1f, "A"))
        val plan = QueueBuilder.build(items, simOf(items), emptyList(), emptyMap(), p)
        assertTrue(plan.unresolved.isNotEmpty())
        assertTrue(plan.maxAdjSim!! > p.tauHi)
        assertEquals(0, plan.pulled)
        assertEquals(3, plan.queue.size)                  // 上报不等于顺手丢条目
    }

    @Test fun `空队列和单条队列不抛并且 maxAdjSim 为空`() {
        val none = QueueBuilder.build(emptyList(), FixtureSim(emptyMap()), emptyList(), emptyMap(), p)
        assertNull(none.maxAdjSim)                        // 偏差 D6 第 3 条：文档的 maxOf 在这里抛 NoSuchElementException
        assertTrue(none.unresolved.isEmpty())
        val one = QueueBuilder.build(listOf(QueueItem(1, 1f, "A")), FixtureSim(mapOf(1L to 1)), emptyList(), emptyMap(), p)
        assertNull(one.maxAdjSim)
        assertEquals(1, one.queue.size)
    }

    @Test fun `同一输入两次构建结果逐元素相等`() {
        val items = threeClusters() + listOf(QueueItem(10, 0.5f, "A"), QueueItem(11, 0.4f, "A"))
        val first = QueueBuilder.build(items, simOf(items), emptyList(), emptyMap(), p)
        val second = QueueBuilder.build(items, simOf(items), emptyList(), emptyMap(), p)
        assertEquals(first.queue.map { it.id }, second.queue.map { it.id })
        assertEquals(first.pulled, second.pulled)
    }

    @Test fun `换位只往后看 lookahead 个位置`() {
        // 偏差 D5 的可执行变异测试：同一份夹具跑两遍，有界窗口和放开窗口必须给出**不同**末态。
        //
        // 先钉简报原先的 22 条夹具（前 21 条同簇 + 队尾一条异簇，urgency 递减）：
        // 实跑观测 —— 有界（lookahead=12）与无界（lookahead=1000）都把 id 22 落在**下标 1**，末态一模一样。
        // 4*12=48 格的搬运额度用不完 21 格的距离，于是四轮之内链条总能走完（每轮把队尾那张搬进 i=0 的窗口），
        // 只看 queue 的断言在这条夹具上写不成变异测试 —— 这就是简报那条原先白绿的原因，留在这里免得有人把
        // 下面的 60 条"简化"回去。
        val near = (1L..22L).map { QueueItem(it, (23 - it).toFloat(), if (it <= 21L) "A" else "B") }
        val nearSim = FixtureSim(near.associate { it.id to (if (it.id <= 21L) 1 else 2) })
        val nearBounded = QueueBuilder.build(near, nearSim, emptyList(), emptyMap(), p)
        assertEquals(0, nearBounded.pulled)               // 池是空的，任何一格修复都只能靠换位
        assertEquals(1, nearBounded.queue.indexOfFirst { it.id == 22L })
        val nearUnbounded = QueueBuilder.build(near, nearSim, emptyList(), emptyMap(), p.copy(lookahead = 1000))
        assertEquals(1, nearUnbounded.queue.indexOfFirst { it.id == 22L })
        // 拉长到搬运额度用不完的那一档：60 条 = 前 59 条同簇 + 队尾一条异簇，urgency 递减 ⇒ 初序就是 id 升序。
        // 单次换位的位移上限就是 lookahead（窗口右端 `i+1+lookahead`），而一张卡**往队首方向**每轮最多被搬一次，
        // 于是四轮（maxRepairRounds=4）总共最多往前搬 4*12=48 格 ⇒ 手推落在 59-48=11，实跑观测同为 11。
        // 窗口换成 1000 时第一轮 i=0 就够得到它 ⇒ 直接落到下标 1，严格更靠前 ⇒ 「窗口不设界」这个变异在这里红
        // （实跑：不设界时 expected:<11> but was:<1>）。
        val items = (1L..60L).map { QueueItem(it, (61 - it).toFloat(), if (it <= 59L) "A" else "B") }
        val sim = FixtureSim(items.associate { it.id to (if (it.id <= 59L) 1 else 2) })
        val bounded = QueueBuilder.build(items, sim, emptyList(), emptyMap(), p)
        assertEquals(0, bounded.pulled)
        assertEquals(11, bounded.queue.indexOfFirst { it.id == 60L })
        val unbounded = QueueBuilder.build(items, sim, emptyList(), emptyMap(), p.copy(lookahead = 1000))
        val farthest = unbounded.queue.indexOfFirst { it.id == 60L }
        assertTrue("无界窗口把队尾那条搬到了 $farthest，不比有界的 11 更靠前", farthest < 11)
    }
}
