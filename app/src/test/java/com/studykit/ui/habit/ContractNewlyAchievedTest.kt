package com.studykit.ui.habit

import com.studykit.data.entity.Contract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 「本次结算里挑出达成那几张」的纯函数（计划 R1 的落点，达成仪式唯一的触发条件）。
 *
 * 仪式绑的是**本次 settleDue 真的写成了 ACHIEVED**，不是"看到一张达成的契约" —— 后者会让用户
 * 每次进契约页、每次列表重放都庆祝一遍（那是噪音；对账本身跑在整个进程寿命里，见
 * `ContractsViewModel.achievedToCelebrate`）。这条线一旦错，要么吵、要么整个功能不响，而两种错在界面上
 * 都只表现为"放了/没放"，所以全部判据钉在这里测。
 *
 * 四件事是验收点：未到期不选、到期但判 FAILED 不选、已经结算过的不选（防重）、一次多张全选中。
 */
class ContractNewlyAchievedTest {

    private val today: LocalDate = LocalDate.of(2026, 10, 1)

    /** 默认给一张"昨天到期、要求 5 次"的进行中契约 —— 四个用例只改各自要改的那一个字段 */
    private fun contract(
        id: Long,
        status: String = Contract.STATUS_ACTIVE,
        deadlineEpochDay: Long = today.minusDays(1).toEpochDay(),
        goalCount: Int = 5,
    ): Contract = Contract(
        id = id,
        uuid = "u-$id",
        habitId = 1L,
        deadlineEpochDay = deadlineEpochDay,
        goalCount = goalCount,
        promiseText = "如果到了【睡前】，我就完成当日打卡。",
        consequenceText = "当天不许刷剧",
        signedBy = "小考",
        signedAtEpochDay = today.minusDays(30).toEpochDay(),
        status = status,
    )

    /** 与 `settleDue` 同一串调用：先挑"本次被结算的"，再从里面筛 ACHIEVED */
    private fun achievedOf(all: List<Contract>, counts: Map<Long, Int>): List<Contract> =
        newlyAchieved(newlySettled(all, counts, today))

    private fun ids(list: List<Contract>): List<Long> = list.map { it.id }

    // ── 未到期不选 ────────────────────────────────────────────

    /** deadline == today 还差最后一天，不许提前对账，更不许提前庆祝 */
    @Test
    fun `contract due exactly today is not picked`() {
        val c = contract(id = 1L, deadlineEpochDay = today.toEpochDay())
        assertTrue(achievedOf(listOf(c), counts = mapOf(1L to 99)).isEmpty())
        assertTrue(newlySettled(listOf(c), completedCounts = mapOf(1L to 99), today = today).isEmpty())
    }

    /** deadline 在未来：查都不该查（调用方不给计数），给了也不选 */
    @Test
    fun `future contract is not picked`() {
        val c = contract(id = 1L, deadlineEpochDay = today.plusDays(7).toEpochDay())
        assertTrue(achievedOf(listOf(c), counts = mapOf(1L to 99)).isEmpty())
        assertTrue(achievedOf(listOf(c), counts = emptyMap()).isEmpty())
    }

    // ── 到期但判 FAILED 不选（计划 R2）─────────────────────────

    /** 差一次没达标：这一张**要落库**（写的是 FAILED），但仪式不许放 */
    @Test
    fun `due contract judged failed is written but not picked`() {
        val c = contract(id = 1L)
        val settled = newlySettled(listOf(c), completedCounts = mapOf(1L to 4), today = today)
        assertEquals(listOf(1L), ids(settled))
        assertEquals(Contract.STATUS_FAILED, settled.single().status)
        assertTrue(achievedOf(listOf(c), counts = mapOf(1L to 4)).isEmpty())
    }

    /** 零次也要判成 FAILED：不能因为"什么都没干"就跳过落库 */
    @Test
    fun `zero count contract is judged failed, not skipped`() {
        val settled = newlySettled(listOf(contract(id = 1L)), completedCounts = mapOf(1L to 0), today = today)
        assertEquals(Contract.STATUS_FAILED, settled.single().status)
    }

    // ── 已经结算过的不选（防重）────────────────────────────────

    /** 库里已经是 ACHIEVED 的契约：settle 不再动它，于是永远进不了这份清单 */
    @Test
    fun `already achieved contract is not picked again`() {
        val achieved = contract(id = 1L, status = Contract.STATUS_ACHIEVED)
        assertTrue(achievedOf(listOf(achieved), counts = mapOf(1L to 9)).isEmpty())
        assertTrue(newlySettled(listOf(achieved), completedCounts = mapOf(1L to 9), today = today).isEmpty())
    }

    /** 库里已经是 FAILED 的同样不动（事后打卡变多也不追溯翻案） */
    @Test
    fun `already failed contract is not picked again`() {
        val failed = contract(id = 1L, status = Contract.STATUS_FAILED)
        assertTrue(achievedOf(listOf(failed), counts = mapOf(1L to 99)).isEmpty())
    }

    /**
     * 这条就是"不需要是否看过这一列"的**全部**理由：结算写完，Room 会立刻用同一批数据重放一次
     * collect，第二趟拿到的已经是判完的契约 —— 挑不出东西，所以仪式不会被第二次触发，
     * 也不会被第二趟**抹掉**（队列只由 `dismissRitual` 摘，「收下」与系统返回都走那里，
     * 见 ContractsViewModel.achievedToCelebrate）。
     */
    @Test
    fun `re-running over the just settled rows picks nothing`() {
        val due = listOf(contract(id = 1L), contract(id = 2L, goalCount = 3))
        val counts = mapOf(1L to 5, 2L to 3)
        val firstRun = achievedOf(due, counts)
        assertEquals(listOf(1L, 2L), ids(firstRun))
        // 第二趟：列表已经是判完的样子，同样的计数再走一遍
        assertTrue(achievedOf(firstRun, counts).isEmpty())
    }

    // ── 一次多张全选中 ────────────────────────────────────────

    /**
     * 一个早上打开 app、三张到期两张达标：达标的那两张全要选出来，**按传入顺序**
     * （DAO 已按截止日升序，页面就按这个顺序一张张放仪式）。判 FAILED 的与未到期的不掺进来。
     */
    @Test
    fun `several achieved in one run are all picked`() {
        val all = listOf(
            contract(id = 1L),                                        // 达标 → 选
            contract(id = 2L, status = Contract.STATUS_FAILED),        // 早就判完 → 不选
            contract(id = 3L, goalCount = 12),                         // 差两次 → 不选
            contract(id = 4L, deadlineEpochDay = today.toEpochDay()),  // 今天才到期 → 不选
            contract(id = 5L, goalCount = 1),                          // 超额达标 → 选
        )
        val counts = mapOf(1L to 5, 3L to 10, 4L to 99, 5L to 4)
        assertEquals(listOf(1L, 5L), ids(achievedOf(all, counts)))
        // 落库清单要同时含那一张 FAILED 的，否则界面上一切正常、库里却永远写着 ACTIVE
        assertEquals(listOf(1L, 3L, 5L), ids(newlySettled(all, counts, today)))
    }

    /** 恰好等于 goalCount 即达成（判据是 >= 而不是 >），仪式照放 */
    @Test
    fun `exact goal count is picked`() {
        assertEquals(listOf(1L), ids(achievedOf(listOf(contract(id = 1L)), counts = mapOf(1L to 5))))
    }

    // ── 没给计数就不判 ────────────────────────────────────────

    /**
     * 计数缺失 = "这张没被判"，**不是**"这张打了 0 次"：拿 0 顶上去会把一张没查的契约判成 FAILED，
     * 那是凭空造一个判定出来。调用方靠这条把查询范围限制在"到期 + 进行中"。
     */
    @Test
    fun `missing count is treated as not judged rather than zero`() {
        val due = contract(id = 1L)
        assertTrue(newlySettled(listOf(due), completedCounts = emptyMap(), today = today).isEmpty())
        // 同一批里只给了一张的计数：给了的照常判，没给的一张都不许动
        val judged = contract(id = 2L)
        assertEquals(listOf(2L), ids(achievedOf(listOf(due, judged), counts = mapOf(2L to 7))))
        assertEquals(listOf(2L), ids(newlySettled(listOf(due, judged), completedCounts = mapOf(2L to 7), today = today)))
    }

    /** 空批次：不选也不炸 */
    @Test
    fun `empty batch picks nothing`() {
        assertTrue(newlySettled(emptyList(), completedCounts = emptyMap(), today = today).isEmpty())
        assertTrue(newlyAchieved(emptyList()).isEmpty())
    }
}
