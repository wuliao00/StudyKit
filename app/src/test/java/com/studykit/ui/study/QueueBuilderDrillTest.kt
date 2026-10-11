package com.studykit.ui.study

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `QueueBuilder` 第二遍（混淆对 drills）与挪完复扫的用例（v3.0-C Task 4）。
 *
 * 这一份**显式带 subject**（文档 L1227「仅同学科内」判定要看它），所以不用 `QueueFixtures` 那套把 subject
 * 当簇标记的写法：分值由 [ScoredSim] 按对直接给，簇结构给不出「同科但不像」「像但跨科」这种组合。
 *
 * 每条命名了意图的用例都在注释里写明它杀哪个变异（改法与被改的那一行），不是"看着像在测"。
 * 期望值全部手推后实跑核对；⛔ 标记的那几条钉字面量、不读生产常量。
 */
class QueueBuilderDrillTest {

    private val p = LexicalInterleave.DEFAULT

    /**
     * 按对给分的提供方：[score] 收到一对 id 返回分值。同一对只真实计算一次（正向反向共用条目），
     * 与 [QuestionSimularity] 同一口径 —— `report` 会把每一对相邻读两遍（一遍给 `maxAdjSim`、一遍走 `violates`），
     * 那两个数对得上号的前提就是"同一对永远给同一个数"。
     */
    private class ScoredSim(private val score: (Long, Long) -> Float) : Simularity {
        private val memo = HashMap<Pair<Long, Long>, Float>()
        override fun sim(a: Long, b: Long): Float {
            if (a == b) return 1f
            val key = if (a < b) a to b else b to a
            memo[key]?.let { return it }
            return score(a, b).also { memo[key] = it }
        }
    }

    /** 列表位置就是初序位置：urgency 递派发出去，`build` 的排序键 `(urgency desc, id asc)` 还原成同一顺序 */
    private fun drillItems(spec: List<Pair<Long, String>>) =
        spec.mapIndexed { idx, (id, subject) -> QueueItem(id, (spec.size - idx).toFloat(), subject) }

    /** 全部同科 "A"：`confusablePairs` 的同学科过滤在这里不构成障碍 */
    private fun sameSubject(ids: List<Long>) = drillItems(ids.map { it to "A" })

    /** 只有列出来的对子有分值，其余一律 0f（低于 tauLo ⇒ 相邻修复不会插手） */
    private fun bandSim(vararg pairs: Triple<Long, Long, Float>) = ScoredSim { a, b ->
        pairs.firstOrNull { (it.first == a && it.second == b) || (it.first == b && it.second == a) }?.third ?: 0f
    }

    /** 插入序可控的映射：用来喂"内容相同、迭代序相反"的两份输入 */
    private fun linkedOf(vararg entries: Pair<Long, List<Long>>): Map<Long, List<Long>> {
        val m = java.util.LinkedHashMap<Long, List<Long>>()
        for (e in entries) m[e.first] = e.second
        return m
    }

    private fun idxOf(plan: QueuePlan, id: Long) = plan.queue.indexOfFirst { it.id == id }

    @Test fun `混淆对挨着出时被挪到距 anchor 三题`() {
        // 初序 [1,2,3,4,5]：1 与 2 挨着（gap 1 < gapLo 3）⇒ 把 2 挪到 anchor 往后第三题。
        // 杀的变异：早退条件 `gap in gapLo..gapHi` 松成 `gap <= gapHi`（丢掉下界）——
        // 那样"太近"这一半就不再挪，末序停在 [1,2,3,4,5]、gap 报 1。
        val items = drillItems(listOf(1L to "A", 2L to "A", 3L to "B", 4L to "C", 5L to "D"))
        val sim = bandSim(Triple(1L, 2L, 0.5f))          // 在混淆带 [0.40,0.75] 内，且 <= tauHi ⇒ 第一遍不管它
        val plan = QueueBuilder.build(items, sim, emptyList(), mapOf(1L to listOf(2L)), p)
        assertEquals(listOf(1L, 3L, 4L, 2L, 5L), plan.queue.map { it.id })
        assertEquals(3, kotlin.math.abs(idxOf(plan, 1L) - idxOf(plan, 2L)))
        assertEquals(listOf(1L, 2L), plan.drillPairs.map { listOf(it.aId, it.bId) }.first())
        assertEquals(3, plan.drillPairs.first().gap)
        assertTrue(plan.unresolved.isEmpty())
    }

    @Test fun `混淆对离得太远时被拉回三题处`() {
        // 初序 [1..7]：1 与 7 隔 6 题（> gapHi 5）⇒ 往回拉到 anchor 往后第三题。
        // 杀的变异：早退条件松成 `gap >= gapLo`（丢掉上界）—— 那样"太远"这一半不再拉回，
        // 7 停在末位、距离停在 6 ⇒ 前两句都红。
        val items = drillItems(
            listOf(1L to "A", 2L to "B", 3L to "C", 4L to "D", 5L to "E", 6L to "F", 7L to "A"),
        )
        val sim = bandSim(Triple(1L, 7L, 0.5f))
        val plan = QueueBuilder.build(items, sim, emptyList(), mapOf(1L to listOf(7L)), p)
        assertEquals(3, kotlin.math.abs(idxOf(plan, 1L) - idxOf(plan, 7L)))
        assertEquals(listOf(1L, 2L, 3L, 7L, 4L, 5L, 6L), plan.queue.map { it.id })
        assertEquals(7, plan.queue.size)                 // 挪位不增不减
    }

    @Test fun `间隔已在带内就不动`() {
        // 初序 [1..5]：1 与 5 的 gap 正好 4 ∈ [gapLo 3, gapHi 5] ⇒ 第二遍一步都不该走。
        // 杀的变异：删掉那句早退（`gap in gapLo..gapHi` 整条不判、每对都挪）——
        // 那样 5 会被插到下标 3，after 变成 [1,2,3,5,4]，与 before 不等。
        val items = drillItems(listOf(1L to "A", 2L to "B", 3L to "C", 4L to "D", 5L to "A"))
        val sim = bandSim(Triple(1L, 5L, 0.5f))
        val before = QueueBuilder.build(items, sim, emptyList(), emptyMap(), p)
        val after = QueueBuilder.build(items, sim, emptyList(), mapOf(1L to listOf(5L)), p)
        assertEquals(before.queue.map { it.id }, after.queue.map { it.id })
        assertTrue(before.drillPairs.isEmpty())          // 空映射一条 drill 都不该记
        assertEquals(1, after.drillPairs.size)           // 不动也要记：调用方要看得见"这一场有成对出现"
        assertEquals(4, after.drillPairs.first().gap)
    }

    @Test fun `先删后算下标：伙伴在前面时不偏移一位`() {
        // ⛔ 钉偏差 D6 第 1 条。12 条、初序手工排成 4 在下标 5、10 在下标 3（gap 2 < gapLo ⇒ 一定挪），
        // 且 aId(4) < bId(10) 满足规范化方向。删掉伙伴（下标 3）之后 anchor 左移一格：
        // 正确 anchor = 5-1 = 4 ⇒ target 7 ⇒ 末序 [1,2,3,5,4,6,7,10,8,9,11,12]、gap 3；
        // 沿用旧下标（变异：`if (ib < ia) ia - 1 else ia` 改成 `ia`）⇒ target 8 ⇒ 10 落在 8、距离变 4。
        val items = sameSubject(listOf(1L, 2L, 3L, 10L, 5L, 4L, 6L, 7L, 8L, 9L, 11L, 12L))
        val sim = bandSim(Triple(4L, 10L, 0.5f))
        val plan = QueueBuilder.build(items, sim, emptyList(), mapOf(4L to listOf(10L)), p)
        assertEquals(listOf(1L, 2L, 3L, 5L, 4L, 6L, 7L, 10L, 8L, 9L, 11L, 12L), plan.queue.map { it.id })
        assertEquals(3, kotlin.math.abs(idxOf(plan, 4L) - idxOf(plan, 10L)))
        assertEquals(3, plan.drillPairs.first().gap)
        assertEquals(12, plan.queue.size)
    }

    @Test fun `挪完造出的新违规被复扫修掉`() {
        // ⛔ 钉偏差 D6 第 2 条：drills 挪完不管新邻居。形状是**删**出来的：8 原本把 7 与 9 隔开
        // （7 在下标 6、8 在 7、9 在 8），把 8 搬走后 7 与 9（sim 0.9 > tauHi）就贴上了。
        // 这一对初序不相邻 ⇒ 第一遍没看见它，只有 drills 之后的复扫会撞上它；
        // 杀的变异：删掉 build 里 `if (relocated > 0)` 里那句复扫调用。
        // 删掉后 7 与 9 就那么贴着留在最终队列里：实跑红在第一句（末序停在 [..., 7, 9, 10, 11, 12]），
        // 下一句 `unresolved` 说的也是同一对（下标 7），JUnit 只报第一个失败。
        val items = sameSubject((1L..12L).toList())
        val sim = ScoredSim { a, b ->
            when {
                setOf(a, b) == setOf(1L, 8L) -> 0.5f      // 混淆对：gap 7 > gapHi ⇒ 真的会挪
                setOf(a, b) == setOf(7L, 9L) -> 0.9f      // 挪完才贴上的一对
                else -> 0f
            }
        }
        val plan = QueueBuilder.build(items, sim, emptyList(), mapOf(1L to listOf(8L)), p)
        assertEquals(listOf(1L, 2L, 3L, 8L, 4L, 5L, 6L, 7L, 10L, 9L, 11L, 12L), plan.queue.map { it.id })
        assertTrue("未解决: ${plan.unresolved}", plan.unresolved.isEmpty())
        val maxAdj = plan.queue.zipWithNext().maxOfOrNull { sim.sim(it.first.id, it.second.id) }!!
        assertTrue("maxAdjSim=$maxAdj", maxAdj <= 0.60f)   // ⛔ 字面量 = 现在 DEFAULT.tauHi 的值，不读常量
        assertEquals(maxAdj, plan.maxAdjSim!!, 1e-6f)
        assertEquals(12, plan.queue.size)
    }

    @Test fun `挪完又被复扫换开的对子报最终距离`() {
        // drills 把 3 从下标 2 挪到 anchor 后三题（下标 3），那一挪把 2 与 4（sim 0.9）挤成相邻；
        // 复扫为了拆掉它把 3 又换回下标 2 ⇒ 最终队列与初序逐格相同。
        // 这一条钉的是 [QueueDrill.gap] 的口径：报的是**最终**距离 2，不是"挪完那一刻"的 3。
        // 杀的变异：build 里把 `stampGaps(q, drills)` 换成直接用 applyDrills 的返回值（复扫后不再复盖）。
        val items = sameSubject((1L..8L).toList())
        val sim = ScoredSim { a, b ->
            when {
                setOf(a, b) == setOf(1L, 3L) -> 0.5f      // 混淆对：gap 2 < gapLo ⇒ 一定挪
                setOf(a, b) == setOf(2L, 4L) -> 0.9f      // 初序不相邻（下标 1 与 3），挪完才贴上
                else -> 0f
            }
        }
        val plan = QueueBuilder.build(items, sim, emptyList(), mapOf(1L to listOf(3L)), p)
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L), plan.queue.map { it.id })
        assertEquals(2, plan.drillPairs.first().gap)      // ⛔ 字面量 2 = 最终队列里的真实距离
        assertTrue("未解决: ${plan.unresolved}", plan.unresolved.isEmpty())
    }

    @Test fun `复扫换走带内那对的一张时间距涨到 gapHi 之外`() {
        // ⛔ 这条钉的是 [QueueBuilder.applyDrills] 注释里**第四条**出带路（原稿只写三条）：
        // drills 之后那一轮复扫为了拆掉自己那趟扫描够得着的违规，把记录在案的对子里的一张换走 ⇒ 最终距离离带。
        // 上尾（3 → 8 > gapHi 5）由这一条钉；下尾（3 → 2 < gapLo 3）上面那条
        // 「挪完又被复扫换开的对子报最终距离」早就跑出来过 —— 它当时的标题说的是"最终距离"的口径，
        // 而数目那句停在"三条"，于是同一件事在两处各写一套（Task 8 的 R3 收的就是这笔账）。
        //
        // 形状（`drillItems` 把初序摆成下面那份清单）：
        //  - 4 在下标 3、5 在下标 4，sim 0.9 > tauHi ⇒ **第一遍就有一处违规**，而它修不动：
        //    窗口是下标 5..16（`i + 2 .. i + 1 + lookahead`），那 12 格上是 id 6..17，与 4 全给 0.4
        //    ≥ tauLo ⇒ 一只合格候选都没有；池是空的（20 条的 cap = 2 也开着，但没有东西可插）⇒ 原地留下。
        //  - 混淆对 (13, 30)：初距 7 > gapHi ⇒ 一定要挪。drills 把 30 从下标 19 抽出来，插到
        //    anchor(13，下标 12) 后第三格 = 下标 15 —— 那一格**正好落在 4 的那只窗口里**，
        //    而 sim(4, 30) = 0 < tauLo ⇒ 它是这轮里第一只合格候选。落点两侧不撞：
        //    sim(30, 15) = sim(30, 16) = 0 ⇒ relocated = 1 ⇒ 复扫那一轮跑起来（`rounds = 1`）。
        //  - 复扫在 i=3 那一站把 q[4]（= 5）与 q[15]（= 30）对调：违规拆掉了（unresolved 空），
        //    代价是 30 离 anchor 从带内的 3 格涨成 **8** 格 ⇒ 报出来的最终 gap 出带，往上尾。
        // 杀的变异（真跑）：删掉 build 里那句复扫调用 ⇒ 30 停在下标 15、gap 停在带内的 3，
        // 下面第一句红（`expected:<8> but was:<3>`），末序那句同时红（变异 M3 的取证见 Task 8 报告）。
        val items = drillItems(listOf(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L, 11L, 12L, 13L, 14L, 15L, 16L, 17L, 18L, 19L, 30L).map { it to "A" })
        val sim = bandSim(
            Triple(4L, 5L, 0.9f),                          // 相邻违规：> tauHi，且高于 drillHi ⇒ 只归第一遍/复扫管
            Triple(4L, 6L, 0.4f), Triple(4L, 7L, 0.4f), Triple(4L, 8L, 0.4f), Triple(4L, 9L, 0.4f),
            Triple(4L, 10L, 0.4f), Triple(4L, 11L, 0.4f), Triple(4L, 12L, 0.4f), Triple(4L, 13L, 0.4f),
            Triple(4L, 14L, 0.4f), Triple(4L, 15L, 0.4f), Triple(4L, 16L, 0.4f), Triple(4L, 17L, 0.4f),
            Triple(13L, 30L, 0.5f),                        // 混淆对：带内（0.40..0.75）⇒ 生产输入也长得出这个形状
        )
        val plan = QueueBuilder.build(items, sim, emptyList(), mapOf(13L to listOf(30L)), p)
        assertEquals(8, plan.drillPairs.first().gap)       // ⛔ 高于 gapHi 5 的字面量：第四条路出到上尾
        assertEquals(8, kotlin.math.abs(idxOf(plan, 13L) - idxOf(plan, 30L)))
        assertEquals(
            listOf(1L, 2L, 3L, 4L, 30L, 6L, 7L, 8L, 9L, 10L, 11L, 12L, 13L, 14L, 15L, 5L, 16L, 17L, 18L, 19L),
            plan.queue.map { it.id },
        )
        assertEquals(20, plan.queue.size)                  // 挪位与复扫都不增不减
        assertTrue("未解决: ${plan.unresolved}", plan.unresolved.isEmpty())   // 复扫确实把 (4,5) 那一刀拆掉了
    }

    @Test fun `混淆映射的迭代序不影响结果`() {
        // ⛔ 钉偏差 D6 第 4 条（Map 迭代序不定）。两份内容相同、插入序相反的映射必须给同一个末序。
        // 杀的变异：`for ((aId, partners) in TreeMap(confusable))` 改回直接吃 `confusable`（吃调用方的迭代序）。
        // 夹具用 LinkedHashMap（不是 HashMap）：HashMap 对小 id 恰好按升序出桶，那一刀在它身上反而现不了形。
        // 后两句是防"白绿"的：如果第二遍什么都没挪，两次结果也相等 ⇒ 必须钉住末序本身。
        val items = sameSubject((1L..12L).toList())
        val sim = ScoredSim { a, b -> if (setOf(a, b) == setOf(1L, 2L) || setOf(a, b) == setOf(3L, 4L)) 0.5f else 0f }
        val expected = listOf(1L, 3L, 2L, 5L, 4L, 6L, 7L, 8L, 9L, 10L, 11L, 12L)
        val forward = QueueBuilder.build(items, sim, emptyList(), linkedOf(1L to listOf(2L), 3L to listOf(4L)), p)
        val reverse = QueueBuilder.build(items, sim, emptyList(), linkedOf(3L to listOf(4L), 1L to listOf(2L)), p)
        assertEquals(expected, forward.queue.map { it.id })
        assertEquals(expected, reverse.queue.map { it.id })
        assertEquals(forward.queue.map { it.id }, reverse.queue.map { it.id })
        assertEquals(2, forward.drillPairs.size)
    }

    @Test fun `HashMap 的桶序不许带着结果漂`() {
        // ⛔ 钉偏差 D6 第 4 条在**大 id** 上的那一半：上一轮报告让步说"`java.util.HashMap` 替 `TreeMap`
        // 在这批夹具下是等价变异"，那话只对当时的小 id 成立（M4a 那批 aId 全是个位/十位，恰好按升序出桶）。
        // 20 条 + 两个 key 就能现形：默认容量 16 时 id 15 落第 15 桶、id 17 落第 1 桶 ⇒ HashMap 先迭代 17。
        // 两对都挨着出（初距 1 < gapLo ⇒ 都要挪），而**处理序不同末序就不同**：
        //  先 15（[TreeMap] 归一化，本仓口径）⇒ [..., 15, 17, 16, 19, 18, 20]；
        //  先 17（跟着调用方的桶序走）⇒ [..., 15, 17, 19, 16, 20, 18]。
        // 杀的变异：`for ((aId, partners) in TreeMap(confusable))` 改回直接吃 `confusable`。
        // 末段两句顺手钉第三条出带路（applyDrills 的 KDoc）：(15,16) 挪完那刻是 3，(17,18) 那一挪把夹在中间的 18
        // 搬走 ⇒ 最终 2（拆掉前一对间隔的是后一次 drill 的动作，不是复扫 —— 这里压根没有违规可复扫）。
        val items = sameSubject((1L..20L).toList())
        val sim = ScoredSim { a, b ->
            if (setOf(a, b) == setOf(15L, 16L) || setOf(a, b) == setOf(17L, 18L)) 0.5f else 0f
        }
        val bucketed = java.util.HashMap<Long, List<Long>>()
        bucketed[15L] = listOf(16L)
        bucketed[17L] = listOf(18L)
        val plan = QueueBuilder.build(items, sim, emptyList(), bucketed, p)
        assertEquals(
            listOf(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L, 11L, 12L, 13L, 14L, 15L, 17L, 16L, 19L, 18L, 20L),
            plan.queue.map { it.id },
        )
        assertEquals(20, plan.queue.size)
        assertEquals(listOf(15L, 17L), plan.drillPairs.map { it.aId })   // 记录序也是 aId 升序
        assertEquals(2, plan.drillPairs[0].gap)                          // 带内的 3 被后一挪拆成 2：如实上报
        assertEquals(3, plan.drillPairs[1].gap)
        assertTrue(plan.unresolved.isEmpty())
    }

    @Test fun `伙伴清单的升序决定 drillPairs 的记录序`() {
        // ⛔ 钉 `partners.sorted()`：此前每条夹具的伙伴清单都只有一项 ⇒ 删掉它杀不掉任何东西。
        // anchor 1 挂两个伙伴，**插入序是 [9, 5]**（`linkedOf` 保住调用方给的清单序）：
        // 1 与 5 初距 4 落在带内（零成本、只记不挪），1 与 9 初距 8 > gapHi ⇒ 挪到 anchor 后第 3 格（下标 3）。
        // 升序 ⇒ 记录序 (1,5) 在前、(1,9) 在后；不升序 ⇒ 恰好相反。
        // 杀的变异：`for (bId in partners.sorted())` 改回 `for (bId in partners)`。
        // 末序在两档**逐格相同**（9 那一挪不依赖 5 先被看过：带内那一条只做了个判定），所以这条只能断记录序。
        val items = sameSubject((1L..12L).toList())
        val sim = ScoredSim { a, b ->
            if (setOf(a, b) == setOf(1L, 5L) || setOf(a, b) == setOf(1L, 9L)) 0.5f else 0f
        }
        val plan = QueueBuilder.build(items, sim, emptyList(), linkedOf(1L to listOf(9L, 5L)), p)
        assertEquals(listOf(5L, 9L), plan.drillPairs.map { it.bId })      // ⛔ 记录序 = 伙伴 id 升序
        assertEquals(2, plan.drillPairs.size)
        assertEquals(listOf(1L, 2L, 3L, 9L, 4L, 5L, 6L, 7L, 8L, 10L, 11L, 12L), plan.queue.map { it.id })
        assertEquals(5, plan.drillPairs[0].gap)                           // (1,5)：最终 1 在下标 0、5 在下标 5
        assertEquals(3, plan.drillPairs[1].gap)                           // (1,9)：挪成的 3
    }

    @Test fun `跨学科的对子不 drill`() {
        // 「仅同学科内」（文档 L1227）这道过滤住在 confusablePairs 里；applyDrills 只看调用方给的映射，
        // 既不看学科也不看分值。所以这条只能问 confusablePairs —— 简报原稿把映射直接喂给 build 再断言队列不动，
        // 那是拿 applyDrills 去撞一道它里面不存在的过滤（派发前订正第 1 条的同一条理由，落到学科这一侧）。
        // 杀的变异：删掉 `b.subject != a.subject` 那半句 ⇒ 1 与 2 成对，映射非空。
        val items = drillItems(listOf(1L to "语文", 2L to "数学", 3L to "英语", 4L to "物理", 5L to "化学"))
        val sim = bandSim(Triple(1L, 2L, 0.5f))          // 分值在混淆带里，只有学科不同
        assertEquals(emptyMap<Long, List<Long>>(), QueueBuilder.confusablePairs(items, sim, p))
    }

    @Test fun `没有学科归属的题不进混淆映射`() {
        // `subject.isBlank()` 那一道过滤的理由：两条都没有学科的题，`b.subject != a.subject` 筛不掉它们
        // （空串等于空串），于是"同科"判定会把它们配成对。杀的变异就是删掉那句 continue。
        val items = drillItems(listOf(1L to "", 2L to "", 3L to "A"))
        val sim = ScoredSim { a, b -> if (setOf(a, b) == setOf(1L, 2L)) 0.5f else 0f }
        assertEquals(emptyMap<Long, List<Long>>(), QueueBuilder.confusablePairs(items, sim, p))
    }

    @Test fun `超过上界的重复题不当混淆对`() {
        // 高于 drillHi 0.75 ⇒ 是重复题，只该被第一遍分开，不该成对出现（文档 L1230 的上界那一侧）。
        // 判的对象是 confusablePairs：带间过滤只在那一处（applyDrills 从不看分值）。
        // 杀的变异：`value in drillLo..drillHi` 松成只看下界 `value >= drillLo` ⇒ 0.95 混进映射。
        val items = drillItems(listOf(1L to "A", 2L to "A", 3L to "B", 4L to "C", 5L to "D"))
        val sim = bandSim(Triple(1L, 2L, 0.95f))
        assertEquals(emptyMap<Long, List<Long>>(), QueueBuilder.confusablePairs(items, sim, p))
    }

    @Test fun `混淆带的两个边界值都算`() {
        // ⛔ 文档 L1230 写的是闭区间：两端都含。夹具要让 1 与 5 **同科**（简报原稿五题五科，
        // 同学科过滤先把它们筛掉了，那条会直接红）。
        // 杀的变异：下界或上界的开闭写反（`>= drillLo && < drillHi` / `> drillLo && <= drillHi`）。
        val items = drillItems(listOf(1L to "A", 2L to "B", 3L to "C", 4L to "D", 5L to "A"))
        for (v in listOf(0.40f, 0.75f)) {
            val pairs = QueueBuilder.confusablePairs(items, bandSim(Triple(1L, 5L, v)), p)
            assertTrue("sim=$v 应算混淆对", pairs[1L] == listOf(5L) && pairs[5L] == listOf(1L))
        }
        val out = QueueBuilder.confusablePairs(items, bandSim(Triple(1L, 5L, 0.39f)), p)
        assertTrue("低于 drillLo 的也被当成了混淆对: $out", out.isEmpty())
    }

    @Test fun `每卡的混淆伙伴数不超过 topK`() {
        // hub 1 与其余 10 条同科题都在混淆带，**分值全等** ⇒ 这一条考的是截断长度 + 同分时的 id 升序 tie-break。
        // 跑过的变异两刀：
        //  ① `.take(policy.topK)` 写成 `.take(policy.topK + 2)` ⇒ 实跑红在 `expected:<8> but was:<10>`；
        //  ② tie-break `.thenBy { it.key }` 写成 `.thenByDescending { it.key }` ⇒ 留下的变成 4..11，
        //     实跑红在第二句：`expected:<[2, 3, 4, 5, 6, 7, 8, 9]> but was:<[4, 5, 6, 7, 8, 9, 10, 11]>`。
        val items = sameSubject((1L..11L).toList())
        val sim = ScoredSim { a, b -> if (a == 1L || b == 1L) 0.5f else 0f }
        val map = QueueBuilder.confusablePairs(items, sim, p)
        assertEquals(8, map[1L]!!.size)                   // ⛔ topK = 8 的字面值
        assertEquals(listOf(2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L), map[1L])
        for ((id, partners) in map) assertTrue("伙伴数超限（$id）: $partners", partners.size <= 8)
    }

    @Test fun `伙伴数超上界时截掉的是最不像的那几个`() {
        // 同一批 11 条，但分值随 id 递增：最不像的是 2 与 3 ⇒ 该被截掉的是它们，不是 id 最大的两个。
        // 杀的变异：把 `sortedWith(compareByDescending { 分值 }.thenBy { id })` 写成 `sortedBy { id }`（按 id 硬砍），
        // 那一条在「每卡的混淆伙伴数不超过 topK」上杀不出来（那里分值全等，两种排法给同一份清单），只能靠这条分开。
        val items = sameSubject((1L..11L).toList())
        val sim = bandSim(
            Triple(1L, 2L, 0.44f), Triple(1L, 3L, 0.47f), Triple(1L, 4L, 0.50f), Triple(1L, 5L, 0.53f),
            Triple(1L, 6L, 0.56f), Triple(1L, 7L, 0.59f), Triple(1L, 8L, 0.62f), Triple(1L, 9L, 0.65f),
            Triple(1L, 10L, 0.68f), Triple(1L, 11L, 0.71f),
        )
        val map = QueueBuilder.confusablePairs(items, sim, p)
        assertEquals(listOf(4L, 5L, 6L, 7L, 8L, 9L, 10L, 11L), map[1L])
        assertEquals(listOf(1L), map[2L])                 // 截断只收自己那一侧：2 被 1 的清单截掉，它自己的清单仍挂着 1
    }

    @Test fun `confusablePairs 对称写且遍历序确定`() {
        // 杀的变异有三个，都落在这一条的断言上：
        //  ① 把内层收成只走单向（`for (b in items.filter { it.id > a.id })`）⇒ 伙伴那一侧的清单缺席，map[5]/map[6] 红；
        //  ② 删掉清单末尾的 `.sorted()` ⇒ map[2] 停在分值降序的 [6L, 5L]（0.70 高于 0.45）；
        //  ③ 删掉 `partners.isEmpty() continue` ⇒ 谁也不像的卡也带着空清单进映射，map.keys 红。
        val items = sameSubject((1L..6L).toList())
        val sim = bandSim(Triple(2L, 5L, 0.45f), Triple(2L, 6L, 0.70f))
        val map = QueueBuilder.confusablePairs(items, sim, p)
        assertEquals(listOf(5L, 6L), map[2L])
        assertEquals(listOf(2L), map[5L])
        assertEquals(listOf(2L), map[6L])
        assertEquals(emptyList<Long>(), map[3L] ?: emptyList<Long>())   // 3 谁也不像：压根不进映射
        assertEquals(sortedSetOf(2L, 5L, 6L), map.keys)
        for ((_, partners) in map) assertEquals(partners.sorted(), partners)
    }

    @Test fun `挪不动时宁可不挪也不能造违规`() {
        // 1 与 8 的 gap 是 7（> gapHi ⇒ 一定要挪），但落点（anchor 往后第三题）的左邻是 3，
        // 而 sim(3,8) = 0.7 > tauHi ⇒ 拒绝挪位、原位放回。
        // 那个 0.7 的对子初序**不相邻**（3 在下标 2、8 在下标 7），否则第一遍会先替它修掉，
        // 测的就不是 drills 的拒绝（派发前订正第 3 条）。
        // 杀的变异：删掉 `if (clashes)` 那一段（无条件落到 target）⇒ 8 被插到下标 3，
        // 复扫为了拆掉它新撞上的那对（3 与 8，0.7 > tauHi）又把它换到下标 4。
        // 实跑红在下面第一句（末序 `[1, 2, 3, 4, 8, 5, 6, 7]`）；`reported.gap` 那句（7 vs 4）说的是同一个末态，
        // JUnit 只报第一个失败，所以它的红象没出现在同一份输出里。
        val items = sameSubject((1L..8L).toList())
        val sim = ScoredSim { a, b ->
            when {
                setOf(a, b) == setOf(1L, 8L) -> 0.5f
                setOf(a, b) == setOf(3L, 8L) -> 0.7f
                else -> 0f
            }
        }
        val plan = QueueBuilder.build(items, sim, emptyList(), mapOf(1L to listOf(8L)), p)
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L), plan.queue.map { it.id })
        assertEquals(8, plan.queue.size)
        val reported = plan.drillPairs.first { it.aId == 1L && it.bId == 8L }
        assertEquals(7, reported.gap)                      // ⛔ 报的是最终队列里的真实距离，不是"打算挪成的 3"
        assertEquals(7, kotlin.math.abs(idxOf(plan, 1L) - idxOf(plan, 8L)))
        assertTrue("未解决: ${plan.unresolved}", plan.unresolved.isEmpty())
    }

    @Test fun `够不着 gapLo 时夹到队尾也如实报真实距离`() {
        // ⛔ 钉 `placeBeside` 里 `coerceIn(0, q.size)` 那一档 —— 上一轮它**零覆盖**：没有任何夹具够得到，
        // 于是"删掉 coerceIn"是个杀不掉的变异（它只会 IndexOutOfBounds，而没东西走到那一步）。
        // 形状：anchor(1) 就在**队尾**（下标 11），伙伴 12 在下标 0（初距 11 > gapHi ⇒ 一定挪）。
        // 删掉下标 0 之后 anchor 左移到 10 ⇒ 想去的 target = 10 + gapLo 3 = 13 越出队尾 ⇒ 夹到 11（末位）
        // ⇒ 12 只能贴在 1 后面：真实距离 **1**，短于 gapLo。报的是真实值，不是打算挪成的 3。
        // 杀的变异（本轮真跑）：删掉 `.coerceIn(0, q.size)` ⇒ target 停在 13，当场 IndexOutOfBoundsException，
        // 炸点是落点检查读左邻那一句 `q[target - 1]`（Index 12 out of bounds for length 11），比 `q.add` 更早 ——
        // 上一轮"没有夹具够得到、这一刀杀不掉"的说法已被这一跑否掉：同批还带走「落点等于原位时不算挪动」那条。
        val items = drillItems(listOf(12L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L, 11L, 2L, 1L).map { it to "A" })
        val sim = bandSim(Triple(1L, 12L, 0.5f))
        val plan = QueueBuilder.build(items, sim, emptyList(), mapOf(1L to listOf(12L)), p)
        assertEquals(listOf(3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L, 11L, 2L, 1L, 12L), plan.queue.map { it.id })
        assertEquals(12, plan.queue.size)                      // 夹到队尾也不增不减
        assertEquals(1, plan.drillPairs.first().gap)           // ⛔ 低于 gapLo 的字面量：夹过就报 1
        assertEquals(1, kotlin.math.abs(idxOf(plan, 1L) - idxOf(plan, 12L)))
        assertTrue("未解决: ${plan.unresolved}", plan.unresolved.isEmpty())
    }

    @Test fun `drillNoteIds 只标后面那张`() {
        // 杀的变异：`if (pa < pb) drill.bId else drill.aId` 的两个分支互换 ⇒ 返回 {1,2}（挂在前面那张 = 剧透）。
        val queue = sameSubject((1L..6L).toList())
        val ids = drillNoteIds(queue, listOf(QueueDrill(1L, 4L, 3), QueueDrill(2L, 6L, 4)))
        assertEquals(setOf(4L, 6L), ids)
    }

    @Test fun `drillNoteIds 对队列里没有的 id 给空而不是崩`() {
        // 杀的变异：`position[drill.bId] ?: continue` 写成 `position[drill.bId]!!` ⇒ KotlinNullPointerException。
        val queue = sameSubject((1L..4L).toList())
        assertEquals(emptySet<Long>(), drillNoteIds(queue, listOf(QueueDrill(1L, 99L, 3))))
    }

    @Test fun `方向规则跳过的对子不算挪动：不追加复扫`() {
        // build 里的复扫是被 `relocated > 0` 挡着的（简报那版无条件跑）。这里给一份**被规范化方向直接 skip**
        // 的映射（aId=30、伙伴 5 在前面）⇒ 第二遍一次队列都没碰 ⇒ 不许再多扫一轮。
        // 夹具取 Task 3 那条「换位只往后看 lookahead 个位置」的 60 条形状：59 同簇 + 队尾一条异簇，
        // 四轮（12 × 4 = 48 格）之后那条异簇卡在**下标 11**（Task 3 已手推并实跑核对过）。
        // 多出来的第五轮会把它继续往队首冒（每个违规位置都能把它往前拽一格）⇒ 若去掉那道门，这里观测到的是 1。
        // 所以这条同时是「去掉 `if (relocated > 0)`」这个变异的杀手，也是「skip 掉的对子不该被记成 drill」的杀手。
        val items = (1L..60L).map { QueueItem(it, (61 - it).toFloat(), if (it <= 59L) "A" else "B") }
        val sim = ScoredSim { a, b -> if (a != b && a <= 59L && b <= 59L) 0.9f else 0f }
        val plan = QueueBuilder.build(items, sim, emptyList(), mapOf(30L to listOf(5L)), p)
        assertEquals(11, plan.queue.indexOfFirst { it.id == 60L })
        assertTrue("skip 掉的对子也被记成 drill 了: ${plan.drillPairs}", plan.drillPairs.isEmpty())
    }

    @Test fun `落点等于原位时不算挪动：不追加复扫`() {
        // ⛔ 钉 `placeBeside` 里 `target == ib` 那道门（上一轮没有它：原地放回也 `return true` ⇒ `relocated++`）。
        // 夹具是 Task 3 那条「换位只往后看 lookahead 个位置」的 60 条形状（59 条同簇 + 队尾一条异簇），
        // 只把初序下标 47 与 58 上的 id 对调（59 ↔ 48）—— 四轮前扫一张都没多搬（每轮仍只有一次换位），
        // 怪卡 60 仍落在**下标 11**，而队尾变成 `…, 48(下标 58), 59(下标 59)`。
        // 于是映射 {48:[59]} 的 anchor 在前、伙伴贴着队尾且初距 1 < gapLo ⇒ 一定尝试挪：
        // 删掉下标 59 ⇒ anchor = 58 ⇒ target = 58 + 3 = 61 → `coerceIn(0, 59)` 夹回 **59 = 抽它出来的那一格**，
        // 而 sim(48,59) = 0.5f ≤ tauHi ⇒ 落点检查也不拦 ⇒ 整趟做完队列逐字节未变。
        // 旧代码在这里返回 true ⇒ 复扫门开 ⇒ 那一轮从 i=0 就把怪卡从 11 换到下标 1
        // （实跑 `expected:<11> but was:<1>` —— 正是这道门本来要关的那个回归重新打开）。
        val ids = (1L..47L).toList() + listOf(59L) + (49L..58L).toList() + listOf(48L, 60L)
        val items = drillItems(ids.map { it to if (it <= 59L) "A" else "B" })
        val sim = ScoredSim { a, b ->
            when {
                setOf(a, b) == setOf(48L, 59L) -> 0.5f     // 混淆带内、又不越过 tauHi：落点两侧都不拒
                a <= 59L && b <= 59L -> 0.9f
                else -> 0f
            }
        }
        val noDrill = QueueBuilder.build(items, sim, emptyList(), emptyMap(), p)
        val plan = QueueBuilder.build(items, sim, emptyList(), mapOf(48L to listOf(59L)), p)
        assertEquals(60, plan.queue.size)
        assertEquals(11, plan.queue.indexOfFirst { it.id == 60L })     // ⛔ 四轮的字面量落点：空操作不该有第五轮
        assertEquals(noDrill.queue.map { it.id }, plan.queue.map { it.id })   // 队列逐字节未变
        assertEquals(1, plan.drillPairs.size)
        assertEquals(1, plan.drillPairs.first().gap)                   // ⛔ 它仍然贴着 48：如实上报，不假装挪成了
    }

    @Test fun `映射里本轮没有的 id 整对跳过，不崩也不记`() {
        // 调用方可能传一整份映射（文档 L1226 那张 `confusable` 表按题存、不按这一轮存），
        // 于是映射里会出现本轮队列里没有的 id：aId=0 不在队列、伙伴 5 在；aId=1 在、伙伴 99 不在。
        // 两句 `if (ia < 0) continue` / `if (ib < 0) continue` 各由一半夹具钉着：
        //  ① 删掉 ia 那一句 ⇒ (0,5) 的 gap 被算成 |-1 - 4| = 5，落在带内 ⇒ drillPairs 多出一条 aId=0 的记录；
        //  ② 删掉 ib 那一句 ⇒ (1,99) 走到 removeAt(-1) 当场 IndexOutOfBoundsException。
        val items = sameSubject((1L..12L).toList())
        val sim = bandSim(Triple(0L, 5L, 0.5f), Triple(1L, 99L, 0.5f))
        val plan = QueueBuilder.build(items, sim, emptyList(), linkedOf(0L to listOf(5L), 1L to listOf(99L)), p)
        assertTrue("队列外的 id 也被记成 drill 了: ${plan.drillPairs}", plan.drillPairs.isEmpty())
        assertEquals((1L..12L).toList(), plan.queue.map { it.id })
    }

    @Test fun `drill 移动次数有预算封顶`() {
        // 预算存在的理由：每次尝试挪位要两次 indexOfFirst（O(n)）加落点两个邻居的查表，不设上限就退化成 O(n·k)。
        // 夹具是 34 对**互不干扰**的混淆对：第 j 对占下标 4j 与 4j+1（挨着 ⇒ 每对都要挪），后两格是 filler；
        // 一次移动只重排自己那四格（删在 4j+1、插在 4j+3 ⇒ 下标 >= 4j+4 的元素一个都不动），
        // 所以"前 32 对都被处理、后 2 对一步没走"是推得死的。
        // 杀的变异：删掉 `if (attempts >= DRILL_MOVE_BUDGET) continue` ⇒ 34 对全被处理、预算等于没有。
        // 这一份夹具**全部是越界对子**，所以它钉的是"挪位封顶"；"记录不封顶"由下一条
        // 「预算耗尽后带内的对子照样记录」钉（同一道门，两个半边）。
        // 实跑先红在 `drillPairs.size` 那一句（`expected:<32> but was:<34>`）；最后一对的距离是第二道锁，
        // 同一刀下它也会跟着挪开（JUnit 只报第一个失败，所以第二句的红象没在同一份输出里出现）。
        val pairCount = 34
        val ids = ArrayList<Long>(pairCount * 4)
        val scores = ArrayList<Triple<Long, Long, Float>>(pairCount)
        val confusable = LinkedHashMap<Long, List<Long>>()
        for (j in 0 until pairCount) {
            val a = (j + 1).toLong()
            val b = (j + 101).toLong()
            ids.add(a)
            ids.add(b)
            ids.add((200 + 4 * j).toLong())
            ids.add((201 + 4 * j).toLong())
            scores.add(Triple(a, b, 0.5f))
            confusable[a] = listOf(b)
        }
        val plan = QueueBuilder.build(drillItems(ids.map { it to "A" }), bandSim(*scores.toTypedArray()), emptyList(), confusable, p)
        assertEquals(136, plan.queue.size)
        assertEquals(32, plan.drillPairs.size)             // ⛔ DRILL_MOVE_BUDGET 是 private，这里钉它的字面值
        assertEquals(1, kotlin.math.abs(idxOf(plan, 34L) - idxOf(plan, 134L)))   // 第 34 对根本没被处理
        assertTrue(plan.unresolved.isEmpty())
    }

    @Test fun `预算耗尽后带内的对子照样记录`() {
        // ⛔ 钉 R5 的裁定：**带内的对子永远记，预算只封"挪位"这一种动作**。
        // 旧实现到点时 `break` 出内层循环 ⇒ 连**零成本的带内对子**都不再记 ⇒ `drillPairs` 静默截断，
        // 而它经 `drillNoteIds` 就是会话内提示的来源 —— 提示不许因为预算而缺席；外层还照样空转（break 只出一层）。
        // 夹具 = 32 对越界（恰好把预算花光；每对占四格、删 4j+1 插 4j+3 ⇒ 后面的格一格不动，形状同上一条）
        //       + 1 对带内（33 与 37 隔 4 题：不花预算，必须被记下来 —— 旧实现在这里被 break 吞掉）
        //       + 1 对越界（34 与 38 挨着：预算已过 ⇒ 不记也不挪，证明预算确实还封着挪位）。
        // 杀的变异：把带内那段早退挪到 `if (attempts >= DRILL_MOVE_BUDGET) continue` **之后**
        // （或把那道门挪回 break 的位置）⇒ `drillPairs.size` 实跑 `expected:<33> but was:<32>`。
        val outOfBand = 32
        val ids = ArrayList<Long>(outOfBand * 4 + 7)
        val scores = ArrayList<Triple<Long, Long, Float>>(outOfBand + 2)
        val confusable = LinkedHashMap<Long, List<Long>>()
        for (j in 0 until outOfBand) {
            val a = (j + 1).toLong()
            val b = (101 + j).toLong()
            ids.add(a)
            ids.add(b)
            ids.add((201 + 2 * j).toLong())
            ids.add((202 + 2 * j).toLong())
            scores.add(Triple(a, b, 0.5f))
            confusable[a] = listOf(b)
        }
        ids.addAll(listOf(33L, 265L, 266L, 267L, 37L))        // 下标 128..132 ⇒ (33,37) 的 gap 4，落在带内
        ids.addAll(listOf(34L, 38L))                          // 下标 133、134 ⇒ gap 1，越界且预算已尽
        scores.add(Triple(33L, 37L, 0.5f))
        scores.add(Triple(34L, 38L, 0.5f))
        confusable[33L] = listOf(37L)
        confusable[34L] = listOf(38L)
        val plan = QueueBuilder.build(
            drillItems(ids.map { it to "A" }),
            bandSim(*scores.toTypedArray()),
            emptyList(),
            confusable,
            p,
        )
        assertEquals(135, plan.queue.size)
        assertEquals(33, plan.drillPairs.size)                 // ⛔ 32 次挪动 + 1 条带内记录（旧实现只有 32 条）
        assertEquals(33L, plan.drillPairs.last().aId)
        assertEquals(37L, plan.drillPairs.last().bId)
        assertEquals(4, plan.drillPairs.last().gap)            // ⛔ 带内那条报它自己的真实距离
        assertEquals(1, kotlin.math.abs(idxOf(plan, 34L) - idxOf(plan, 38L)))   // 第 34 对没被挪：预算照样封着挪位
        assertTrue(plan.unresolved.isEmpty())
    }
}
