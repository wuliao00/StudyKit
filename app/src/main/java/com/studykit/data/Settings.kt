package com.studykit.data

import androidx.compose.runtime.Immutable
import com.studykit.data.memory.ReviewStrictness
import java.time.LocalDate

/** 主题选择。[SYSTEM] 沿用系统的 `isSystemInDarkTheme()`，另两档是用户硬指定。 */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * 玻璃材质档位。开关与浓度合成一枚控件，而不是「开关 + 滑块」两件：
 * 三档里 OFF 就是关，SOFT/STRONG 的差别在 tint 不透明度，用户不需要先开再调。
 */
enum class GlassLevel { OFF, SOFT, STRONG }

/**
 * 用户的本地设置 —— 全仓唯一一份，落库为 `app_settings` 的键值行（见 [AppSetting]）。
 *
 * ## 每一条都必须有消费点
 *
 * 设置页最容易长成一堆"存了但没人读"的开关，所以这一版**砍掉**了四项本可顺手加的：
 * 头像色（界面上没有头像位）、每日题数目标（刷题页没有"今日目标"这一行）、
 * 字号缩放与列表密度（会重开全部 30+ 屏的像素预算，而那份预算是 M1 逐屏真机验收过的）。
 *
 * 保留项的消费点：[themeMode] → `StudyKitTheme`；[glass] → `ui/material/Glass.kt`；
 * [reduceMotion] → 彩带 / 错峰入场 / 按压缩放；[nickname] → `HabitExporter.buildShareText` 抬头；
 * [dailyWordGoal] → 学习首页今日进度；[reminderEveryHours] → `ReminderScheduler`；
 * [examEpochDay] → 学习首页倒计时行；[lastDictFailure] → 设置页诊断段（只是显示，不参与逻辑）。
 *
 * 所有字段都有默认值，[AppSettings] 的无参构造就是"从没进过设置页"时的行为，
 * 因此**首装即使一行都没写进库也不会改变现有观感**（玻璃默认 SOFT 是唯一例外，那是要给用户看见的新东西）。
 */
@Immutable
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val glass: GlassLevel = GlassLevel.SOFT,
    val reduceMotion: Boolean = false,
    val nickname: String = "",
    val dailyWordGoal: Int = DEFAULT_WORD_GOAL,
    val reminderEveryHours: Int = DEFAULT_REMINDER_HOURS,
    /** 考试日，存 `LocalDate.toEpochDay()`；0 表示未设置（epochDay 0 = 1970-01-01，不可能被当考试日） */
    val examEpochDay: Long = 0L,
    /**
     * 复习严格度。AUTO = 由考试日期反推（越远的目标越宽松），
     * 其余三档是把目标准确率钉死。默认 AUTO：不填考试日的用户不该被一个写死的数字对待。
     */
    val reviewStrictness: ReviewStrictness = ReviewStrictness.AUTO,
    /** 贪吃蛇历史最高分（批次一）。只增不减：读档时取 max，防止手改备份把它清零 */
    val snakeBest: Int = 0,
    /** 上一次词库下载失败的原文，只为诊断展示，不做任何判断 */
    val lastDictFailure: String = "",
) {

    /** 主题三态落到"这次构图用不用深色"。[systemDark] 由调用方传 `isSystemInDarkTheme()`。 */
    fun resolveDark(systemDark: Boolean): Boolean = when (themeMode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    /** 考试日；未设置返回 null。设置页与首页共用，避免两处各写一遍 `0L` 的语义 */
    val examDate: LocalDate?
        get() = if (examEpochDay == 0L) null else runCatching { LocalDate.ofEpochDay(examEpochDay) }.getOrNull()

    fun toMap(): Map<String, String> = linkedMapOf(
        KEY_THEME to themeMode.name,
        KEY_GLASS to glass.name,
        KEY_REDUCE_MOTION to reduceMotion.toString(),
        KEY_NICKNAME to nickname,
        KEY_WORD_GOAL to dailyWordGoal.toString(),
        KEY_REMINDER_HOURS to reminderEveryHours.toString(),
        KEY_EXAM_DAY to examEpochDay.toString(),
        KEY_REVIEW_STRICTNESS to reviewStrictness.name,
        KEY_SNAKE_BEST to snakeBest.toString(),
        KEY_DICT_FAILURE to lastDictFailure,
    )

    companion object {

        const val KEY_THEME = "theme_mode"
        const val KEY_GLASS = "glass_level"
        const val KEY_REDUCE_MOTION = "reduce_motion"
        const val KEY_NICKNAME = "nickname"
        const val KEY_WORD_GOAL = "daily_word_goal"
        const val KEY_REMINDER_HOURS = "reminder_every_hours"
        const val KEY_EXAM_DAY = "exam_epoch_day"
        const val KEY_REVIEW_STRICTNESS = "review_strictness"
        const val KEY_SNAKE_BEST = "snake_best"
        const val KEY_DICT_FAILURE = "last_dict_failure"

        const val DEFAULT_WORD_GOAL = 20
        const val DEFAULT_REMINDER_HOURS = 6

        /** 昵称上限：分享文本里它只占一行抬头，再长就只是把正文挤下去 */
        const val NICKNAME_MAX = 24

        /** WorkManager 的周期下限是 15 分钟，这里按小时收在 1..72（三天一次也算提醒） */
        val HOURS_RANGE = 1..72
        val WORD_GOAL_RANGE = 1..500

        /** 能接受考试日的区间：2000-01-01 至 2100-01-01 */
        val EXAM_DAY_RANGE = LocalDate.of(2000, 1, 1).toEpochDay()..LocalDate.of(2100, 1, 1).toEpochDay()

        /**
         * 键值行 → 类型化设置。**永不抛异常**：库里的值可能被旧版本写过、被手改过的备份文件恢复进来，
         * 任何一种解释不通都退回默认值，而不是让整个 App 在启动时崩掉。
         * 未识别的键忽略，缺键取默认。
         */
        fun fromMap(map: Map<String, String>): AppSettings {
            val defaults = AppSettings()
            return AppSettings(
                themeMode = map[KEY_THEME]?.let { raw -> ThemeMode.values().firstOrNull { it.name == raw } }
                    ?: defaults.themeMode,
                glass = map[KEY_GLASS]?.let { raw -> GlassLevel.values().firstOrNull { it.name == raw } }
                    ?: defaults.glass,
                reduceMotion = map[KEY_REDUCE_MOTION]?.toBooleanStrictOrNull() ?: defaults.reduceMotion,
                nickname = map[KEY_NICKNAME]?.trim()?.take(NICKNAME_MAX) ?: defaults.nickname,
                dailyWordGoal = map[KEY_WORD_GOAL]?.toIntOrNull()?.takeIf { it in WORD_GOAL_RANGE }
                    ?: defaults.dailyWordGoal,
                reminderEveryHours = map[KEY_REMINDER_HOURS]?.toIntOrNull()?.takeIf { it in HOURS_RANGE }
                    ?: defaults.reminderEveryHours,
                examEpochDay = map[KEY_EXAM_DAY]?.toLongOrNull()?.takeIf { it != 0L && it in EXAM_DAY_RANGE }
                    ?: defaults.examEpochDay,
                reviewStrictness = map[KEY_REVIEW_STRICTNESS]
                    ?.let { raw -> ReviewStrictness.entries.firstOrNull { it.name == raw } }
                    ?: defaults.reviewStrictness,
                snakeBest = map[KEY_SNAKE_BEST]?.toIntOrNull()?.coerceAtLeast(0) ?: defaults.snakeBest,
                // 诊断文本原样留着，包括空串；只在超长时掐掉，免得一次异常堆栈把设置页撑坏
                lastDictFailure = map[KEY_DICT_FAILURE]?.take(400) ?: defaults.lastDictFailure,
            )
        }
    }
}
