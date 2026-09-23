package com.studykit.ui.habit

import com.studykit.data.entity.Contract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * 对账纯函数（v2.4 批次五）。判错了全是静默的：未到期被提前判死、已对账的被重算翻案，
 * 用户都只能在"达成/未达成"药丸上看到结果 —— 边界全部钉死在这里。
 */
class ContractSettleTest {

    private val today: LocalDate = LocalDate.of(2026, 10, 1)

    private fun contract(
        status: String = Contract.STATUS_ACTIVE,
        deadlineEpochDay: Long = today.minusDays(1).toEpochDay(),
        goalCount: Int = 5,
        settledAt: Long? = null,
    ): Contract = Contract(
        uuid = "test-uuid",
        habitId = 1L,
        deadlineEpochDay = deadlineEpochDay,
        goalCount = goalCount,
        promiseText = "如果到了【睡前】，我就完成当日打卡。",
        consequenceText = "当天不许刷剧",
        signedBy = "小考",
        signedAtEpochDay = today.minusDays(30).toEpochDay(),
        status = status,
        settledAt = settledAt,
    )

    // ── 未到期不动 ─────────────────────────────────────────────

    /** deadline == today：还差最后一天，不许提前对账 */
    @Test
    fun `contract due exactly today is not settled`() {
        val c = contract(deadlineEpochDay = today.toEpochDay())
        assertSame(c, settle(c, completedCount = 0, today = today))
    }

    /** deadline 在未来：更不动 */
    @Test
    fun `future contract is not settled`() {
        val c = contract(deadlineEpochDay = today.plusDays(7).toEpochDay())
        assertSame(c, settle(c, completedCount = 0, today = today))
        assertEquals(Contract.STATUS_ACTIVE, settle(c, completedCount = 0, today = today).status)
    }

    // ── 达成 / 未达成 ─────────────────────────────────────────

    /** 恰好达标：count == goalCount 即 ACHIEVED（>= 不是 >） */
    @Test
    fun `exact goal count settles as achieved`() {
        val settled = settle(contract(), completedCount = 5, today = today)
        assertEquals(Contract.STATUS_ACHIEVED, settled.status)
        assertTrue(settled.settledAt != null)
    }

    /** 超额也算达成 */
    @Test
    fun `over goal count settles as achieved`() {
        val settled = settle(contract(), completedCount = 9, today = today)
        assertEquals(Contract.STATUS_ACHIEVED, settled.status)
    }

    /** 差一次：FAILED，其余字段（承诺、后果、签名）原样保留 */
    @Test
    fun `one short of goal settles as failed`() {
        val original = contract()
        val settled = settle(original, completedCount = 4, today = today)
        assertEquals(Contract.STATUS_FAILED, settled.status)
        assertEquals(original.promiseText, settled.promiseText)
        assertEquals(original.consequenceText, settled.consequenceText)
        assertEquals(original.signedBy, settled.signedBy)
    }

    /** goalCount 为 0 的脏数据：完成 0 次也满足 >=，判 ACHIEVED 而不是崩 */
    @Test
    fun `zero goal with zero count settles as achieved`() {
        val settled = settle(contract(goalCount = 0), completedCount = 0, today = today)
        assertEquals(Contract.STATUS_ACHIEVED, settled.status)
    }

    // ── 已对账不再重算 ────────────────────────────────────────

    /** ACHIEVED/FAILED 是落库的历史事实：事后打卡变少也不许翻案 */
    @Test
    fun `already settled contracts are never re-evaluated`() {
        val achieved = contract(status = Contract.STATUS_ACHIEVED, settledAt = 0L)
        assertSame(achieved, settle(achieved, completedCount = 0, today = today))

        val failed = contract(status = Contract.STATUS_FAILED, settledAt = 0L)
        assertSame(failed, settle(failed, completedCount = 99, today = today))
    }

    // ── settledAt 口径 ────────────────────────────────────────

    /** settledAt = today 当天 0 点的 epochMilli（系统时区），不是对账那一刻的 wall clock */
    @Test
    fun `settledAt anchors to midnight of the settle day`() {
        val settled = settle(contract(), completedCount = 5, today = today)
        val expected = today.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertEquals(expected, settled.settledAt)
        assertFalse(settled.settledAt == null)
    }

    /** 未对账的契约 settledAt 保持 null */
    @Test
    fun `unsettled contract keeps null settledAt`() {
        assertNull(settle(contract(deadlineEpochDay = today.toEpochDay()), completedCount = 5, today = today).settledAt)
    }
}
