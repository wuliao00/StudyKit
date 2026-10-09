package com.studykit.ui.study

/**
 * 队列里的一项。[urgency] 越大越该先出。
 * 「装配层用它承载原序位置」是**前瞻**：那个装配层是 `QuestionQueuePlanner`（Task 5），本任务里还没有读这个字段的代码
 * —— 现在唯一用到它的是 [build] 的排序键。
 */
internal data class QueueItem(val id: Long, val urgency: Float, val subject: String)

/**
 * 一对被判定为易混的对子，[gap] 是**最终**队列里两者的距离（不是移动前的）。
 * 前瞻：本任务不产出它（[QueuePlan.drillPairs] 恒空），[gap] 的这条口径要等 Task 4 的第二遍填进它才算被验证过。
 */
internal data class QueueDrill(val aId: Long, val bId: Long, val gap: Int)

/**
 * @param pulled 从缓冲池拉到前面的条数（受 [InterleavePolicy] 之外的 10% 上限约束）
 * @param maxAdjSim 最终队列里最相邻的一对相似度；**少于两条时为 null** ——
 *        null 表示"没得测"，不是"测出 0"。用 `Float?` 而不是拿 0f 当哨兵，
 *        因为 0f 会被下游读成"这些题一点都不像"，那是假信息。
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
 * 第二遍：混淆带 `[drillLo, drillHi]` 内的对子强制同场并隔开 `gapLo..gapHi` 题（Task 4）。
 *
 * 与文档参考实现的差别全部记在计划的偏差 D5/D6：
 *  1. 有界窗口 + 每轮一次单向前扫 + 最多 [InterleavePolicy.maxRepairRounds] 轮，
 *     替掉最坏 O(n²) 的全尾扫描 —— 查表次数上界由**两项**相加，只写第一项会低估：
 *      - 换位项：`n × (lookahead + 1) × maxRepairRounds`（每个位置 1 次相邻判定 + 窗口内至多 lookahead 次候选判定）；
 *      - **插池扫描项**：`insertBuffer` 是在**每个**违规位置上都试的（只要 `pulled < pullCap` 还没耗尽），
 *        每试一次最坏扫完整池、每条候选查两个邻居 ⇒ `违反位置数 × maxRepairRounds × |pool| × 2`，
 *        其中违反位置数 ≤ `n`。池里有几百张都不像的卡时这一项比换位项大一个数量级
 *        （n=500、|pool|=500 时约为第一项的 80 倍），所以它必须写在账上；
 *      - 外加 [report] 的 `2 × (n − 1)` 次读数（`maxAdjSim` 一遍、`unresolved` 走 [violates] 一遍）。
 *     上界存在 Task 8 才钉得住，届时按这三项推；
 *  2. drills 之后**复扫**一遍（参考实现挪完 b 就不管新邻居了；复扫在 Task 4，本任务没有它）；
 *  3. `maxAdjSim` 用 `maxOfOrNull`，少于两条给 null，不抛；
 *  4. 一切遍历序定死：排序键 `(urgency desc, id asc)`，混淆映射按 id 升序走。
 *
 * 纯函数：不吃 Android、不吃 Room、不做 IO，相似度一律从注入的 [Simularity] 拿。
 * [build] 的 `confusable` 参数本任务先收下不用，Task 4 的第二遍接线它。
 */
internal object QueueBuilder {

    /**
     * 缓冲拉前条数上限的**分子**：`due 条数 × PULL_CAP_PERCENT / 100`（分母是那个 100，不是这里的 10），
     * 对应文档 L1264「pulled 上限 = due 数 ×10%」。整数除法 ⇒ 不足十题的会话上限就是 0。
     */
    private const val PULL_CAP_PERCENT = 10

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
        return report(q, sim, pulled, emptyList(), policy)
    }

    /**
     * 相邻违规判定的**唯一一处**算术（计划的全局约束「单一算术之家：相邻判定、gap 计算、pull 上限只允许一处实现」）：
     * [repairAdjacent] 的修复点与 [report] 的 `unresolved` 都调它，不在两处各写一遍 `> policy.tauHi`。
     *
     * 方向：严格大于 —— 分值**恰好等于** `tauHi` 不算违规。简报里那版把这条式子内联在两处，
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
     * 汇总最终队列的读数。相邻违规的判定走 [violates]（与修复点同一个家），这里不另写比较式。
     * 前瞻：第二遍（drills）与复扫都在 Task 4，本任务只有第一遍调用它，`drills` 实参恒为 `emptyList()`。
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
