package com.studykit.ui.habit

import com.studykit.data.entity.Contract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * 达成仪式上那几行纯文案：落款那一行 + 对账日 + 成批时那一行「第 k / 共 n 张」。
 *
 * 钉的是三条诚实线：
 * 1. **对账日读的是 `settle()` 写下的那一天**，不是"此刻" —— 拿 `LocalDate.now()` 顶的话，
 *    第二天再打开 app 补上仪式，落款就会写成一个没发生过的日子；
 * 2. 昵称为空写「未署名」，与卡面同一份措辞，不代填假名；
 * 3. 脏数据（已达成却没有对账时刻）宁可不写日期，也不编一个。
 *
 * 另外按本仓的文案纪律过一遍这两行：它们只陈述"这份契约到期了、账对上了""这一批里排第几"
 * 这几个事实，不许出现奖励、称号、"继续加油"那类过度承诺。
 * （仪式上其余几句是 Composable 里的字面量，挂不上 JVM 断言，而本仓的 Robolectric 渲染守卫
 * 给不出可复现的判定 —— 观察到的事实记在 `ContractRitualLayer` 的 KDoc 里，全量跑时它会红成
 * `UncaughtExceptionsBeforeTest`，红在**别处**残留的未捕获异常上、落在哪条测试头上取决于跑序。
 * 所以"这一页真的摆出来了"那一句留给真机复验。）
 */
class ContractRitualCopyTest {

    private fun settledAt(date: LocalDate): Long =
        date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun contract(
        id: Long = 1L,
        signedBy: String = "小考",
        settledAt: Long? = settledAt(LocalDate.of(2026, 10, 1)),
    ): Contract = Contract(
        id = id,
        uuid = "u-$id",
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

    // ── 一批多张时那一行「第 k / 共 n 张」（裁决 R8：让出口可数）────────
    //
    // 钉的是"这一行什么时候该出现、出现的数字是从哪来的"：对账跑在整个进程寿命里，
    // 攒下一批是可达状态，而每收下一张才出下一张 —— 没有计数时"再点一次"和"这层永远在"
    // 在界面上长得一样。k 取的是这一张在队列里的位置，不是写死的 1（换成写死 1 下面第 3 条就红）。

    /** 只有一张：不报数（仪式"一句多一句都算吵"的纪律还在，单张也没什么可数的） */
    @Test
    fun `a single ritual shows no position line`() {
        assertNull(contractRitualPositionLine(queue = listOf(contract()), currentId = 1L))
        assertNull(contractRitualPositionLine(queue = emptyList(), currentId = 1L))
    }

    /** 一批三张：队列头那一张读作「第 1 / 共 3 张」，n 含正在摆的这一张 */
    @Test
    fun `a batch numbers the head of the queue`() {
        val queue = listOf(contract(id = 7L), contract(id = 8L), contract(id = 9L))
        assertEquals("第 1 / 共 3 张", contractRitualPositionLine(queue = queue, currentId = 7L))
    }

    /**
     * 序号取自这一张在队列里的位置 —— 这是**纯函数的通式**，不是界面上看得到的每一档。
     * 页面永远把队列头那一张交给仪式（`achievedToCelebrate.firstOrNull()`），
     * 而收下会把它从队列里摘掉，所以**屏幕上只会出现「第 1 / 共 n 张」，n 逐次变小**，
     * 下面这两档是把函数写通用之后的覆盖，不是"用户点了收下之后会看到的样子"。
     */
    @Test
    fun `the position follows the place in the queue`() {
        val queue = listOf(contract(id = 7L), contract(id = 8L), contract(id = 9L))
        assertEquals("第 2 / 共 3 张", contractRitualPositionLine(queue = queue, currentId = 8L))
        assertEquals("第 3 / 共 3 张", contractRitualPositionLine(queue = queue, currentId = 9L))
    }

    /**
     * 队列里没有这一张（它正在被 `dismissRitual` 摘掉的路上）：宁可不显示这一行，
     * 也不编一个"第 0 张"或"第 4 / 共 3 张"出来。
     */
    @Test
    fun `a contract outside the queue yields no position line`() {
        val queue = listOf(contract(id = 7L), contract(id = 8L))
        assertNull(contractRitualPositionLine(queue = queue, currentId = 99L))
    }

    /**
     * 这一行只报位置：不发奖励、不给称号、不喊口号；措辞按裁决 R8 给的字面走
     * （「第 k / 共 n 张」），不改写成"还有 n 张"那类同义说法，免得复评时以为换了一件事。
     */
    @Test
    fun `position line states only the position`() {
        val forbidden = listOf("奖励", "获得", "徽章", "称号", "加油", "继续", "已同步", "解锁", "还有")
        val queue = listOf(contract(id = 7L), contract(id = 8L))
        val line = contractRitualPositionLine(queue = queue, currentId = 7L)
        assertNotNull(line)
        forbidden.forEach { phrase ->
            assertFalse("「$line」里不该出现「$phrase」", line!!.contains(phrase))
        }
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
