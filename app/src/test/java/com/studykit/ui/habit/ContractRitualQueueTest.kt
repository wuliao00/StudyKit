package com.studykit.ui.habit

import com.studykit.data.entity.Contract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * 仪式队列的转移函数 [nextRitualQueue]（计划 R1 里"哪几张还等着摆"的唯一落点）。
 *
 * 为什么钉这个函数而不是钉 `ContractsViewModel` 那段 collect：那段跑在 JVM 单测够不着的协程里，
 * 而它一旦写错，症状只有两种 —— "整页仪式一次都没出现过"或"闪一下就没了"，界面上没有任何日志
 * 会说话。三条性质各对应一种失败模式（详见 [nextRitualQueue] 的 KDoc），逐条钉住：
 *
 * 1. **刚挑出来的不被筛掉**：`snapshot` 是**结算前**的那一份，本趟那张在里面还写着 ACTIVE。
 *    并集漏了这一步 = 队列永远空，功能整个不响 —— 本仓第一版就错在这里，症状是
 *    "仪式一次都没出现过，而且没有任何日志会说话"。
 * 2. **行消失后被摘掉**：库里没这一行（撤销了、或「清除学习数据」）就不再摆它。
 * 3. **收下后的那张不回来**：摘掉的就是摘掉了，"库里它还是 ACHIEVED"不许成为把它塞回去的理由。
 *    这条性质就是"放过没有"的全部记法 —— 页面上那份重复的 id 清单已经删掉了（裁决 R6）。
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
}
