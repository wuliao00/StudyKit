package com.studykit.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "habits")
data class Habit(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String,
    @ColumnInfo(name = "syncStatus", defaultValue = "0") val syncStatus: Int = 0,
    val name: String,
    val icon: String,
    /**
     * 目标天数（天数型习惯）。默认 66 —— 见 [DEFAULT_TARGET_DAYS]。
     *
     * 这里刻意**不写** `@ColumnInfo(defaultValue = ...)`：Room 只看注解，不看 Kotlin 属性默认值，
     * 所以这个默认值是纯 Kotlin 侧的表单/构造初值，不进建表 SQL。
     * 加一个 `defaultValue = "66"` 等于改 schema（建表语句 + identity hash + 迁移），本轮不付这个代价。
     * 后来人请勿"顺手补上"它。
     */
    @ColumnInfo(name = "target_days") val targetDays: Int = DEFAULT_TARGET_DAYS,
    @ColumnInfo(name = "start_date") val startDate: Long = System.currentTimeMillis(),
    val archived: Boolean = false,
    /** 数量型目标总量（>0 即数量型习惯，如背 50 词；0 表示天数型） */
    @ColumnInfo(name = "target_count", defaultValue = "0") val targetCount: Double = 0.0,
    /** 数量型单位（如「个」「公里」「ml」） */
    @ColumnInfo(name = "unit", defaultValue = "''") val unit: String = "",
    /** 默认打卡文案：一键打卡时自动带入打卡备注 */
    @ColumnInfo(name = "default_text", defaultValue = "''") val defaultText: String = "",
    /**
     * 时段分类（v2.4 批次四）：ANY/MORNING/FORENOON/NOON/AFTERNOON/EVENING/NIGHT。
     * 依据 Gardner 2021 —— 绑例程（"睡前"）比绑钟点（"22:30"）更易成习惯，
     * 所以分类是"一天的哪一段"而不是提醒时刻。
     */
    @ColumnInfo(name = "category", defaultValue = "'ANY'") val category: String = CATEGORY_ANY,
    /** 手动排序（长按拖动）；小值在前 */
    @ColumnInfo(name = "sort_order", defaultValue = "0") val sortOrder: Int = 0,
    /** v2.7 执行意图整句（app.docx 模块4 P0；when 维度复用 category，Gollwitzer & Sheeran 2006） */
    @ColumnInfo(name = "if_then", defaultValue = "''") val ifThen: String = "",
) {
    companion object {
        const val CATEGORY_ANY = "ANY"
        val CATEGORIES = listOf(CATEGORY_ANY, "MORNING", "FORENOON", "NOON", "AFTERNOON", "EVENING", "NIGHT")

        /**
         * 默认目标天数 = **66 天**（Lally et al. 2010, EJSP：习惯自动性中位数约 66 天，
         * 个体差异 18–254 天）。
         *
         * 之前这里是 21：那个说法被原研究团队自己公开辟过谣，而 21 天没成形的用户会把它读成
         * 自己的失败。同一个研究另一条同样重要的结论是**偶尔漏一天不毁掉养成**，
         * 所以配套的口径是 5/7 弹性达标（见 `weekCompliance`），不是不断链。
         *
         * 默认值只有这一处：创建页初值、`HabitRepository.add` 形参、演示数据都引用它。
         */
        const val DEFAULT_TARGET_DAYS = 66
    }
}
