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
 * [examEpochDay] → 学习首页倒计时行；[lastDictFailure] → 设置页诊断段（只是显示，不参与逻辑）；
 * [recallBeforeGrade] → `ui/study/RecallGate.kt` 的闸门判定（`CardStudyScreen` 读它，
 * 关掉之后滑动与三档自评**完全**退回旧行为，不留半截拦截）；
 * [recallGateHintSeen] → 同一条闸门的「第一次拦下你」说明（`CardStudyScreen` 读、
 * 点「知道了」经 `StudyViewModel.markRecallGateHintSeen` 写，每次安装只出现一次）；
 * [flipTipSeen] → 翻面时那条 RECALL_FIRST 贴士（`CardStudyScreen` 读、首次翻面经
 * `StudyViewModel.markFlipTipSeen` 写，每次安装只出现一次；口径与 recallGateHintSeen 一致）；
 * [interleavingEnabled] → 题库轮次的取数排序（`StudyViewModel.startQuiz` / `startMixedQuiz`
 * 读它传进 `QuestionOrdering.interleaveBySubject`，**全仓只有那一处判定**；关掉就退回取数原序）；
 * [schedulingKernel] → `ui/study/StudyViewModel.gradeCard` 与 `CardStudyScreen` 的按钮预览（消费点落在 A-T9）；
 * [confidenceEnabled] → 翻面/提交前的信心自评条（`CardStudyScreen` / `QuizScreen`，采集 UI 在计划 B Task 11-12 落地；A-T9 只接通了 conf 传进来时的落库与侧信道，目前生产侧恒 null）。
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
    /**
     * 已同意的免责声明版本。0 = 从没同意过（首启必弹）。
     * **带版本号**：以后改免责文案就把 [com.studykit.data.Disclaimer.TEXT_VERSION] 加一，
     * 老用户下次启动会再看到一次 —— 否则改了条款而没人看到，改了等于没改。
     */
    val disclaimerVersion: Int = 0,
    /** 首启引导是否看过（同意免责声明之后走一遍）。只影响"要不要再自动放一次" */
    val onboardingSeen: Boolean = false,
    /** 贪吃蛇历史最高分（批次一）。只增不减：读档时取 max，防止手改备份把它清零 */
    val snakeBest: Int = 0,
    /**
     * 「一天」从几点开始（0~5，默认 0）：凌晨 0~4 点打卡算**前一天**。
     * 修真实缺陷 —— 连续天数原本写死自然日，熬夜用户天天被断签
     * （Lally 2010：漏一天并不毁掉习惯的自动性，断签清零才不科学）。
     */
    val dayBoundaryHour: Int = DEFAULT_DAY_BOUNDARY_HOUR,
    /**
     * 允许补打卡（近 7 天内的空格）。补的记 `check_ins.is_makeup`，
     * **计入连续与累计（不断签），但在全局日历的日详情卡上带「补」字药丸单独标识**。
     *
     * 注意别写成"日历与统计里都单独标识"：统计侧（累计打卡天数、热力图）本来就该把补卡算进去，
     * 也没有逐条列打卡明细的界面，所以那里没有可依附的标识位。
     */
    val makeupAllowed: Boolean = true,
    /** 限制打卡时段（默认关）：开启后只允许窗口内打卡 */
    val restrictCheckIn: Boolean = false,
    /** 窗口起止（当天分钟数，默认 08:00–22:00） */
    val restrictStartMin: Int = DEFAULT_RESTRICT_START_MIN,
    val restrictEndMin: Int = DEFAULT_RESTRICT_END_MIN,
    /** 上一次词库下载失败的原文，只为诊断展示，不做任何判断 */
    val lastDictFailure: String = "",
    /**
     * 检索优先闸门（v2.5 §3.1，默认**开**）：卡片没翻面（= 答案还没出现在屏幕上）时，
     * 左右滑不结算、而是把这一滑变成一次翻面；卡下三档自评同步置灰。
     *
     * 关掉它 = 完全退回旧行为（不翻面也能直接评价，与墨墨一致），
     * 这条承诺的全部内容就是"不留半截拦截"，所以判定只有一处（`RecallGate.kt`），
     * 三个入口（滑动 / 两颗按钮 / 三档自评）都从它拿结论。
     */
    val recallBeforeGrade: Boolean = true,
    /**
     * 闸门第一次真正拦下一次滑动时那条说明，看过就没有（**每次安装一次**，
     * 不是每天一次、也不是每次会话一次）。点「知道了」置真。
     */
    val recallGateHintSeen: Boolean = false,
    /**
     * 交错练习（默认**开**）：多学科的一轮练习把同科连排打散；**只有一个学科时保持原序**
     * （交错对单一材料无意义，不硬打散）。判定只有 `QuestionOrdering.interleaveBySubject` 一处，
     * `StudyViewModel` 组轮次时读这一条把它传进去 —— 关掉即完全退回取数原序，不留半截打乱。
     *
     * 效果口径是诚实的：元分析（Brunmair & Richter 2019）给的是**中等、且非普适**的收益，
     * 而它当下更费劲（Kornell & Bjork 2008）—— 主观感受与客观效果错位是这套方法的特征，不是玄学承诺。
     */
    val interleavingEnabled: Boolean = true,
    /**
     * 排期内核 id（"FSRS"|"HALF_LIFE"，值域 [KERNEL_IDS]，默认 FSRS）。
     * 消费点：`ui/study/StudyViewModel.gradeCard` 与 `CardStudyScreen` 的按钮预览（计划 A-T9）。
     * 切回 HALF_LIFE 不丢评分历史，但 fsrs_*→半衰期侧的读数是换算近似（spec §2.1），设置页要如实写这句。
     */
    val schedulingKernel: String = "FSRS",
    /**
     * 翻面/提交前的信心自评条（app.docx 模块1/2；默认**开**）。
     * 关掉 = 完全退回 v2.6 交互（不采集、confidence 列恒 NULL）；采集 UI 在计划 B Task 11-12 接，
     * 在此之前本开关无可见效果（gradeCard 的 conf 恒 null，口径同文件头）。
     */
    val confidenceEnabled: Boolean = true,
    /**
     * 翻面时那条 RECALL_FIRST 贴士（"先在脑子里把答案挤一遍，再翻这一面"），看过就没有
     * （**每次安装一次**，与 [recallGateHintSeen] 同一条纪律）。首次翻面出现时经
     * `StudyViewModel.markFlipTipSeen` 置真。默认**没看过**（false）。
     */
    val flipTipSeen: Boolean = false,
    /**
     * 首次保存带执行意图的习惯时那条 IF_THEN 贴士（计划 B Task 16），看过就没有
     * （**每次安装一次**，与 [flipTipSeen]、[recallGateHintSeen] 同一条纪律）。首次成功保存带
     * `ifThen` 的习惯时经 `HabitViewModel.markIfThenTipSeen` 置真。默认**没看过**（false）。
     */
    val ifThenTipSeen: Boolean = false,
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
        KEY_DISCLAIMER_VERSION to disclaimerVersion.toString(),
        KEY_ONBOARDING_SEEN to onboardingSeen.toString(),
        KEY_SNAKE_BEST to snakeBest.toString(),
        KEY_DAY_BOUNDARY to dayBoundaryHour.toString(),
        KEY_MAKEUP_ALLOWED to makeupAllowed.toString(),
        KEY_RESTRICT_CHECK_IN to restrictCheckIn.toString(),
        KEY_RESTRICT_START to restrictStartMin.toString(),
        KEY_RESTRICT_END to restrictEndMin.toString(),
        KEY_DICT_FAILURE to lastDictFailure,
        KEY_RECALL_BEFORE_GRADE to recallBeforeGrade.toString(),
        KEY_RECALL_GATE_HINT_SEEN to recallGateHintSeen.toString(),
        KEY_INTERLEAVING to interleavingEnabled.toString(),
        KEY_SCHEDULING_KERNEL to schedulingKernel,
        KEY_CONFIDENCE_ENABLED to confidenceEnabled.toString(),
        KEY_FLIP_TIP_SEEN to flipTipSeen.toString(),
        KEY_IF_THEN_TIP_SEEN to ifThenTipSeen.toString(),
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
        const val KEY_DISCLAIMER_VERSION = "disclaimer_version"
        const val KEY_ONBOARDING_SEEN = "onboarding_seen"
        const val KEY_DAY_BOUNDARY = "day_boundary_hour"
        const val KEY_MAKEUP_ALLOWED = "makeup_allowed"
        const val KEY_RESTRICT_CHECK_IN = "restrict_check_in"
        const val KEY_RESTRICT_START = "restrict_start_min"
        const val KEY_RESTRICT_END = "restrict_end_min"
        const val KEY_DICT_FAILURE = "last_dict_failure"
        const val KEY_RECALL_BEFORE_GRADE = "recall_before_grade"
        const val KEY_RECALL_GATE_HINT_SEEN = "recall_gate_hint_seen"
        const val KEY_INTERLEAVING = "interleaving_enabled"
        const val KEY_SCHEDULING_KERNEL = "scheduling_kernel"
        const val KEY_CONFIDENCE_ENABLED = "confidence_enabled"
        const val KEY_FLIP_TIP_SEEN = "flip_tip_seen"
        const val KEY_IF_THEN_TIP_SEEN = "if_then_tip_seen"

        /** 内核 id 的白名单；不在表内的值一律回默认——设置页 UI 也只画这两项 */
        val KERNEL_IDS = setOf("FSRS", "HALF_LIFE")

        const val DEFAULT_WORD_GOAL = 20
        const val DEFAULT_REMINDER_HOURS = 6

        /** 昵称上限：分享文本里它只占一行抬头，再长就只是把正文挤下去 */
        const val NICKNAME_MAX = 24

        /** WorkManager 的周期下限是 15 分钟，这里按小时收在 1..72（三天一次也算提醒） */
        val HOURS_RANGE = 1..72
        val WORD_GOAL_RANGE = 1..500

        /** 能接受考试日的区间：2000-01-01 至 2100-01-01 */
        val EXAM_DAY_RANGE = LocalDate.of(2000, 1, 1).toEpochDay()..LocalDate.of(2100, 1, 1).toEpochDay()

        /** 「一天」的开始小时：0~5（再大就不是熬夜而是作息颠倒了） */
        val DAY_BOUNDARY_RANGE = 0..5
        const val DEFAULT_DAY_BOUNDARY_HOUR = 0

        /** 打卡时段限制的分钟上限与默认窗口（08:00–22:00） */
        const val MINUTES_OF_DAY = 24 * 60
        const val DEFAULT_RESTRICT_START_MIN = 8 * 60
        const val DEFAULT_RESTRICT_END_MIN = 22 * 60

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
                disclaimerVersion = map[KEY_DISCLAIMER_VERSION]?.toIntOrNull()
                    ?.coerceAtLeast(0) ?: defaults.disclaimerVersion,
                onboardingSeen = map[KEY_ONBOARDING_SEEN]?.toBooleanStrictOrNull()
                    ?: defaults.onboardingSeen,
                snakeBest = map[KEY_SNAKE_BEST]?.toIntOrNull()?.coerceAtLeast(0) ?: defaults.snakeBest,
                dayBoundaryHour = map[KEY_DAY_BOUNDARY]?.toIntOrNull()?.takeIf { it in DAY_BOUNDARY_RANGE }
                    ?: defaults.dayBoundaryHour,
                makeupAllowed = map[KEY_MAKEUP_ALLOWED]?.toBooleanStrictOrNull() ?: defaults.makeupAllowed,
                restrictCheckIn = map[KEY_RESTRICT_CHECK_IN]?.toBooleanStrictOrNull()
                    ?: defaults.restrictCheckIn,
                restrictStartMin = map[KEY_RESTRICT_START]?.toIntOrNull()
                    ?.takeIf { it in 0..MINUTES_OF_DAY } ?: defaults.restrictStartMin,
                restrictEndMin = map[KEY_RESTRICT_END]?.toIntOrNull()
                    ?.takeIf { it in 0..MINUTES_OF_DAY } ?: defaults.restrictEndMin,
                // 诊断文本原样留着，包括空串；只在超长时掐掉，免得一次异常堆栈把设置页撑坏
                lastDictFailure = map[KEY_DICT_FAILURE]?.take(400) ?: defaults.lastDictFailure,
                // 闸门：默认**开**。垃圾值 / 缺键都退回默认，与其余每一项同一口径
                // （这一条决定的是手势语义，宁可维持"设计里的默认"，不该因为库里存了个"也许"就静默关掉）
                recallBeforeGrade = map[KEY_RECALL_BEFORE_GRADE]?.toBooleanStrictOrNull()
                    ?: defaults.recallBeforeGrade,
                recallGateHintSeen = map[KEY_RECALL_GATE_HINT_SEEN]?.toBooleanStrictOrNull()
                    ?: defaults.recallGateHintSeen,
                // 交错：默认**开**，与其余每一项同一口径（垃圾值 / 缺键只让本项退回默认）
                interleavingEnabled = map[KEY_INTERLEAVING]?.toBooleanStrictOrNull()
                    ?: defaults.interleavingEnabled,
                // 内核：默认 FSRS；垃圾值 / 缺键同口径，只让本项退回默认
                schedulingKernel = map[KEY_SCHEDULING_KERNEL]?.takeIf { it in KERNEL_IDS }
                    ?: defaults.schedulingKernel,
                // 信心条：默认**开**，关掉即完全退回 v2.6 交互
                confidenceEnabled = map[KEY_CONFIDENCE_ENABLED]?.toBooleanStrictOrNull()
                    ?: defaults.confidenceEnabled,
                // 翻面贴士：默认**没看过**，口径同 recallGateHintSeen（每次安装一次的说明）
                flipTipSeen = map[KEY_FLIP_TIP_SEEN]?.toBooleanStrictOrNull()
                    ?: defaults.flipTipSeen,
                // 执行意图贴士：默认**没看过**，口径同 flipTipSeen（每次安装一次的说明）
                ifThenTipSeen = map[KEY_IF_THEN_TIP_SEEN]?.toBooleanStrictOrNull()
                    ?: defaults.ifThenTipSeen,
            )
        }
    }
}
