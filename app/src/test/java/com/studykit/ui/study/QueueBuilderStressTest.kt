package com.studykit.ui.study

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.system.measureTimeMillis

/**
 * 规模与查找预算闸（v3.0-C Task 8）：500 题那一档**查表次数有闭式上界**、**不丢不重**、**一成上限真咬得住**、**结果确定**。
 *
 * 文档 §② L1265 那条「500 due 构建 <50ms」**不写成硬断言**：那需要真机，本仓现在没有设备，
 * 把 JVM 读数写成毫秒闸门就是拿"这台机器单核跑得快"冒充"真机上也够快"。本机钉得住的是偏差 D5
 * 用有界窗口换来的那样东西 —— **查表次数的闭式上界**（那五项住在 [QueueBuilder] 的类 KDoc 里，
 * 唯一事实源，本文件只按它推、不复述成新口径）。耗时逐条打印出来，报告与 Task 9 收口时写的发版说明里
 * 都要写明它是 **JVM 单核读数**、不是真机；那条 500ms 只是十倍余量的防退化闸，不是质量门。
 *
 * 三份 500 题夹具，各钉一件事（期望值全部手推后实跑核对）：
 *  - [fiveHundred]：`id / 10` 连号分簇 ⇒ 51 个簇号（簇 0 = id 1..9 那 9 连号，簇 1..49 各 10 连号，
 *    簇 50 = 只剩 id 500 一张），初序（urgency 递减 = id 升序）里**相邻同簇对 8 + 49×9 = 449 对**。
 *    计划原稿这里写的是 `id % 50`：那个簇号让相邻 id 分属不同簇，于是初序里**一对违规都没有**，
 *    整条"压力测试"只证明了"不崩"，换位项与插池项都停在 0。
 *  - [oneClusterFiveHundred]：500 张**全同一簇** ⇒ 499 对相邻全违规，而换位窗口里的候选也与左邻同簇
 *    （`0.9 >= tauLo` ⇒ 一只合格候选都点不出来）⇒ 修复只剩插池一条路 ⇒ 一成上限才会被咬到。
 *    它是 `QueueBuilderAdjacentTest.拉前条数受上限约束等于条数的一成` 那份 10 题夹具的 500 题放大版：
 *    同一条推法，只是数字大 50 倍。
 *  - 第三份在「真挪动过卡」那条里现场构造（每张卡自成一簇、只让 id 400 落进 id 7 的簇）：初序零违规，
 *    但那一对隔 393 格 ⇒ drills 一定真挪 ⇒ 复扫那一轮与落点检查都被变活。
 *
 * **派发那一版"每簇 20 连号"的推法被实跑否掉了**（那一步的账：20 > lookahead 12 ⇒ 每簇头部 7 对换位够不到
 * ⇒ 只能插池 ⇒ 50 张用尽）：同一份夹具接上 100 张缓冲池，实测 `pulled = 17`、`unresolved = 0`，
 * 上限 50 根本没被咬到；池从 100 张换成 500 张还是 17（瓶颈不在池的多少）。收尾代理补了一次**临时探针**（未入库）
 * 把 `maxRepairRounds` 从 4 压到 1：`pulled` 与 `unresolved` 一个都不动（17 / 0），只有读数从 3509 降到 2993
 * ⇒ 这一版原先写在下面的解释（"第 1..3 轮里已插进去的池卡成了换位的合格候选"）**同样不成立**，跟着推演一起撤。
 * 实测只支持到这一句：那一趟前扫自己就把初序那 474 对违规拆干净了，压根走不到上限；再往下的机制没有测，别照因果复述。
 * 上限那道闸因此改由 [oneClusterFiveHundred] 钉：那里换位**永久**无路（同簇 ⇒ 候选 `0.9 >= tauLo`）、而池卡永远合格
 * ⇒ 只剩插池一条路，直到 50 张用尽（`pulled = 50`、`queue = 550`、`unresolved = 449` 三个精确值实测钉死，抬高上限就红）。
 * 至于每张池卡**具体插在哪几格**、它与违规位置的先后关系 —— 没有单独取证，属机制细节，跟上面那句同一个规矩：别照因果复述。
 *
 * 上界公式里的 `lookahead` / `maxRepairRounds` 读的是注入的 policy（那九个数由 `InterleavePolicyTest`
 * 用字面量钉着，漂不了），而被上界管的 [QueueBuilder] 内部预算（`PULL_CAP_PERCENT` / `DRILL_MOVE_BUDGET`）
 * 在断言里**全是字面量**。
 *
 * ⚠️ 上面这些**精确读数**（`7407` / `1998` —— 由 `assertEquals` 钉死；以及逐条 `trace` 打进报告与发版说明的
 * `13516` / `256907`）都是 `LexicalInterleave.DEFAULT` 那组标定值的函数。**动它们就会把这几条读红的标定格**至少有：
 * `lookahead = 12` 与 `maxRepairRounds = 4`（窗口宽度 × 轮数）、`tauHi = 0.60` 与 `tauLo = 0.15`（前者就是
 * [QueueBuilder.violates] 的谓词、后者决定换位候选与池卡合不合格 —— 把 `tauHi` 抬过夹具的 0.9 就一个违规都判不出来，
 * **扫得多的那三条读数（7407 / 13516 / 256907）因此塌到只剩 report 两遍加一轮前扫，也就是下面那句 1497 的下沿**；
 * 这句**不含 `1998`**：far 那份夹具的初序本来就零违规，抬 `tauHi` 改不动它的四个加项 —— 前扫 499（一路走到队尾、
 * 一处违规都没有）、落点 2（`placeBeside` 两侧都问，`clashes` 在 0.60 与"抬过 0.9"两档下都是假 ⇒ 一次都没少问）、
 * 复扫 499（追加它的那道门是 `relocated > 0`，与分值无关）、读数 998 ⇒ 四笔照旧 = 1998。它押的是下一句那两个数）、
 * 以及 `1998` 那条还要算 `gapLo/gapHi`（它们决定那对题算不算"已经隔开"，判成带内就一步都不挪，而复扫只认真挪过卡）。
 * 这份名单说的是"重标定这几个会读到红"，**不是**"只有这几个能动这四个数"。
 * `drillLo/drillHi` 与 `topK = 8` 都不在这四个数里：它们只被 [QueueBuilder.confusablePairs] 读，而本文件的夹具
 * 要么传空映射、要么把映射当输入直接喂给 [QueueBuilder.build]，**一条都不经 `confusablePairs`** ——
 * [QueueBuilder.build] 自己从不读 `topK`（读它的是 `confusablePairs`），所以本文件「两次构建逐元素相等」那些
 * `drillPairs` 读数吃的是**手搓映射**，拧 `topK` 它不动。`topK` 真正决定的字面量读数住在
 * [QueueBuilderDrillTest] 的「每卡的混淆伙伴数不超过 topK」与「伙伴数超上界时截掉的是最不像的那几个」，
 * 生产路径上再经装配层 `planQuestionQueue` 间接显形。
 * 语义包将来重新标定上面那几个数，**相应那批字面量会按设计变红**（`7407`/`1998` 直接红，`13516`/`256907` 只是
 * 打印值随之改变、报告须重取）—— 那是在提醒"上界口径要跟着重推"，不是实现退化了：一次标定 PR 把它们读红，
 * 别当成回归去 revert，要按新标定重推这些数。
 */
class QueueBuilderStressTest {

    private val p = LexicalInterleave.DEFAULT

    /**
     * 换位项 `n × (lookahead + 1) × maxRepairRounds` 唯一的家（口径住在 [QueueBuilder] 类 KDoc，这里只是
     * 给这条本 lane "一处计算一处家"的规矩一个落点，不复述成新口径）。复扫项那条是**另一项**，在各条 bound 里单独加、
     * 不走这个 helper —— 它通用那一格的位置数底与换位项同（住在类 KDoc 那句「违反位置数 × (lookahead + 1)」里），
     * 本文件各条 bound 代进去的才是下面那段说清的 `n`。
     *
     * 类 KDoc 把位置数写成 `≤ n + pullCap`，这里、上面那句提到的复扫项、以及插池项那个表达式代的都是 `n` —— **不是抄漏了**：钉上界的三个用例
     * （共五处表达式）里，三处（空池 500 档 / 空池全同簇档 / 真挪动 far）喂的就是 `emptyList()` 池 ⇒ `insertBuffer`
     * 连门都开不了、`q` 不会变长；剩下两处都在「全不合格池」那条，而它自己把 `pulled = 0` 断言死了 ⇒ 位置数就是 `n`。
     * 真把 `q` 拉到 550 的只有「拉前受一成上限」，另一条池开着的用例是「两次构建逐元素相等」—— 这两条**一条上界都不钉**，
     * 只钉 `pulled`/`queue`/`unresolved` 那类字面量 ⇒ 代 `n` 不会漏算任何一条在库断言（哪天给它们补上界，得连 `pullCap` 一起代）。
     */
    private fun swapTerm(n: Int) = n * (p.lookahead + 1) * p.maxRepairRounds

    /** 500 条：urgency 递减 ⇒ `build` 的排序键还原成 id 升序；簇按 [clusterWidth] 连号给。 */
    private fun itemsOf(clusterWidth: Int): List<QueueItem> =
        (1L..500L).map { id -> QueueItem(id, (501 - id).toFloat(), subject = "C${id / clusterWidth}") }

    /** 宽度 10 那份：449 对违规、窗口永远够得着下一簇（⇒ 换位够用、`pulled` 停在 0）。上界那条的前半、真挪动那条与确定性那条用它。 */
    private fun fiveHundred(): Pair<List<QueueItem>, FixtureSim> {
        val items = itemsOf(10)
        return items to FixtureSim(items.associate { it.id to (it.id / 10).toInt() })
    }

    /**
     * 全同簇那份：换位**永久**无路（窗口里的候选全与左邻同簇）。
     * 于是它一身两职 —— 配 100 张池卡时只剩插池一条路 ⇒ 能被走到一成上限；
     * 配空池时每个违规位置都把它那一只窗口问到尽头（尾部那几格被 `swapInWindow` 的 `q.lastIndex` 截短，`i = 498` 那一只空到一眼都不问；
     * 逐笔账在下面那条用例的推导里）
     * ⇒ 上界那条用例的"问到尽头"那一档（换位项本身没走满，账在 [QueueBuilder] 类 KDoc）。
     */
    private fun oneClusterFiveHundred(): Pair<List<QueueItem>, FixtureSim> {
        val items = (1L..500L).map { id -> QueueItem(id, (501 - id).toFloat(), subject = "A") }
        return items to FixtureSim(items.associate { it.id to 1 })
    }

    /** 100 张缓冲池卡：`FixtureSim` 不认识 900..999 ⇒ 对任何邻居都是 0f，即**永远合格**的缓冲卡。 */
    private fun bufferPool(): List<QueueItem> = (900L..999L).map { QueueItem(it, 0f, subject = "P$it") }

    private fun linkedOf(vararg entries: Pair<Long, List<Long>>): Map<Long, List<Long>> {
        val m = java.util.LinkedHashMap<Long, List<Long>>()
        for (e in entries) m[e.first] = e.second
        return m
    }

    /** 键 15/16/17 在默认容量 16 的表里落第 15/0/1 桶 ⇒ 迭代序是 16, 17, 15，**不是**升序。 */
    private fun bucketedOf(vararg entries: Pair<Long, List<Long>>): Map<Long, List<Long>> {
        val m = java.util.HashMap<Long, List<Long>>()
        for (e in entries) m[e.first] = e.second
        return m
    }

    /**
     * 实测读数打到 stdout：Task 8 的产物之一就是一份如实的规模记录（报告抄的是这几行，Task 9 写发版说明时从报告里取）。
     * 这几行**不参与判定** —— 闸门是它们上面那句闭式上界断言；删掉这个调用，测试行为一字不变。
     */
    private fun trace(label: String, sim: FixtureSim, plan: QueuePlan) {
        println("T8[$label] lookups=${sim.lookups} pulled=${plan.pulled} queue=${plan.queue.size} unresolved=${plan.unresolved.size}")
    }

    @Test fun `五百题空池构建的查找次数在闭式上界内`() {
        val (items, sim) = fiveHundred()
        val plan = QueueBuilder.build(items, sim, emptyList(), emptyMap(), p)
        // 上界住在 [QueueBuilder] 的类 KDoc 里（那里是唯一事实源，两处各写一份就是一份债）。
        // KDoc 是**五项**；本条夹具传 `confusable = emptyMap()` 且池是 `emptyList()`，所以其中三项为 0 ——
        // 这件事必须写在账上，否则读代码的人会以为下面三项就是全部上界。
        // 下面那五行是**本条夹具那一档**的取值（空池 ⇒ 队列没被拉长 ⇒ 位置数与最终条数都是 `n`）；通用底
        // `违反位置数 ≤ n + pullCap`、`读数 ≤ 2 × (n + pullCap − 1)` 只住在类 KDoc，五处上界表达式为什么都能代 `n` 见 [swapTerm] 那段：
        //  - 换位项：n × (lookahead + 1) × maxRepairRounds（每个位置 1 次相邻判定 + 窗口内至多 lookahead 次候选判定）
        //  - 复扫项：n × (lookahead + 1)，仅当 drills **真挪动过卡**才跑 ⇒ 空映射时为 0
        //  - 插池项：违反位置数 × maxRepairRounds × |pool| × 2 ⇒ **空池时为 0**（firstOrNull 一次都不问）
        //  - drills 落点项：DRILL_MOVE_BUDGET × 2 ⇒ 空映射时为 0
        //  - 读数项：report 读的是**走完全部趟之后**那只队列，每一对相邻读两遍（maxAdjSim 一遍、violates 一遍）
        //    ⇒ 通用口径是 `2 × (最终条数 − 1)`，底和上面三项同那一格（那笔账住在类 KDoc）；本条空池 ⇒ 代的就是 `2 × (n − 1)` = 998
        // 注：T3 评审把「每轮 2n+4 步预算」连同换位后回退一格一起删了，所以原稿那个公式已经作废。
        val swap = swapTerm(items.size)                                       // 500 × 13 × 4 = 26000
        val poolTerm = 0                                                      // 空池 ⇒ 这一项为 0（不是"没写就不算"）
        val reportTerm = 2 * (items.size - 1)                                 // 998
        val bound = swap + poolTerm + reportTerm
        assertTrue("lookups=${sim.lookups} 超上界 $bound", sim.lookups <= bound)
        // 这一句方向相反，是**下沿**：它拦的是"这条其实在空跑"。推得死的部分只有两处必然发生的读数 ——
        // report 那两遍整列相邻读数（2 × 499 = 998）加第一遍至少一轮整列前扫（`while (i < q.size - 1)`
        // 一路走到队尾、中途没有 break ⇒ 499 次相邻判定）= 1497。实跑 2921。
        // 读数若是 0，只可能是替身不计数或 build 提前返回，那时上界那句会跟着一起绿 ⇒ 需要这一句挡着。
        assertTrue("读数低到 ${sim.lookups}：连 report 两遍整列读数加一轮前扫都没走完", sim.lookups >= reportTerm + items.size - 1)
        assertEquals(500, plan.queue.size)                                    // 空池 ⇒ 一条不多，⛔ 字面量
        assertEquals(items.map { it.id }.sorted(), plan.queue.map { it.id }.sorted())   // 不丢不重不换血
        trace("空池 500", sim, plan)

        // 同一句上界，换一份**把窗口问到尽头**的夹具：全同簇 + 空池 ⇒ 每个违规位置都把它那一只窗口问到尽头
        // （尾部被 `q.lastIndex` 截短：`i = 487..497` 各问 11..1 眼、`i = 498` 那一只窗口是空的、一眼都不问 —— 逐笔账就在下面那段推导里）；
        // 候选全部 `0.9 >= tauLo` ⇒ 一只都点不中，而插池那半空池一次都不问。
        // 这一档才是上界真正在管的那个形状：上面那份混合簇夹具**挡不住"撤掉窗口界"这一刀**（实跑取证：
        // 把 [QueueBuilder.swapInWindow] 的右端 `minOf(i + 1 + lookahead, q.lastIndex)` 撤成 `q.lastIndex`
        // 之后，上面那份夹具六个用例全绿 —— 异簇卡挨得近，有界无界都只问得出那几眼，读数没跨过 26998；
        // 红的是 `QueueBuilderAdjacentTest.换位只往后看 lookahead 个位置`（`expected:<11> but was:<1>`），
        // 那是**位移**那一半，不是查表次数这一半。所以上界必须有一份"窗口里永远点不出合格候选"的夹具陪着钉）。
        //
        // ⛔ 精确读数逐笔推（不是上界）：
        //  - 相邻判定：`while (i < q.size - 1)` ⇒ i = 0..498 ⇒ 499 次；
        //  - 换位候选：i = 0..486 各 12 次（窗口 `i+2 .. i+13`）= 5844；i = 487..498 各 `498 - i` 次
        //    （右端被 `q.lastIndex = 499` 截住）= 11 + 10 + … + 0 = 66 ⇒ 合计 5910；
        //  - 第一遍 `changed` 为假 ⇒ 第 0 轮走完就早停 ⇒ 后面三轮一次都不跑；
        //  - drills 与落点：空映射 ⇒ 0；插池：空池 ⇒ `firstOrNull` 一次都不问 ⇒ 0；
        //  - 读数：maxAdjSim 499 + unresolved 499 = 998。
        // 合计 499 + 5910 + 998 = **7407**。
        // 这一句同时是两刀的靶子（都实跑过）：
        //  - 撤掉窗口界（变异 M2）⇒ 每个位置的候选数从 12 变成"直到队尾"，上界那一句先红：
        //    `lookups=125748 超上界 26998`；
        //  - 删掉 `if (!changed) break` 那道早停（变异 M9）⇒ 多跑三轮完整前扫（+3 × 6409 = 19227）⇒
        //    实跑 `expected:<7407> but was:<26634>`，而 26634 **仍然不超上界** 26998 ⇒ 上界那一句照样绿，
        //    只有这一句精确读数看得见那一刀。这就是"钉上界"之外还要钉精确读数的理由。
        val (denseItems, denseSim) = oneClusterFiveHundred()
        val densePlan = QueueBuilder.build(denseItems, denseSim, emptyList(), emptyMap(), p)
        val denseBound = swapTerm(denseItems.size) + 2 * (denseItems.size - 1)
        assertTrue("lookups=${denseSim.lookups} 超上界 $denseBound", denseSim.lookups <= denseBound)
        assertEquals(7407, denseSim.lookups)
        assertEquals(500, densePlan.queue.size)                               // 空池：上限开着也没东西可插
        assertEquals(499, densePlan.unresolved.size)                          // 500 张全像 ⇒ 一处都修不掉
        trace("空池 500 全同簇", denseSim, densePlan)
    }

    @Test fun `五百题配一只全不合格的池时插池扫描项也在上界内`() {
        // 这条钉的是 D5 里那句「插池项不能省」。最坏情形是一只**全都不合格**的池：
        // 每个违规位置都试插、每条候选都要问邻居、每次都问不出结果，于是整只池被扫完。
        // 用簇型替身造不出"对所有邻居都不合格"的池卡（簇结构下相似是成片的），
        // 所以这里用 `constant` 那把尺子 —— 除自反外一律 0.9：既没有可换的，也没有合格的缓冲。
        val items = itemsOf(10)                       // subject 在这条里不参与打分（簇映射是空的），只为可读性留着
        val poolSize = 500
        val pool = (2000L until 2000L + poolSize).map { QueueItem(it, 0f, subject = "P") }
        val sim = FixtureSim(emptyMap(), constant = 0.9f)
        val plan = QueueBuilder.build(items, sim, pool, emptyMap(), p)
        val swap = swapTerm(items.size)
        val poolTerm = items.size * p.maxRepairRounds * poolSize * 2
        val bound = swap + poolTerm + 2 * (items.size - 1)                     // 26000 + 2000000 + 998
        assertTrue("lookups=${sim.lookups} 超上界 $bound", sim.lookups <= bound)
        // 上面那句是**天花板**，下面那句才是"这一项不许从账上省掉"：KDoc 里那句「n=500、|pool|=500 时
        // 约为第一项的 80 倍」要能被观测到，靠的就是这一句。实跑 256907（第 0 轮把整只池扫完就早停 ——
        // 一处都没改动过，`changed` 为假），而省掉插池项的上界只有 26998 ⇒ 差一个数量级。
        // 杀的变异（真跑）：把 `insertBuffer` 首行改成 `return false`（= 插池那半整段没了）⇒ 整只池一次都没被扫，
        // 读数掉回 7407（变异 M4 实跑红在这一句：`实测 7407 没有高出『省掉插池项』那个上界 26998`）。
        val boundWithoutPoolTerm = swap + 2 * (items.size - 1)
        assertTrue(
            "实测 ${sim.lookups} 没有高出『省掉插池项』那个上界 $boundWithoutPoolTerm ⇒ 插池扫描没走进账（五项里的第三项白写了）",
            sim.lookups > boundWithoutPoolTerm,
        )
        assertEquals(0, plan.pulled)                  // 全不合格 ⇒ 一张都插不进来。⛔ 池有 500 张、cap 开着（50），这句红得了
        assertEquals(500, plan.queue.size)
        assertTrue(plan.unresolved.isNotEmpty())      // 499 个位置全违规，一张都插不进 ⇒ 必须如实上报
        assertEquals(499, plan.unresolved.size)       // ⛔ n - 1 的字面量：一处都没修掉
        trace("全不合格池", sim, plan)
    }

    @Test fun `真挪动过卡时复扫项与落点项也进账`() {
        // 前两条的 `confusable` 都是空映射 ⇒ KDoc 五项里的第二、四项恒为 0，
        // 于是"这两项存在"这件事本身没有任何用例钉着：把复扫整段删掉、或让 drills 不落点，
        // 前两条照样绿。这一条给一个**真会挪动**的对子，把两项都变活。
        val (items, sim) = fiveHundred()
        // 宽度 10 的簇结构下"同簇"意味着最多隔 9 格，挪 3 格必然撞同簇邻居 ⇒ 落点一定被拒 ⇒
        // `relocated` 永远是 0 ⇒ 复扫那轮根本不跑。所以这一条不能复用 fiveHundred 的簇，
        // 要自己造一对"远同簇"的题：让 id 400 落进 id 7 的簇，其余每簇只一张。
        val far = FixtureSim(items.associate { it.id to (if (it.id == 400L) 7L else it.id).toInt() })
        val confusable = mapOf(7L to listOf(400L))          // a=7 < b=400 ⇒ 规范化方向会处理
        val plan = QueueBuilder.build(items, far, emptyList(), confusable, p)
        val bound = swapTerm(items.size) + items.size * (p.lookahead + 1) +       // 换位项 + 复扫项（复扫 = n×(lookahead+1)×1 轮）
            32 * 2 +                                                              // drills 落点项（预算封顶）
            2 * (items.size - 1)                                                  // 读数项
        assertTrue("lookups=${far.lookups} 超上界 $bound", far.lookups <= bound)
        // ⛔ 逐笔手推的**精确**读数（不是上界）。这份夹具除 400/7 那一对之外每张卡自成一簇 ⇒ 初序零违规：
        //  - 第一遍：499 次相邻判定（一路走到队尾，一处违规都没有）⇒ `changed` 为假 ⇒ 第 0 轮后就早停 = 499
        //  - drills 落点：`placeBeside` 的 `clashes` 左右邻各查一次（第一侧 0f 判假 ⇒ 短路进第二侧）= 2
        //  - 复扫：relocated = 1 ⇒ 又一轮完整前扫 = 499
        //  - 读数：maxAdjSim 一遍 499 + unresolved 一遍 499 = 998
        // 合计 499 + 2 + 499 + 998 = **1998**。这一句同时钉着"复扫那一轮真跑了"与"落点真查了两个邻居"：
        // 删掉 build 里那句复扫调用 ⇒ 掉到 1499（变异 M3 实跑 `expected:<1998> but was:<1499>`）；
        // 把 `placeBeside` 落点检查的**左邻**那一半短路掉 ⇒ 掉到 1997（变异 M6 实跑 `expected:<1998> but was:<1997>`）；
        // 删掉每轮末尾 `if (!changed) break` 那道早停 ⇒ 第一遍多跑三轮 = 掉到 3495（变异 M9 实跑
        // `expected:<1998> but was:<3495>`，而那一刀在上界那一句下是绿的：3495 < 33562）。
        assertEquals(1998, far.lookups)
        // 初序就是 id 升序（urgency 递减）：7 在下标 6、400 在下标 399，隔 393 格 > gapHi ⇒ 真挪；
        // 落点两侧是 id 9 与 id 10（各属自己那一簇）⇒ 不撞 ⇒ relocated = 1 ⇒ 复扫那一轮跑过。
        assertEquals(9, plan.queue.indexOfFirst { it.id == 400L })
        assertEquals(3, plan.drillPairs.first().gap)         // 从 393 格挪到 gapLo = 3
        assertEquals(1, plan.drillPairs.size)
        assertEquals(500, plan.queue.size)
        assertTrue(plan.unresolved.isEmpty())                 // 簇间全 0，本来就没有违规
        trace("真挪动（far）", far, plan)
    }

    @Test fun `五百题构建不丢不重且拉前受一成上限`() {
        // ⛔ 这条钉的是文档 L1264「pulled 上限 = due 数 ×10%」在 500 那一档**真的咬得住**。
        // 派发前的裁定：期望值必须是**精确的 50**，不能写 `pulled <= 50` —— 那份断言在 pulled = 0 或 1 时
        // 不可能红，等于拿一条"上限生效"的标题骗过闸门（这条 lane 第三次踩同一个形状）。
        //
        // 手推（[oneClusterFiveHundred]：500 张全同一簇；池 100 张是 `FixtureSim` 不认识的 id ⇒ 永远合格）：
        //  - 初序 = id 升序 ⇒ 499 对相邻全违规；
        //  - 换位那半**永久**失败：窗口 `i+2 .. i+1+lookahead` 里的候选与 `q[i]` 同簇 ⇒ `0.9 >= tauLo` 全拒；
        //  - 插池那半每次都成功：池卡与左右邻都是 0f < tauLo，而这一刀下能不能插**只看分值**
        //    （`insertBuffer` 压根不读 subject，池卡那串 "P900" 之类的学科名在这儿不参与任何判定）；
        //  - 扫描指针每两格插一张（i=0 插、i=2 插、…）⇒ `pulled` 每两步加一，直到
        //    cap = 500 × 10 / 100 = 50 **用尽**（用尽时指针才走到下标 100，后面还有 450 张同簇卡）；
        //  - 用尽之后换位仍然无路 ⇒ 下标 100..548 那 449 对如实进 `unresolved`；第 1 轮一整轮空转 ⇒ 早停。
        // 池里有 100 张而只拉了 50 张 ⇒ 拦下它的是**上限**，不是"池空了"（那一句正是变异 M1 的靶子）。
        val (items, sim) = oneClusterFiveHundred()
        val pool = bufferPool()
        val plan = QueueBuilder.build(items, sim, pool, emptyMap(), p)
        assertEquals(50, plan.pulled)                          // ⛔ 500 的一成，字面量
        assertEquals(550, plan.queue.size)                     // ⛔ 500 + 50
        // 不丢：500 条 due 一条不少。不重：去重后仍然 550 条。不换血：多出来的恰好是池的**前** 50 张
        // （`firstOrNull` 走清单序 ⇒ 900、901、…、949；后 50 张 950..999 该留在池里）。
        assertEquals(items.map { it.id }.toSet() + (900L..949L), plan.queue.map { it.id }.toSet())
        assertEquals(plan.queue.size, plan.queue.map { it.id }.distinct().size)
        // 形状逐格推得死：前 100 格是"一张 due 一张缓冲"交替，下标 100 起是原序 51..500 一段没被动过。
        assertEquals(listOf(1L, 900L, 2L, 901L, 3L, 902L), plan.queue.take(6).map { it.id })
        assertEquals((51L..500L).toList(), plan.queue.drop(100).map { it.id })
        // 上限用尽之后没人再管那 449 对：如实报出来，而不是假装干净。
        assertEquals(449, plan.unresolved.size)                // ⛔ 548 - 100 + 1，逐格推得死
        assertEquals(100, plan.unresolved.first())             // 违规段从缓冲带结束那一格起
        trace("拉前上限", sim, plan)
        // 杀的变异（真跑）：`PULL_CAP_PERCENT` 从 10 抬到 20 ⇒ cap = 100 = 整只池 ⇒ 实跑
        // `expected:<50> but was:<100>`（变异 M1），且**只有这一条红**：其余五条在那一刀下都不碰上限那条线。
        // 反过来把插池整段废掉（变异 M4）则红在 `expected:<50> but was:<0>` —— 两句一起把"50 这个数字
        // 既不是因为没东西可插、也不是因为还没到上限"钉住。
    }

    @Test fun `五百题两次构建逐元素相等`() {
        // 确定性这一档要**不空转**：同一份逻辑输入换三种呈现，结果必须逐格相同。
        //  ① `due` 反着给：排序键是 `(urgency desc, id asc)` 而这份夹具的 urgency 全不相等 ⇒ 初序仍是 id 升序。
        //     杀的变异是"初序直接吃调用方给的清单序"（`due.sortedWith(...)` 换成 `due.toMutableList()`）——
        //     变异 M8 实跑**只红这一条**（全类其余五条都按 id 升序喂 `due`，那一刀在它们身上给不出差别），
        //     红在末序那一句：b 那份是 `asReversed()` 喂进去的 ⇒ 整列从队尾开始修，与 a 逐格不同。
        //  ② 同一份混淆映射换呈现：正着插的 `LinkedHashMap` vs `HashMap`（15/16/17 落第 15/0/1 桶
        //     ⇒ 迭代序是 16, 17, 15）。杀的变异是 [QueueBuilder.applyDrills] 那句 `TreeMap(confusable)`
        //     归一化被删（跟着调用方的桶序走）。
        //  ③ 伙伴清单 `17 -> [455, 237]` 故意倒着给：杀的变异是 `partners.sorted()` 被删。
        // ②那一刀实跑显形在**两处**（变异 M5 的取证）：先红在末序那一句 ——
        // `[..., 105, 6, 216, 17, 2, 455, 237, 18, ...]` 变成 `[..., 105, 6, 17, 216, 2, 455, 237, 18, ...]`
        // （这份夹具的 drill 是真挪得动的：216 落在 anchor 16 后面第三格，处理序一变它就相对 17 换了位），
        // 随后 `drillPairs` 的记录序两档整列不同 ⇒ 记录序那四句字面量是第二道锁，读起来不像"差不多"。
        // 变异 M7（`partners.sorted()` 被删）只红在记录序那两句，末序那一句在它下面是绿的 ——
        // 实跑 `expected:<[105, 216, 237, 455]> but was:<[105, 216, 455, 237]>`：伙伴清单的序换的只是记录序。
        // 末段三句是防"白绿"的：末序真的不等于初序、两份呈现各自都不丢不重、`drillPairs` 真记了 4 条 ——
        // 否则"两次相等"可能只是"什么都没做然后相等"。
        val (items, sim) = fiveHundred()
        val pool = bufferPool()
        val forward = linkedOf(15L to listOf(105L), 16L to listOf(216L), 17L to listOf(455L, 237L))
        val a = QueueBuilder.build(items, sim, pool, forward, p)
        val (reversedItems, freshSim) = fiveHundred()
        val bucketed = bucketedOf(17L to listOf(455L, 237L), 16L to listOf(216L), 15L to listOf(105L))
        val b = QueueBuilder.build(reversedItems.asReversed(), freshSim, pool, bucketed, p)
        assertEquals(a.queue.map { it.id }, b.queue.map { it.id })
        assertEquals(a.pulled, b.pulled)
        assertEquals(listOf(15L, 16L, 17L, 17L), a.drillPairs.map { it.aId })   // ⛔ aId 升序，与桶序无关
        assertEquals(listOf(15L, 16L, 17L, 17L), b.drillPairs.map { it.aId })   // HashMap 那一侧同一条字面量
        assertEquals(listOf(105L, 216L, 237L, 455L), a.drillPairs.map { it.bId }) // ⛔ 伙伴按 id 升序，不是清单序
        assertEquals(listOf(105L, 216L, 237L, 455L), b.drillPairs.map { it.bId })
        assertEquals(4, a.drillPairs.size)
        assertEquals(a.drillPairs.map { it.gap }, b.drillPairs.map { it.gap })
        assertNotEquals(items.map { it.id }, a.queue.map { it.id })             // 末序确实被动过（449 对违规不可能原样）
        assertEquals(a.queue.size, a.queue.map { it.id }.distinct().size)       // 不丢不重（两份呈现各自成立）
        assertEquals(b.queue.size, b.queue.map { it.id }.distinct().size)
        trace("确定性", sim, a)
    }

    @Test fun `实测耗时如实打印（不当闸门）`() {
        // 文档那条 50ms 要真机才谈得上，本仓没有设备 ⇒ 这里只打印，报告与 Task 9 写的发版说明里都要写明它是
        // **JVM 单核读数**（真机三层基准另开计划，本任务不冒充它）。500ms 这道闸是十倍余量的**防退化**门
        // （把一次 sim 变成一次磁盘 IO 那种量级才响），不是质量门：它绿不代表真机上够快，它红代表实现退化了一个量级。
        val (items, sim) = fiveHundred()
        repeat(5) { QueueBuilder.build(items, sim, emptyList(), emptyMap(), p) }   // 预热
        val fresh = fiveHundred()
        val ms = measureTimeMillis { QueueBuilder.build(fresh.first, fresh.second, emptyList(), emptyMap(), p) }
        println("QueueBuilder 500 items: ${ms}ms (lookups=${fresh.second.lookups})")
        assertTrue("构建耗时 $ms ms 超过防退化闸 500ms", ms < 500)
    }
}
