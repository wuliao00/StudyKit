package com.studykit.ui.habit

import com.studykit.data.entity.Contract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * 仪式队列的转移函数 [nextRitualQueue]（计划 R1 里"哪几张还等着摆"的唯一落点）。
 *
 * 为什么钉这个函数而不是钉 `ContractsViewModel` 那段 collect：那段跑在 JVM 单测够不着的协程里，
 * 而它一旦写错，症状只有两种 —— "整页仪式一次都没出现过"或"闪一下就没了"，界面上没有任何日志
 * 会说话。四条性质各对应一种失败模式（详见 [nextRitualQueue] 的 KDoc），逐条钉住：
 *
 * 1. **刚挑出来的不被筛掉**：`snapshot` 是**结算前**的那一份，本趟那张在里面还写着 ACTIVE。
 *    并集漏了这一步 = 队列永远空，功能整个不响 —— 本仓第一版就错在这里，症状是
 *    "仪式一次都没出现过，而且没有任何日志会说话"。
 * 2. **行消失后被摘掉**：库里没这一行（撤销了、或「清除学习数据」）就不再摆它。
 * 3. **收下后的那张不回来**：摘掉的就是摘掉了，"库里它还是 ACHIEVED"不许成为把它塞回去的理由。
 *    这条性质就是"放过没有"的全部记法 —— 页面上那份重复的 id 清单已经删掉了（裁决 R6）。
 * 4. **同一张不许进两次**：2026-09 真机走查撞到的重复 enqueue（三张达成占了五个槽、
 *    收下时同一张放两遍）。成因在调用时机那一侧 —— 逐张落库让 Room 把**半新半旧**的快照
 *    又送了一趟，那张还没提交的契约于是被再判一次、再追加一次。这里钉的是它的**形状**。
 */
class ContractRitualQueueTest {

    private val today: LocalDate = LocalDate.of(2026, 10, 1)

    /** 与 [settle] 同一个口径：对账日当天 0 点 —— 落款那一行读的就是它 */
    private val settleStamp: Long =
        today.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun contract(
        id: Long,
        status: String = Contract.STATUS_ACTIVE,
        settledAt: Long? = null,
    ): Contract = Contract(
        id = id,
        uuid = "u-$id",
        habitId = id,
        deadlineEpochDay = today.minusDays(1).toEpochDay(),
        goalCount = 5,
        promiseText = "如果到了【睡前】，我就完成当日打卡。",
        consequenceText = "当天不许刷剧",
        signedBy = "小考",
        signedAtEpochDay = today.minusDays(30).toEpochDay(),
        status = status,
        settledAt = settledAt,
    )

    /** 结算**后**的副本（队列与"本趟挑出来的"两份清单的元素都长这样：带着 settledAt） */
    private fun achieved(id: Long): Contract =
        contract(id = id, status = Contract.STATUS_ACHIEVED, settledAt = settleStamp)

    /** 结算**前**的快照：同一张还写着 ACTIVE、没有对账时刻 */
    private fun beforeSettle(id: Long): Contract = contract(id = id)

    private fun ids(queue: List<Contract>): List<Long> = queue.map { it.id }

    // ── 性质 1：刚挑出来的不被筛掉 ─────────────────────────────

    /**
     * 单张：本趟判成 ACHIEVED，快照里它还是 ACTIVE —— 它必须留在队列里。
     * 把放行名单写成 `snapshot.filter { ACHIEVED }`（少那一步并集）这条立刻红。
     */
    @Test
    fun `the contract just picked survives the pre-settle snapshot`() {
        val queue = nextRitualQueue(
            current = emptyList(),
            justAchieved = listOf(achieved(1L)),
            snapshot = listOf(beforeSettle(1L)),
        )
        assertEquals(listOf(1L), ids(queue))
    }

    /** 一批多张全选中：本趟挑出来的两张都要在，顺序照传入顺序；快照里另外那两行不许被引进来 */
    @Test
    fun `a batch of several just picked contracts all survive`() {
        val queue = nextRitualQueue(
            current = emptyList(),
            justAchieved = listOf(achieved(1L), achieved(3L)),
            snapshot = listOf(
                beforeSettle(1L),
                contract(id = 2L, status = Contract.STATUS_FAILED),
                beforeSettle(3L),
                beforeSettle(4L),
            ),
        )
        // 2 号判的是 FAILED（不进清单）、4 号本趟没挑它：放行名单只用来放行，不用来添加
        assertEquals(listOf(1L, 3L), ids(queue))
    }

    /** 旧队列里那张不许被本趟的追加挤掉：Room 落库后马上重放一趟，覆盖式写法就是在这一步把仪式抹没的 */
    @Test
    fun `appending a second batch does not overwrite the one being shown`() {
        val queue = nextRitualQueue(
            current = listOf(achieved(1L)),
            justAchieved = listOf(achieved(2L)),
            // 第二趟的重放：1 号在库里已经是 ACHIEVED，2 号还写着结算前的 ACTIVE
            snapshot = listOf(achieved(1L), beforeSettle(2L)),
        )
        assertEquals(listOf(1L, 2L), ids(queue))
    }

    /** 什么都没有的一趟（就是 Room 重放那一趟）：队列原样留着，不排序、不重排、不清空 */
    @Test
    fun `an empty batch leaves the queue untouched`() {
        val current = listOf(achieved(1L), achieved(2L))
        val replay = nextRitualQueue(
            current = current,
            justAchieved = emptyList(),
            snapshot = current,
        )
        assertEquals(listOf(1L, 2L), ids(replay))
        val nothing = nextRitualQueue(
            current = emptyList(),
            justAchieved = emptyList(),
            snapshot = emptyList(),
        )
        assertTrue(nothing.isEmpty())
    }

    // ── 性质 2：行消失后被摘掉 ─────────────────────────────────

    /** 「撤销」或「清除学习数据」把那一张抹了：快照里没有这一行，就要从队列里摘出去 */
    @Test
    fun `queued contract whose row disappeared is pruned`() {
        val queue = nextRitualQueue(
            current = listOf(achieved(1L), achieved(2L)),
            justAchieved = emptyList(),
            snapshot = listOf(achieved(2L)),
        )
        assertEquals(listOf(2L), ids(queue))
    }

    /** 行还在但状态不再是 ACHIEVED（恢复来的旧行、或将来允许重签）：同样不许再为它摆仪式 */
    @Test
    fun `queued contract that is no longer achieved is pruned`() {
        val queue = nextRitualQueue(
            current = listOf(achieved(1L), achieved(2L)),
            justAchieved = emptyList(),
            snapshot = listOf(
                achieved(1L),
                contract(id = 2L, status = Contract.STATUS_FAILED, settledAt = settleStamp),
            ),
        )
        assertEquals(listOf(1L), ids(queue))
    }

    /** 摘除不许殃及别人：库里消失的那两张被摘，还在的与本趟刚挑出来的照常留下 */
    @Test
    fun `pruning one survivor does not swallow the rest`() {
        val queue = nextRitualQueue(
            current = listOf(achieved(1L), achieved(2L)),
            justAchieved = listOf(achieved(3L)),
            // 1、2 是上一趟结算并落库过的，3 是本趟刚判的（快照里还写着 ACTIVE）
            snapshot = listOf(achieved(1L), achieved(2L), beforeSettle(3L)),
        )
        assertEquals(listOf(1L, 2L, 3L), ids(queue))
        // 这时 2、3 两行从库里消失了（撤销、「清除学习数据」、或恢复旧备份之后的样子）
        val afterWipe = nextRitualQueue(
            current = queue,
            justAchieved = listOf(achieved(4L)),
            snapshot = listOf(achieved(1L), beforeSettle(4L)),
        )
        assertEquals(listOf(1L, 4L), ids(afterWipe))
    }

    // ── 性质 3：收下后的那张不回来 ─────────────────────────────

    /**
     * 收下 = `dismissRitual` 把它从 current 里摘掉。此后哪怕快照里它仍然写着 ACHIEVED，
     * 它也不许自己长回来 —— 本函数只从 `current + justAchieved` 里挑，快照只用于放行。
     * 页面不再另存一份"哪些放过"（裁决 R6），所以这条性质是唯一的防线，钉死它。
     */
    @Test
    fun `a taken contract never comes back`() {
        val queue = nextRitualQueue(
            current = emptyList(),
            justAchieved = emptyList(),
            snapshot = listOf(achieved(1L)),
        )
        assertTrue(queue.isEmpty())
        // 同一条契约在库里躺着，本趟又结算出别的一张：回来的只有新那张
        val next = nextRitualQueue(
            current = emptyList(),
            justAchieved = listOf(achieved(2L)),
            snapshot = listOf(achieved(1L), beforeSettle(2L)),
        )
        assertEquals(listOf(2L), ids(next))
    }

    /** 队列里那张的**内容**是结算后的副本，不是快照里那一份：换成快照那份 `settledAt` 就没了、落款失去日期 */
    @Test
    fun `the queued item stays the post-settle copy`() {
        val queue = nextRitualQueue(
            current = emptyList(),
            justAchieved = listOf(achieved(1L)),
            snapshot = listOf(beforeSettle(1L)),
        )
        val kept = queue.single()
        assertEquals(Contract.STATUS_ACHIEVED, kept.status)
        assertNotNull(kept.settledAt)
        assertEquals(today, contractSettledDate(kept))
    }

    // ── 性质 4：同一张不许进两次 ───────────────────────────────

    /**
     * 复现 2026-09 真机走查那个缺陷的**形状**（纯函数一侧，不需要 Room、不需要协程）。
     *
     * 第一趟把 A、B 两张都推进队列。缺陷出在落库的时机：`settleDue` 当时是一张一次 `await`，
     * 每提交一张 Room 就失效一次 `contracts` 表，于是 A 已提交、B 还写着 ACTIVE 的**半新半旧**
     * 快照又被送进 collect 一趟 —— [newlySettled] 只认"状态变了没有"，把 B 再判一次，
     * [newlyAchieved] 于是又交出 B 一次。
     *
     * 界面上看到的是「第 1 / 共 5 张」为三张真达成占了五个槽，收下时
     * 早起 → 阅读 → 背单词 → 背单词**又一遍** → 空。
     * 去重按 id 判，不看内容：两张 B 是不同实例，`distinct()` 那种"看着一样就是一条"的写法
     * 在这里挡不住（结算后的副本 `settledAt` 逐毫秒都可能不同）。
     */
    @Test
    fun `a contract reported again by a partially updated emission is queued once`() {
        val afterFirst = nextRitualQueue(
            current = emptyList(),
            justAchieved = listOf(achieved(1L), achieved(2L)),
            snapshot = listOf(beforeSettle(1L), beforeSettle(2L)),
        )
        assertEquals(listOf(1L, 2L), ids(afterFirst))

        // 第二趟：1 号已经落库（快照里写着 ACHIEVED），2 号那一行还没提交，仍是结算前的 ACTIVE，
        // 于是它又被 newlySettled 挑了出来
        val second = nextRitualQueue(
            current = afterFirst,
            justAchieved = listOf(achieved(2L)),
            snapshot = listOf(achieved(1L), beforeSettle(2L)),
        )
        assertEquals("两张达成不许占三个槽", listOf(1L, 2L), ids(second))
        assertEquals(2, second.size)
        assertEquals("队列里出现了重复 id", second.size, ids(second).toSet().size)
    }

    /** 同一趟里那张被报了两遍（同一份快照被送进来两次）：也只许留一个槽 */
    @Test
    fun `the same contract listed twice in one batch still takes one slot`() {
        val queue = nextRitualQueue(
            current = listOf(achieved(1L)),
            justAchieved = listOf(achieved(1L), achieved(1L)),
            snapshot = listOf(beforeSettle(1L)),
        )
        assertEquals(listOf(1L), ids(queue))
    }

    /**
     * 去重只许拦"再追加一次"，不许顺手把排在队列里的那一张筛掉。
     *
     * 这一趟的快照里 2 号还写着 ACTIVE（规则 2 不放行它），而它同时出现在 [justAchieved] 里 ——
     * 如果实现写成"重复的那张直接从结果里去掉"，症状就从"放两遍"变成"闪一下就没了"，
     * 那是更难查的另一个坑。留在原位的那一份（带 `settledAt` 的旧副本）必须活着。
     */
    @Test
    fun `dedup suppresses the second append without evicting the queued copy`() {
        val queuedAgain = achieved(2L)
        val queue = nextRitualQueue(
            current = listOf(achieved(1L), queuedAgain),
            justAchieved = listOf(achieved(2L), achieved(3L)),
            snapshot = listOf(achieved(1L), beforeSettle(2L), beforeSettle(3L)),
        )
        assertEquals(listOf(1L, 2L, 3L), ids(queue))
        // 留下的是队列里原本那一份，不是本趟新造的那一份
        assertSame(queuedAgain, queue.single { it.id == 2L })
    }

    // ── 摘除：R6 之后"放过没有"的唯一记法 ───────────────────────

    /**
     * 只摘点名的那一张，**其余连顺序都不动**。
     *
     * 顺序不是小事：队列顺序就是播放顺序（DAO 按 `deadline_epoch_day ASC`），
     * 写成 `filter{...}.sortedBy{...}` 之类会重排的实现，会让用户先看到不该先看到的那张。
     */
    @Test
    fun dismissing_one_takes_only_that_entry_out_and_keeps_the_order() {
        val queue = listOf(achieved(1L), achieved(2L), achieved(3L))
        assertEquals(listOf(1L, 3L), ids(dismissedQueue(queue, 2L)))
        // 留下的还是原本那两份实例，不是复制出来的
        assertSame(queue[0], dismissedQueue(queue, 2L).first())
    }

    /**
     * id 不在队列里时原样返回 —— 那是"同一帧连点两次收下"或"收下与 Room 重放撞上"。
     * 这时清空队列会把还没摆过的仪式一起抹掉，抛异常更是当场崩。
     */
    @Test
    fun dismissing_an_id_that_is_not_queued_changes_nothing() {
        val queue = listOf(achieved(1L), achieved(2L))
        assertEquals(listOf(1L, 2L), ids(dismissedQueue(queue, 99L)))
        assertEquals(emptyList<Long>(), ids(dismissedQueue(emptyList(), 1L)))
    }

    /**
     * 摘掉的那张**不许自己长回来**。
     *
     * 这条看着显然，但它是 R6 删掉页面那把锁之后**唯一**的防线：库里那一行此后一直是 ACHIEVED，
     * 而 [nextRitualQueue] 的规则 3 会放行"快照里仍是 ACHIEVED"的每一张 ——
     * 换句话说，放行名单**本来是会把它捞回来的**，它不回来只是因为
     * 既不在 `current` 也不在 `justAchieved`。写成"按快照里所有 ACHIEVED 重建队列"的
     * 实现会当场红在这里，症状是"收下一张又冒出来一张"。
     */
    @Test
    fun a_dismissed_contract_does_not_come_back_even_though_the_library_still_says_achieved() {
        val queue = listOf(achieved(1L), achieved(2L))
        val afterTaking = dismissedQueue(queue, 1L)
        assertEquals(listOf(2L), ids(afterTaking))

        // 收下之后 Room 又重放了一趟：快照里 1 号仍然写着 ACHIEVED，而本趟什么都没结算出来
        val snapshot = listOf(achieved(1L), achieved(2L))
        val again = nextRitualQueue(current = afterTaking, justAchieved = emptyList(), snapshot = snapshot)
        assertEquals("放过的那张不许因为库里还是 ACHIEVED 就自己回来", listOf(2L), ids(again))
    }
}
