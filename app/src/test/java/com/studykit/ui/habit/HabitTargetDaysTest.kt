package com.studykit.ui.habit

import com.studykit.data.DemoSeeder
import com.studykit.data.dao.HabitDao
import com.studykit.data.entity.CheckIn
import com.studykit.data.entity.Habit
import com.studykit.data.repository.HabitRepository
import kotlinx.coroutines.flow.Flow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 习惯「目标天数」的证据侧守卫（本轮 A 段）。
 *
 * 钉的是下面几件互相独立的事，每件各挡一种回退（下面五条里只有「schema 侧没动」一条
 * 没有对应的 @Test，为什么测不到写在那一段里，其余每条一个）：
 *  - **Kotlin 侧默认值** = 66（Lally et al. 2010, EJSP 的中位数；范围 18–254）。
 *    「21 天」被原研究团队公开辟谣过，所以它不许再从任何默认值里溜回来。
 *  - **schema 侧没动**：`target_days` 的 `@ColumnInfo` 依旧不带 `defaultValue`（Room 2.6.1
 *    的哨兵是 `[value-unspecified]`），所以改 Kotlin 默认值不改建表 SQL、不改 Room 版本。
 *    这条本轮**测不到**：`@ColumnInfo` 是 `RetentionPolicy.CLASS`，运行时反射看不见它，
 *    而在纯 JVM 单测里跑 Room 处理器 / 比对 schema identity hash 会把 Room 依赖拖进这个模块。
 *    所以它靠 `Habit.kt` 上的注释与本轮 diff（未碰 AppDatabase / 未加列）守，写在这里是为了不让人以为漏写了。
 *  - **创建页的档位**以 66 为中心、含 30/66/100，且默认值真的在档位里 ——
 *    默认值落不进档位的话，进页面时那一格是空的，用户看到的第一个数就是"没选"。
 *  - **写入路径**（`HabitRepository.add` 省略参数时）拿到的确实是同一个 66，
 *    而不是两处各写一份字面量然后某天漂移。
 *  - **演示数据**不再暗示 21 天必成。
 *
 * 注意这里**只管默认值与档位**：空状态那一句由 `HabitEmptyStateCopyTest` 单独钉，
 * 那一句里任何具体天数都不许出现，66 也不行。两边不共用断言。
 */
class HabitTargetDaysTest {

    // ── Kotlin 侧默认值 ─────────────────────────────────────────

    /** Kotlin 侧默认值就是 66；「21 天」被原研究团队辟谣过，不许再从默认值里溜回来 */
    @Test
    fun `默认目标天数换成 66 而不是 21`() {
        assertEquals(66, Habit.DEFAULT_TARGET_DAYS)
        assertEquals(66, Habit(uuid = "u1", name = "阅读", icon = "book").targetDays)
        assertNotEquals(21, Habit(uuid = "u2", name = "阅读", icon = "book").targetDays)
    }

    /** 写入路径省略参数时也必须是同一个 66 —— 默认值有两处就迟早会漂移 */
    @Test
    fun `仓库省略目标天数时用的就是实体那个默认值`() = kotlinx.coroutines.runBlocking {
        val dao = RecordingHabitDao()
        val repository = HabitRepository(dao)
        repository.add(name = "刷题", icon = "🎯")
        assertEquals(66, dao.inserted?.targetDays)
    }

    /** 显式传值时不许被默认值盖掉 */
    @Test
    fun `显式传的目标天数原样写进去`() = kotlinx.coroutines.runBlocking {
        val dao = RecordingHabitDao()
        HabitRepository(dao).add(name = "刷题", icon = "🎯", targetDays = 100)
        assertEquals(100, dao.inserted?.targetDays)
    }

    // ── 创建页档位 ─────────────────────────────────────────────

    @Test
    fun `创建页档位以 66 为中心且含 30 与 100`() {
        assertTrue(HABIT_TARGET_DAY_OPTIONS.containsAll(listOf(30, 66, 100)))
        // 中位数那一档就是默认值，读起来才是"这一行的中间"而不是"某个极端"
        assertEquals(66, HABIT_TARGET_DAY_OPTIONS[HABIT_TARGET_DAY_OPTIONS.size / 2])
        assertEquals(Habit.DEFAULT_TARGET_DAYS, HABIT_TARGET_DAY_OPTIONS[HABIT_TARGET_DAY_OPTIONS.size / 2])
    }

    /** 21 从档位里撤掉：留着它等于把辟谣过的说法重新摆回用户眼前 */
    @Test
    fun `创建页档位里没有 21 也没有比 66 更近的替身`() {
        assertFalse(HABIT_TARGET_DAY_OPTIONS.any { it == 21 })
        assertFalse(HABIT_TARGET_DAY_OPTIONS.any { it in 20..25 })
    }

    /** 档位升序、默认值必须是档位之一（否则新建页进来时那一格选不中） */
    @Test
    fun `档位升序且默认值落在档位里`() {
        assertEquals(HABIT_TARGET_DAY_OPTIONS.sorted(), HABIT_TARGET_DAY_OPTIONS)
        assertTrue(HABIT_TARGET_DAY_OPTIONS.contains(Habit.DEFAULT_TARGET_DAYS))
    }

    // ── 演示数据 ───────────────────────────────────────────────

    @Test
    fun `演示习惯不再暗示 21 天必成`() {
        assertEquals(66, DemoSeeder.DEMO_TARGET_DAYS)
        assertNotEquals(21, DemoSeeder.DEMO_TARGET_DAYS)
    }

    // ── 创建页那句说明 ─────────────────────────────────────────

    /** 该说的还得说：这是个体差异很大的中位数、漏一天不断、补录窗口只有 7 天 */
    @Test
    fun `创建页说明写清个体差异与补录现状`() {
        val hint = HABIT_TARGET_DAYS_HINT
        assertTrue(hint, hint.contains("66"))
        assertTrue(hint, hint.contains("漏一天"))
        assertTrue(hint, hint.contains("补录"))
        // 补录窗口只能照实说是 7 天（`MAKEUP_WINDOW_DAYS`），不许写成"无限/随时"
        assertTrue(hint, hint.contains("$MAKEUP_WINDOW_DAYS 天"))
        assertFalse(hint, hint.contains("随时"))
        assertFalse(hint, hint.contains("无限"))
    }

    /**
     * 这一句**允许**出现天数（它不在空状态那一屏），但它不许声称本轮没有的东西：
     * 断签保护卡额度、自动补签（那需要加列，超出范围）。
     *
     * 「不是期限」也在禁列：同一行在数量型习惯那里标的标签就是「目标期限（天）」，
     * 而它确实是期限（过了那天就完不成 500 ml 了）—— 一句说明不能同时是两个东西。
     */
    @Test
    fun `创建页说明不声称额度或自动补签`() {
        listOf("额度", "自动补", "保护卡", "21", "不是期限").forEach { banned ->
            assertFalse("HABIT_TARGET_DAYS_HINT 里出现了「$banned」", HABIT_TARGET_DAYS_HINT.contains(banned))
        }
    }

    // ── 夹具 ───────────────────────────────────────────────────

    /** 只关心 `insert` 收到什么；其余方法被调到就是测试自己写错了，直接报错响 */
    private class RecordingHabitDao : HabitDao {
        var inserted: Habit? = null

        override suspend fun insert(habit: Habit): Long {
            inserted = habit
            return habit.id
        }

        override fun observeAll(): Flow<List<Habit>> = unused()
        override suspend fun getAll(): List<Habit> = unused()
        override fun observeAllIncludingArchived(): Flow<List<Habit>> = unused()
        override suspend fun update(habit: Habit) = unused()
        override suspend fun archive(id: Long) = unused()
        override suspend fun setArchived(id: Long, archived: Boolean) = unused()
        override suspend fun updateCategory(id: Long, category: String) = unused()
        override suspend fun updateSortOrder(id: Long, sortOrder: Int) = unused()
        override fun observeCheckIns(habitId: Long): Flow<List<CheckIn>> = unused()
        override fun observeAllCheckIns(): Flow<List<CheckIn>> = unused()
        override fun observeCheckInCount(habitId: Long): Flow<Int> = unused()
        override suspend fun countCheckInOn(habitId: Long, date: String): Int = unused()
        override suspend fun findCheckInOn(habitId: Long, date: String): CheckIn? = unused()
        override suspend fun countCheckIns(habitId: Long, fromStr: String, toStr: String): Int = unused()
        override suspend fun updateCheckIn(checkIn: CheckIn) = unused()
        override suspend fun getAllCheckIns(): List<CheckIn> = unused()
        override suspend fun insertCheckIn(checkIn: CheckIn): Long = unused()
        override suspend fun deleteAllCheckIns() = unused()
        override suspend fun deleteAllHabits() = unused()

        private fun unused(): Nothing = error("本测试只走 insert，其他 DAO 方法被调用了")
    }
}
