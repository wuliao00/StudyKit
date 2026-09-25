package com.studykit.ui.habit

import com.studykit.data.entity.Contract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * 达成仪式上那两行纯文案（落款那一行 + 对账日）。
 *
 * 钉的是三条诚实线：
 * 1. **对账日读的是 `settle()` 写下的那一天**，不是"此刻" —— 拿 `LocalDate.now()` 顶的话，
 *    第二天再打开 app 补上仪式，落款就会写成一个没发生过的日子；
 * 2. 昵称为空写「未署名」，与卡面同一份措辞，不代填假名；
 * 3. 脏数据（已达成却没有对账时刻）宁可不写日期，也不编一个。
 *
 * 另外按本仓的文案纪律过一遍落款这一行：它只陈述"这份契约到期了、账对上了"这一个事实，
 * 不许出现奖励、称号、"继续加油"那类过度承诺。（仪式上其余几句是 Composable 里的字面量，
 * 挂不上 JVM 断言，而本仓的 Robolectric 渲染守卫给不出可复现的判定 —— 原因见 task-2 报告，
 * 那一句"这一页真的摆出来了"因此留给真机复验。）
 */
class ContractRitualCopyTest {

    private fun settledAt(date: LocalDate): Long =
        date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun contract(
        signedBy: String = "小考",
        settledAt: Long? = settledAt(LocalDate.of(2026, 10, 1)),
    ): Contract = Contract(
        id = 1L,
        uuid = "u-1",
        habitId = 1L,
        deadlineEpochDay = LocalDate.of(2026, 9, 30).toEpochDay(),
        goalCount = 14,
        promiseText = "如果到了【睡前】，我就完成当日打卡。",
        consequenceText = "",
        signedBy = signedBy,
        signedAtEpochDay = LocalDate.of(2026, 9, 1).toEpochDay(),
        status = Contract.STATUS_ACHIEVED,
        settledAt = settledAt,
    )

    // ── 对账日 ────────────────────────────────────────────────

    /** settle 写的当天 0 点，读回来就是那一天（往返一致，仪式与卡面都靠它） */
    @Test
    fun `settled date reads back the day settle wrote`() {
        val day = LocalDate.of(2026, 10, 1)
        assertEquals(day, contractSettledDate(contract(settledAt = settledAt(day))))
    }

    /** 同一天里晚几个小时的时刻（手改库、或将来改成 wall clock 落库）也读回同一天 */
    @Test
    fun `a settle stamp later in the same day still reads that day`() {
        val later = LocalDate.of(2026, 10, 1).atTime(21, 40)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertEquals(LocalDate.of(2026, 10, 1), contractSettledDate(contract(settledAt = later)))
    }

    /** 未对账（settledAt = null）返回 null，界面这一行不写日期 */
    @Test
    fun `missing settle time yields no date`() {
        assertNull(contractSettledDate(contract(settledAt = null)))
    }

    // ── 落款那一行 ────────────────────────────────────────────

    @Test
    fun `signature line names the signer and the reconcile day`() {
        assertEquals("署名：小考 · 对账于 2026-10-01", contractRitualSignatureLine(contract()))
    }

    /** 空昵称不代填假名 —— 与卡面 `ContractCard` 那一行同一个兜底 */
    @Test
    fun `blank signer shows 未署名`() {
        assertEquals("署名：未署名 · 对账于 2026-10-01", contractRitualSignatureLine(contract(signedBy = "")))
        assertEquals(
            "署名：未署名 · 对账于 2026-10-01",
            contractRitualSignatureLine(contract(signedBy = "   ")),
        )
    }

    /** 脏数据只署名、不编日期（"对账于"三个字不许凭空出现） */
    @Test
    fun `dirty data drops the date instead of inventing one`() {
        val line = contractRitualSignatureLine(contract(settledAt = null))
        assertEquals("署名：小考", line)
        assertFalse(line.contains("对账于"))
    }

    /**
     * 对账日不许写成"今天"：这里故意用六年前的一个日子，
     * 一旦实现换成 `LocalDate.now()`，这条立刻红。
     */
    @Test
    fun `reconcile day is the settled day, not today`() {
        val longAgo = LocalDate.of(2020, 3, 5)
        val line = contractRitualSignatureLine(contract(settledAt = settledAt(longAgo)))
        assertEquals("署名：小考 · 对账于 2020-03-05", line)
        assertFalse(line.contains(LocalDate.now().toString()))
    }

    // ── 文案纪律 ──────────────────────────────────────────────

    /** 落款这一行只陈述事实：不发奖励、不给称号、不喊口号 */
    @Test
    fun `signature line makes no promise beyond the fact`() {
        val forbidden = listOf("奖励", "获得", "徽章", "称号", "加油", "继续", "已同步", "解锁")
        listOf(contract(), contract(signedBy = ""), contract(settledAt = null)).forEach { c ->
            val line = contractRitualSignatureLine(c)
            forbidden.forEach { phrase ->
                assertFalse("「$line」里不该出现「$phrase」", line.contains(phrase))
            }
        }
    }
}
