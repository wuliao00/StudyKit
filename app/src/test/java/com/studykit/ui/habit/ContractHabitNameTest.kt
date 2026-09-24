package com.studykit.ui.habit

import com.studykit.data.entity.Habit
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 契约卡的习惯名（纯函数）。
 *
 * 这里钉的是"归档 ≠ 删除"：习惯被归档后从 `observeAll()` 里消失，
 * 但打卡记录和契约都还在。原来拿那份"看不见的列表"反查，
 * 一条活契约就被标成了「已删除的习惯」—— 而本应用根本没有"删除单个习惯"这个动作。
 */
class ContractHabitNameTest {

    private fun habit(
        id: Long,
        name: String = "习惯$id",
        archived: Boolean = false,
    ): Habit = Habit(
        id = id,
        uuid = "uuid-$id",
        name = name,
        icon = "📚",
        archived = archived,
    )

    @Test
    fun `active habit shows its name`() {
        assertEquals("背单词", contractHabitName(1L, listOf(habit(1L, "背单词"))))
    }

    /** 归档的要说"已归档"，不能说"已删除" —— 数据还在，进度还在跑 */
    @Test
    fun `archived habit is labelled archived not deleted`() {
        val name = contractHabitName(7L, listOf(habit(7L, "喝水", archived = true)))
        assertEquals("喝水（已归档）", name)
        assertEquals(false, name.contains("删除"))
    }

    /** 指向不存在的习惯（清库残留 / 旧备份恢复出来的契约）才用兜底文案 */
    @Test
    fun `dangling habit id falls back to deleted label`() {
        assertEquals("已删除的习惯", contractHabitName(99L, listOf(habit(1L))))
        assertEquals("已删除的习惯", contractHabitName(1L, emptyList()))
    }

    /** 同 id 只可能有一条（主键），但列表里混着归档项时不能挑错人 */
    @Test
    fun `resolves the right habit among mixed ones`() {
        val all = listOf(habit(1L, "早起"), habit(2L, "阅读", archived = true), habit(3L, "跑步"))
        assertEquals("阅读（已归档）", contractHabitName(2L, all))
        assertEquals("跑步", contractHabitName(3L, all))
    }
}
