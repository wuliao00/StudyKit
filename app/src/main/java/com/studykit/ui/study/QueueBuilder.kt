package com.studykit.ui.study

import java.util.TreeMap
import kotlin.math.abs

/**
 * 队列里的一项。[urgency] 越大越该先出。
 * 「装配层用它承载原序位置」是**前瞻**：那个装配层是 `QuestionQueuePlanner`（Task 5），本任务里还没有读这个字段的代码
 * —— 现在唯一用到它的是 [build] 的排序键。
 */
internal data class QueueItem(val id: Long, val urgency: Float, val subject: String)

/**
 * 一对被判定为易混的对子，[gap] 是**最终**队列里两者的距离（不是移动前的）。
 * 口径由 [QueueBuilder.stampGaps] 负责：drills 之后还有复扫，复扫可能又把其中一张换走，
 * 所以 gap 是在 `build` 全部走完之后再盖一次，而不是挪完就地取值。
 */
internal data class QueueDrill(val aId: Long, val bId: Long, val gap: Int)

/**
 * @param pulled 从缓冲池拉到前面的条数（受 [InterleavePolicy] 之外的 10% 上限约束）
 * @param maxAdjSim 最终队列里最相邻的一对相似度；**少于两条时为 null** ——
 *        null 表示"没得测"，不是"测出 0"。用 `Float?` 而不是拿 0f 当哨兵，
 *        因为 0f 会被下游读成"这些题一点都不像"，那是假信息。
 * @param drillPairs 第二遍记下的混淆对；`confusable` 给空映射时它就是空。
 *        已隔开在带内的那一对也会记 —— 调用方要的是"这一场有成对出现"，不是"这一场动过谁"。
 * @param unresolved 最终仍违反 `sim > tauHi` 的相邻位置下标（空 = 修干净了）
 */
internal data class QueuePlan(
    val queue: List<QueueItem>,
    val pulled: Int,
    val maxAdjSim: Float?,
    val drillPairs: List<QueueDrill>,
    val unresolved: List<Int>,
)

/**
 * 相似题交错的两遍约束（文档 §② L1230）。
 *
 * 第一遍：`sim > tauHi` 的两题不许相邻 —— 先在 [InterleavePolicy.lookahead] 窗口内找一条
 * 与左邻足够不像（`sim < tauLo`）的题**换位**，找不到再从缓冲池**插一张**（受条数一成上限）。
 * 第二遍：混淆带 `[drillLo, drillHi]` 内的对子强制同场并隔开 `gapLo..gapHi` 题；真挪动过卡就再复扫一遍第一遍的约束。
 *
 * 与文档参考实现的差别全部记在计划的偏差 D5/D6：
 *  1. 有界窗口 + 每轮一次单向前扫 + 最多 [InterleavePolicy.maxRepairRounds] 轮，
 *     替掉最坏 O(n²) 的全尾扫描 —— 查表次数上界由**五项**相加，少写任何一项都会低估：
 *      - 换位项：`n × (lookahead + 1) × maxRepairRounds`（每个位置 1 次相邻判定 + 窗口内至多 lookahead 次候选判定）；
 *      - **复扫项**：`n × (lookahead + 1)` —— [repairAdjacent] 在 drills **真挪动过卡**之后以 `rounds = 1` 再跑一轮。
 *        这一项是**有条件**的：`confusable` 给空映射、或每一对本来就隔在 `gapLo..gapHi` 之内、
 *        或每一次尝试都被落点拒掉时，`relocated` 是 0 ⇒ 这一轮根本不跑 ⇒ 这项为 0；
 *      - **插池扫描项**：`insertBuffer` 是在**每个**违规位置上都试的（只要 `pulled < pullCap` 还没耗尽），
 *        每试一次最坏扫完整池、每条候选查两个邻居 ⇒ `违反位置数 × maxRepairRounds × |pool| × 2`，
 *        其中违反位置数 ≤ `n`。池里有几百张都不像的卡时这一项比换位项大一个数量级
 *        （n=500、|pool|=500 时约为第一项的 80 倍），所以它必须写在账上。
 *        复扫那一轮的 `pullCap` 是 0 ⇒ `pulled < pullCap` 先假 ⇒ 这一项**只算第一遍**；
 *      - **drills 落点项**：每次尝试挪位最多问落点左右两个邻居 ⇒ `DRILL_MOVE_BUDGET × 2`。
 *        预算封顶的是"尝试次数"（被落点拒掉的也算一次），所以这一项与映射里有多少对子无关。
 *        `gap` 与 drill 落位用的都是下标查找（[gapOf]/[stampGaps]），不查表 ⇒ 不进这笔账；
 *      - 外加 [report] 的 `2 × (n − 1)` 次读数（`maxAdjSim` 一遍、`unresolved` 走 [violates] 一遍）。
 *     上界存在 Task 8 才钉得住，届时按这五项推（它那些用例的 `confusable` 都是空映射 ⇒ 第二、三项为 0）；
 *  2. drills 之后**复扫**一遍（参考实现挪完 b 就不管新邻居了，偏差 D6 第 2 条）；
 *  3. `maxAdjSim` 用 `maxOfOrNull`，少于两条给 null，不抛；
 *  4. 一切遍历序定死：排序键 `(urgency desc, id asc)`，混淆映射按 id 升序走（[applyDrills] 外层 `TreeMap` 归一化，
 *     不跟着调用方给的 `Map` 迭代序漂）。
 *
 * 纯函数：不吃 Android、不吃 Room、不做 IO，相似度一律从注入的 [Simularity] 拿。
 * [build] 的 `confusable` 是**输入**而不是现场算出来的（文档 L1226 那张 `confusable` 表的位置，语义包计划的事）；
 * 生产路径由 Task 5 的装配层调 [confusablePairs] 算好再喂进来 —— 那一步是前瞻，本任务的调用点只有测试。
 */
internal object QueueBuilder {

    /**
     * 缓冲拉前条数上限的**分子**：`due 条数 × PULL_CAP_PERCENT / 100`（分母是那个 100，不是这里的 10），
     * 对应文档 L1264「pulled 上限 = due 数 ×10%」。整数除法 ⇒ 不足十题的会话上限就是 0。
     */
    private const val PULL_CAP_PERCENT = 10

    /**
     * drills 那一遍**尝试**挪位的次数上限（不是"挪成了多少次"：落点被拒掉的那次同样查过落点的两个邻居，
     * 成本已经花出去了）。存在的理由与 [PULL_CAP_PERCENT] 同类，但它封的是**查表**那一半：
     * 没有上限，落点检查就是 `对子数 × 2` 次查表，类 KDoc 里 `DRILL_MOVE_BUDGET × 2` 那一项也就不再与映射的大小脱钩。
     * 下标扫描那一半它封不住：每一对先要用 [gapOf] 判在不在带内，那是 `对子数 × n` 次 `indexOfFirst`，
     * 与本预算无关 —— 上界那笔账记的是查表次数，不是下标扫描次数。
     */
    private const val DRILL_MOVE_BUDGET = 32

    fun build(
        due: List<QueueItem>,
        sim: Simularity,
        pool: List<QueueItem>,
        confusable: Map<Long, List<Long>>,
        policy: InterleavePolicy,
    ): QueuePlan {
        val q = due.sortedWith(compareByDescending<QueueItem> { it.urgency }.thenBy { it.id }).toMutableList()
        val inQueue = q.mapTo(HashSet(q.size)) { it.id }
        val cap = due.size * PULL_CAP_PERCENT / 100
        val pulled = repairAdjacent(q, inQueue, sim, pool, policy, policy.maxRepairRounds, cap)
        val (drills, relocated) = applyDrills(q, sim, confusable, policy)
        // 复扫：drills 挪完会造出新的相邻违规（偏差 D6 第 2 条），而这一遍**不再拉新卡** ——
        // 拉前额度已在第一遍花掉，`pullCap = 0` 让 insertBuffer 那道门连开都没开。
        //
        // 为什么要 `relocated > 0` 挡着（简报那版是无条件跑）：drills 一张都没动时队列没变，
        // 而这一遍是**又一轮完整前扫**，它会接着把第一遍搬不动的卡继续往队首搬 ——
        // 每轮最多一个窗口（`lookahead` 格），第一遍 KDoc 那句"总位移受 lookahead × maxRepairRounds 约束"
        // 连带钉它的那条用例（`QueueBuilderAdjacentTest.换位只往后看 lookahead 个位置`，实跑 11 变 1）就一起被推翻。
        // 挪动过才需要复扫，没挪动就不该有第五轮。
        if (relocated > 0) {
            repairAdjacent(q, inQueue, sim, emptyList(), policy, rounds = 1, pullCap = 0)
        }
        return report(q, sim, pulled, stampGaps(q, drills), policy)
    }

    /**
     * 相邻违规判定的**唯一一处**算术（计划的全局约束「单一算术之家：相邻判定、gap 计算、pull 上限只允许一处实现」）：
     * [repairAdjacent] 的修复点、[report] 的 `unresolved`、[placeBeside] 的落点检查都调它，
     * 不在三处各写一遍 `> policy.tauHi`。gap 那一半同理，唯一的实现是 [gapOf]。
     *
     * 方向：严格大于 —— 分值**恰好等于** `tauHi` 不算违规。简报里那版把这条式子内联在每个写它的地方，
     * 与全局约束直接冲突，约束胜出（这条是裁定，不是实现者的判断）。
     *
     * 前置条件 `tauLo <= tauHi`：[InterleavePolicy] 是普通 data class、不校验任何不变量，
     * 所以这里只把口径写死在句子中；这条不变量归 [repairAdjacent] 的完备性段讨论，
     * 而 `LexicalInterleave.DEFAULT` 的 `tauLo < tauHi` 由 `InterleavePolicyTest` 的顺序用例钉着 ——
     * 换一份不满足它的 policy 进来，本类不保证修干净（`unresolved` 会如实报出来，不是静默错序）。
     */
    private fun violates(a: QueueItem, b: QueueItem, sim: Simularity, policy: InterleavePolicy): Boolean =
        sim.sim(a.id, b.id) > policy.tauHi

    /**
     * 相邻修复。返回拉入的缓冲卡数。
     *
     * 每轮就是**一次单向前扫**，动完位置只 `i++`，不回退。不回退是完备的，因为一次移动只会
     * 碰坏指针前方或脚下的相邻对：换位 `i+1 <-> j`（`j >= i+2`）改动的相邻对只有
     * `(i,i+1)`、`(i+1,i+2)`、`(j-1,j)`、`(j,j+1)` 四对 —— `(i,i+1)` 由换位的成立条件
     * （`sim(q[i], q[j]) < tauLo`，而换进 `i+1` 的正是原来 `j` 上那条）直接保证合格，
     * **这一句要 `tauLo <= tauHi` 才成立**（[InterleavePolicy] 不校验它，是文档口径；真把 tauLo 调到 tauHi 之上，
     * 换进来那条就可能自己违规而前扫不再回看它）；
     * 其余三对分别在本轮的 `i+1`（下一步就查到）、`j-1`、`j` 三处被重新查到，
     * `j > i+1` 保证指针一定追得上。插入只新增 `(i,i+1)` 与 `(i+1,i+2)` 两对，
     * 两对都在插入前查过（两侧邻居都不像才让插），而 `(旧 i+1, 旧 i+2)` 在 `i+2` 处查到。
     *
     * 旧稿在这里 `i = (i - 1).coerceAtLeast(0)` 回退一格，理由是"换位会改变 i-1 处的相邻关系" ——
     * 那句是错的：换位只动下标 `i+1` 与 `j` 两个位置，`i-1` 和 `i` 上的元素一个都没动
     * （括号里描述的破损位置其实是 `j-1`，在指针前方，前扫本来就会查到）。
     * 回退真正买到的是另一样东西：重开 `i-1` 那次的换位尝试，而它的窗口起点正好是刚被换进新卡的 `i+1`，
     * 于是每退一步就把一张卡往前拽一格 —— 60 条夹具里能把队尾那条从下标 59 一路拖到 1，
     * 属于用户可见的错序（该在会话末尾的题成了第 2 题）。所以删回退、也删它配套的每轮步数预算：
     * 不回退的单次前扫本来就是 O(n) 的。
     *
     * 那为什么还要多轮：本轮走到 `i` 时"窗口里没有可换的"不等于这个位置整轮都修不动 ——
     * 同一轮里更靠后的换位会把一张异簇卡片搬进 `i` 的窗口范围（候选被搬到 `i'+1`，而 `i'` 落在 `i` 的窗口内），
     * 但指针已经走过 `i`，只有下一轮从头再扫才捡得到它。「换位只往后看 lookahead 个位置」那条用例的
     * 60 条夹具就是这么搬的：**每轮最多前移一个窗口（`lookahead` 格），四轮 48 格**（59 → 47 → 35 → 23 → 11）。
     * 轮数由 [InterleavePolicy.maxRepairRounds] 封住上界，本轮 `changed` 为假即早停；
     * 那条用例同时钉了 `maxRepairRounds = 3` 的下标（23），所以"循环真读的是 policy 的轮数"不是只能靠读代码相信。
     */
    private fun repairAdjacent(
        q: MutableList<QueueItem>,
        inQueue: MutableSet<Long>,
        sim: Simularity,
        pool: List<QueueItem>,
        policy: InterleavePolicy,
        rounds: Int,
        pullCap: Int,
    ): Int {
        var pulled = 0
        for (round in 0 until rounds) {
            var changed = false
            var i = 0
            while (i < q.size - 1) {
                if (violates(q[i], q[i + 1], sim, policy)) {
                    val moved = swapInWindow(q, i, sim, policy) ||
                        (pulled < pullCap && insertBuffer(q, inQueue, i, sim, pool, policy).also { if (it) pulled++ })
                    if (moved) changed = true
                }
                i++
            }
            if (!changed) break
        }
        return pulled
    }

    /**
     * 把窗口 `[i+2, i+1+lookahead]`（右端截到队尾）内第一条"与 `q[i]` 足够不像"的题换到 `i+1`。
     *
     * 窗口约束的是**单次位移**，不是总位移：一张卡换到 `i+1` 之后指针就越过它，本轮不会再有位置比它
     * 更靠前的违规把它当候选，所以它**往队首方向**每轮最多被搬一次、每次最多 lookahead 格，
     * 总位移受 `lookahead × maxRepairRounds` 约束（被它换走那条往队尾方向的挪动不设这条界）。
     * 60 条夹具实测就是这个乘积：59 → 47 → 35 → 23 → 11。返回是否动了
     */
    private fun swapInWindow(q: MutableList<QueueItem>, i: Int, sim: Simularity, policy: InterleavePolicy): Boolean {
        val from = i + 2
        val to = minOf(i + 1 + policy.lookahead, q.lastIndex)
        for (j in from..to) {
            if (sim.sim(q[i].id, q[j].id) < policy.tauLo) {
                val t = q[j]
                q[j] = q[i + 1]
                q[i + 1] = t
                return true
            }
        }
        return false
    }

    /**
     * 从池里挑一条缓冲卡插到 i+1。候选必须**同时**与左右两邻足够不像（文档 L1246 两侧都查，这条照抄）。
     *
     * 两侧都查的理由要说准，不是"只查一侧会把一个违规换成两个"—— 那是算术上的错话：
     * 插入把 `(q[i], q[i+1])` 这一对**劈成** `(q[i], 新卡)` 与 `(新卡, q[i+1])` 两对，
     * 被查的那一侧已经合格，没查的那一侧才可能违规 ⇒ 瞬时只多出一个**挪了位置的**违规，**违规总数不增**。
     * `池卡与右邻相似时不许插` 就是这一刀的杀手。
     * 但也别把它写成"被挪走的那个违规还留在队列里，只是换了下标"—— 那半句同样超出证据：前扫的指针
     * 下一步就走到新卡那一格，同一轮里就把那个挪了位置的违规换掉了。`池卡与右邻相似时不许插` 那份夹具
     * 在"删掉右侧检查"这一刀下的末序是 `[1, 90, 3, 2, 4, 5, 6, 7, 8, 9, 10]`、`unresolved` 为**空**
     * （这份末态出自复审那份独立替身的推演；Kotlin 实跑只读到第一个失败的断言）。
     * ⇒ 这一刀看得见的伤害是**一张白插的卡 + 队列多一条**，外加白吃掉一格缓冲额度；杀它的因此只能是
     * `pulled` / `size` 那两句（修复轮 1 实跑 `expected:<0> but was:<1>` 红在第一句上），别指望 `unresolved` 报信。
     *
     * 「两侧都查」得靠簇型替身之外的按对分值才钉得住：替身的簇是传递的，两邻同簇时
     * "像一侧"必然"像两侧"，只查一侧的实现照样过。所以那两条用例改用非传递的按对打分器
     * （`池卡与左邻相似时不许插` / `池卡与右邻相似时不许插`），各杀一个方向的变异。
     */
    private fun insertBuffer(
        q: MutableList<QueueItem>,
        inQueue: MutableSet<Long>,
        i: Int,
        sim: Simularity,
        pool: List<QueueItem>,
        policy: InterleavePolicy,
    ): Boolean {
        val buffer = pool.firstOrNull {
            it.id !in inQueue &&
                sim.sim(it.id, q[i].id) < policy.tauLo &&
                sim.sim(it.id, q[i + 1].id) < policy.tauLo
        } ?: return false
        q.add(i + 1, buffer)
        inQueue.add(buffer.id)
        return true
    }

    /**
     * 第二遍：把混淆映射里的对子挪到距 anchor `gapLo..gapHi` 题的位置。
     *
     * 遍历序全部定死（偏差 D6 第 4 条）：外层按 `aId` 升序（[TreeMap] 归一化，调用方给 `HashMap`
     * 还是 `LinkedHashMap` 都不改结果），内层按伙伴 id 升序，并且只处理规范化方向（`bId > aId`）——
     * 对称写入的映射如果两侧都处理一遍，同一对会被挪两次，第二次的起点还是第一次的结果，队列就随插入序漂了。
     *
     * 这一遍**只看映射，不看分值也不看学科**：混淆带 `[drillLo, drillHi]` 与「仅同学科内」两道过滤
     * 只住在 [confusablePairs] 那一处。于是"越界的重复题不该成对"这件事钉在 [confusablePairs] 的用例上，
     * 而不是钉在这里 —— 调用方直接塞一份越界映射进来，这里照样会把它挪成一对（文档 L1226 的
     * `confusable` 表就是这么当输入用的）。
     * 映射里不在本轮队列的 id 直接跳过：drills 只对本轮真正同场的对子负责。
     *
     * 返回的第二项是**真的把卡挪走了多少次**（落点被拒、原位放回的那些不计）——
     * [build] 用它决定要不要复扫，别让"记了几条 drill"冒充"动了几次队列"。
     */
    private fun applyDrills(
        q: MutableList<QueueItem>,
        sim: Simularity,
        confusable: Map<Long, List<Long>>,
        policy: InterleavePolicy,
    ): Pair<List<QueueDrill>, Int> {
        val drills = ArrayList<QueueDrill>()
        var attempts = 0
        var relocated = 0
        for ((aId, partners) in TreeMap(confusable)) {
            for (bId in partners.sorted()) {
                if (bId <= aId) continue                      // 规范化方向：一对只处理一次
                if (attempts >= DRILL_MOVE_BUDGET) break      // 封顶的是尝试次数，落点被拒的那次也算尝试
                val ia = q.indexOfFirst { it.id == aId }
                if (ia < 0) continue
                val ib = q.indexOfFirst { it.id == bId }
                if (ib < 0) continue
                val gap = gapOf(q, aId, bId)
                if (gap in policy.gapLo..policy.gapHi) {
                    drills.add(QueueDrill(aId, bId, gap))     // 已经隔开了，不动也记：调用方要的是"这一场有成对出现"
                    continue
                }
                val moving = q.removeAt(ib)
                if (placeBeside(q, ia, ib, moving, sim, policy)) relocated++
                attempts++
                drills.add(QueueDrill(aId, bId, gapOf(q, aId, bId)))
            }
        }
        return drills to relocated
    }

    /**
     * 两者在 [q] 里的距离。gap 的**唯一**算法在这里（[violates] 之于相邻判定是同一个规矩）。
     *
     * 前置条件：两个 id 都在 `q` 里 —— 只有 [applyDrills] 确认过两侧都找得到之后才会调它，
     * 而 drills 与复扫都不增不减条目，所以 [stampGaps] 再算一遍时这个前提仍然成立。
     */
    private fun gapOf(q: List<QueueItem>, aId: Long, bId: Long): Int =
        abs(q.indexOfFirst { it.id == aId } - q.indexOfFirst { it.id == bId })

    /**
     * 把 [moving] 放到距 anchor [InterleavePolicy.gapLo] 题的位置。
     *
     * 先删后算：`removeAt(ib)` 会让 `ib` 之后的所有下标左移一格，anchor 在 `ib` 之后时必须减一
     * —— 文档参考实现正是漏了这一步（偏差 D6 第 1 条）。
     *
     * 落点两侧如果有 `sim > tauHi` 的邻居（走 [violates]，与修复点同一个家），**原位放回并放弃这次移动**：
     * 交错的目的就是把像的题分开，用制造新违规的方式消除旧违规是倒着走。放弃之后这一对就留在原距离上，
     * 由 [QueueDrill.gap] 如实报出来（见测试「挪不动时宁可不挪也不能造违规」）。
     *
     * 返回是否真的挪成了（原位放回给 false）—— [build] 的复扫门只看真挪动过的次数。
     */
    private fun placeBeside(
        q: MutableList<QueueItem>,
        ia: Int,
        ib: Int,
        moving: QueueItem,
        sim: Simularity,
        policy: InterleavePolicy,
    ): Boolean {
        val anchor = if (ib < ia) ia - 1 else ia
        val target = (anchor + policy.gapLo).coerceIn(0, q.size)
        val clashes = (target > 0 && violates(moving, q[target - 1], sim, policy)) ||
            (target < q.size && violates(moving, q[target], sim, policy))
        if (clashes) {
            q.add(ib, moving)                                 // 原位放回：长度与内容都不变
            return false
        }
        q.add(target, moving)
        return true
    }

    /**
     * 把 drill 的 `gap` 换成**最终队列**里的真实距离 —— [QueueDrill.gap] 的口径就是这么定的。
     *
     * 为什么不在 [applyDrills] 里就地定稿：drills 之后还有复扫，复扫为了拆掉新造出的相邻违规，
     * 可能把这一对里的某一张再换走一格。不重盖的话，报出来的就是"挪完那一刻"的距离而不是最终距离。
     * 这一趟只有下标查找、没有查表 ⇒ [build] 的查找上界那笔账不因为它加项。
     */
    private fun stampGaps(q: List<QueueItem>, drills: List<QueueDrill>): List<QueueDrill> =
        drills.map { it.copy(gap = gapOf(q, it.aId, it.bId)) }

    /**
     * 混淆对映射：同科目、分值落在 `[drillLo, drillHi]`（**两端都含**，文档 L1230 写的就是闭区间）、
     * 每卡最多 [InterleavePolicy.topK] 个伙伴。
     *
     * 「对称写入」（文档 L1227）由遍历方式本身保证：内外两层收的是**有序对**，`a` 那一轮把 `b` 记进自己的清单，
     * `b` 那一轮会把 `a` 记进它自己的清单（`Simularity` 的契约里有 `sim(a,b) == sim(b,a)` 这一条，
     * 两道学科过滤也是对称的）。文档参考实现是单向收完再反向补一遍，那份补齐在这段代码里**没有可踩的差别**
     * —— 只有违反对称契约的提供方才走得出两种结果，而那条契约不归这里钉。
     *
     * 对称的是**收进来**这一步，不是截完之后：topK 只截自己那一侧的清单，被截掉的伙伴自己那侧仍可能挂着对方
     * （测试「伙伴数超上界时截掉的是最不像的那几个」钉的就是这个末态：`map[1]` 里没有 2，`map[2]` 仍是 `[1]`）。
     * 对 [applyDrills] 的后果：一对只在**较小 id 那一侧**没被截掉时才会真被 drill ——
     * 挂在较大 id 那一侧的条目会被规范化方向 `bId <= aId ⇒ continue` 挡掉。
     *
     * 返回的 `Map` 键升序、每个 value 按 id 升序：[applyDrills] 的确定性建立在这两条上。
     * 每卡的清单按 `(分值降序, id 升序)` 排完再截到 topK —— 截的是最不像的那些，不是按 id 硬砍；
     * 同分时按 id 升序留前面的。截完再排回 id 升序。
     *
     * 成本：同科两两比对，O(同科条数²) 次查表。**生产路径喂的是 ≤20 条的会话池**，
     * 500 条那一档（Task 8）测的是 `build`，混淆映射按文档自己的做法当输入给定。
     * 真要给 500 条算混淆对，那正是语义包计划里 `confusable` 表存在的理由（文档 L1226）。
     */
    fun confusablePairs(
        items: List<QueueItem>,
        sim: Simularity,
        policy: InterleavePolicy,
    ): Map<Long, List<Long>> {
        val scored = HashMap<Long, MutableMap<Long, Float>>()
        for (a in items) {
            if (a.subject.isBlank()) continue                  // 没有学科归属就不参与"同学科"配对：空串等于空串，下面那道过滤筛不掉它
            val partners = scored.getOrPut(a.id) { HashMap() }
            for (b in items) {
                if (b.id == a.id || b.subject != a.subject) continue
                val value = sim.sim(a.id, b.id)
                if (value in policy.drillLo..policy.drillHi) partners[b.id] = value
            }
        }
        val out = TreeMap<Long, List<Long>>()
        for ((a, partners) in scored) {
            if (partners.isEmpty()) continue                   // 谁也不像的卡不进映射：调用方按"有没有键"判有没有成对
            val ordered = partners.entries
                .sortedWith(compareByDescending<Map.Entry<Long, Float>> { it.value }.thenBy { it.key })
                .take(policy.topK)
                .map { it.key }
                .sorted()                                      // 最终按 id 升序：applyDrills 的遍历序
            out[a] = ordered
        }
        return out
    }

    /**
     * 汇总最终队列的读数。相邻违规的判定走 [violates]（与修复点同一个家），这里不另写比较式。
     * [drills] 是第二遍的产出，且在 [build] 里已经过 [stampGaps] 复盖 —— 这里的 `gap` 就是最终队列里的距离。
     */
    private fun report(
        q: List<QueueItem>,
        sim: Simularity,
        pulled: Int,
        drills: List<QueueDrill>,
        policy: InterleavePolicy,
    ): QueuePlan {
        return QueuePlan(
            queue = q,
            pulled = pulled,
            maxAdjSim = (0 until q.lastIndex).maxOfOrNull { sim.sim(q[it].id, q[it + 1].id) },
            drillPairs = drills,
            unresolved = (0 until q.lastIndex).filter { violates(q[it], q[it + 1], sim, policy) },
        )
    }
}

/**
 * 会话内提示该挂在哪几张题上：一个 drill 对子里**排在后面的那张**。
 *
 * 挂在前面那张没意义 —— 用户还没遇到伙伴，提示就成了剧透。
 * 队列里不存在的 id 直接忽略：调用方传的可能是一整份映射（文档 L1226 那张 `confusable` 表按题存，
 * 不按这一轮存），里面的伙伴不一定都在这轮队列里。缓冲卡拉进来的那张会不会撞上这条，
 * 要等 Task 5 的装配层真把它喂进 `build` 才有实测答案，这里只保证不抛。
 */
internal fun drillNoteIds(queue: List<QueueItem>, drills: List<QueueDrill>): Set<Long> {
    if (drills.isEmpty()) return emptySet()
    val position = HashMap<Long, Int>(queue.size)
    queue.forEachIndexed { i, item -> position.putIfAbsent(item.id, i) }
    val out = HashSet<Long>()
    for (drill in drills) {
        val pa = position[drill.aId] ?: continue
        val pb = position[drill.bId] ?: continue
        out += if (pa < pb) drill.bId else drill.aId
    }
    return out
}
