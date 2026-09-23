package com.studykit.ui.habit

import com.studykit.data.entity.Habit
import java.time.LocalTime

/**
 * 时段分类（v2.4 批次四）的纯逻辑层：展示名映射、小时 → 时段槽、列表排序。
 * 零 Compose、零 Android —— JVM 单测钉死排序规则，UI 只消费结果。
 * 论文口径（Gardner 2021）：绑例程（"睡前"）比绑钟点（"22:30"）更易成习惯，
 * 所以"当前时段的组排最前"，而不是按提醒时刻插值。
 */

/** Habit.CATEGORIES 的展示名：ANY=任意，其余按一天的先后；未知值退回"任意" */
fun categoryLabel(category: String): String = when (category) {
    Habit.CATEGORY_ANY -> "任意"
    "MORNING" -> "早晨"
    "FORENOON" -> "上午"
    "NOON" -> "中午"
    "AFTERNOON" -> "下午"
    "EVENING" -> "傍晚"
    "NIGHT" -> "晚上"
    else -> "任意"
}

/** category 在 [Habit.CATEGORIES] 中的序；未知值排到最后而不是抛（旧库可能有脏值） */
internal fun categoryOrderIndex(category: String): Int =
    Habit.CATEGORIES.indexOf(category).let { index -> if (index < 0) Habit.CATEGORIES.size else index }

/**
 * 小时 → 时段槽（纯函数）：5-10 早晨、10-13 上午、13-16 中午、16-19 下午、
 * 19-23 傍晚、其余（23-5）晚上。返回 Habit.CATEGORIES 里的键（不含 ANY）。
 */
fun slotForHour(hour: Int): String = when (hour) {
    in 5..9 -> "MORNING"
    in 10..12 -> "FORENOON"
    in 13..15 -> "NOON"
    in 16..18 -> "AFTERNOON"
    in 19..22 -> "EVENING"
    else -> "NIGHT"
}

/**
 * 习惯列表排序（纯函数）：未归档在前，按 category 分组；**当前时段的组排最前**，
 * 其余组间按 [Habit.CATEGORIES] 顺序，组内按 sortOrder 升序；归档的不出现。
 * [now] 由调用方注入（测试传固定值，生产传 LocalTime.now()）。
 */
fun sortedForList(items: List<HabitItemUi>, now: LocalTime = LocalTime.now()): List<HabitItemUi> {
    val currentSlot = slotForHour(hour = now.hour)
    return items
        .filter { item -> !item.habit.archived }
        .sortedWith(
            comparator = compareBy(
                { item -> item.habit.category != currentSlot },
                { item -> categoryOrderIndex(category = item.habit.category) },
                { item -> item.habit.sortOrder },
            ),
        )
}
