package com.studykit.ui.habit

import java.time.LocalDate
import kotlin.math.roundToInt

/**
 * 习惯养成的科学化计算与文案规则。
 *
 * 三条证据落点：
 * - Lally et al. 2010（EJSP）：习惯自动化中位数约 66 天（18–254 天），「21 天」没有证据；
 *   偶尔漏做一次不会破坏养成过程。据此把默认目标改为 66 天，并引入周达标率与断签保护卡。
 * - Gollwitzer & Sheeran 2006 元分析：执行意图（if-then）d≈0.65，故创建习惯时绑定时间+地点+行为。
 * - 目标梯度（Kivetz et al. 2006）：接近目标时努力上升，故按阶段给出进度文案。
 */
object HabitScience {

    /** 研究中的习惯形成中位数，取代「21 天养成习惯」的说法 */
    const val DEFAULT_TARGET_DAYS = 66

    /** 一周允许缺席的天数下限：达标只看 5/7，不做全有全无 */
    const val WEEKLY_TARGET_DAYS = 5

    /** 每月免费提供的断签保护卡数量 */
    const val MONTHLY_PROTECTION_CARDS = 2

    /** 超过这个长度的空白期不再视为「偶尔漏一天」 */
    const val MAX_FORGIVABLE_GAP = 7

    /** 66 天中位数的默认提示文案（不得再宣称 21 天必成） */
    const val GOAL_HINT = "习惯自动化研究中位数约 66 天，个体差异 18–254 天。慢一点不是失败，允许自己慢慢来。"

    /** 阶段划分：早期靠线索重复，中期靠身份认同，后期才是自动化 */
    enum class Phase { EARLY, MIDDLE, MASTERY }

    /** 执行意图三元组：什么时候、在哪里、做什么 */
    data class IntentionPlan(val text: String, val isComplete: Boolean)

    /** 预置执行意图模板，创建习惯时一键带入 */
    data class IntentionTemplate(val label: String, val timeSlot: String, val place: String, val action: String)

    val INTENTION_TEMPLATES = listOf(
        IntentionTemplate("早起背词", "早上起床后", "书桌前", "背 10 个单词"),
        IntentionTemplate("通勤复习", "坐上地铁后", "车厢里", "复习 20 张到期卡片"),
        IntentionTemplate("睡前阅读", "洗完澡以后", "床边", "读 10 页书并摘 1 句"),
        IntentionTemplate("午后刷题", "午休结束后", "教室", "做 5 道题并记录错因"),
        IntentionTemplate("晚间复盘", "写完作业后", "书桌前", "重做 3 道错题"),
    )

    /** 打卡日期集合（近 N 天窗口） */
    data class WeeklyRate(val checkedDays: Int, val windowDays: Int, val percent: Int) {
        fun metTarget(target: Int): Boolean = checkedDays >= target
    }

    /** 含断签保护的连续结果 */
    data class StreakResult(val streak: Int, val cardsUsed: Int, val gaps: Int, val keptStreak: Boolean)

    /** 习惯叠加提示：把新习惯挂在已有线索之后 */
    data class StackAnchor(val name: String, val timeSlot: String)

    data class StackPlan(val text: String, val isComplete: Boolean)

    /**
     * 断签保护卡的月度额度。
     *
     * [period] 形如 `2026-09`。不等于当月时视为已刷新（used 归零），
     * 这样「本月用完卡」不会连带往后几个月。
     */
    data class Quota(val period: String, val cards: Int, val used: Int) {
        val left: Int get() = (cards - used).coerceAtLeast(0)
    }

    /** 月份键：yyyy-MM，个位月份补零 */
    fun monthKey(date: LocalDate): String =
        date.year.toString() + "-" + date.monthValue.toString().padStart(2, '0')

    /** 跨月则回满额度；同月保持原样 */
    fun refreshQuota(
        quota: Quota,
        today: LocalDate,
        monthlyCards: Int = MONTHLY_PROTECTION_CARDS,
    ): Quota = if (quota.period == monthKey(today)) {
        quota.copy(cards = monthlyCards)
    } else {
        Quota(period = monthKey(today), cards = monthlyCards, used = 0)
    }

    /** 余额是否还能再花一张 */
    fun canSpend(quota: Quota): Boolean = quota.left > 0

    /** 花掉一张；已用完时原样返回，不让 used 越过 cards */
    fun spendCard(quota: Quota): Quota = if (canSpend(quota)) quota.copy(used = quota.used + 1) else quota

    /** 已过天数落在哪个阶段 */
    fun phase(days: Int): Phase = when {
        days < 30 -> Phase.EARLY
        days < 60 -> Phase.MIDDLE
        else -> Phase.MASTERY
    }

    /** 近 7 天达标率：漏一天只是 6/7，不是「前功尽弃」 */
    fun weeklyRate(dates: Set<LocalDate>, today: LocalDate, windowDays: Int = 7): WeeklyRate {
        val checked = (0 until windowDays).count { today.minusDays(it.toLong()) in dates }
        val percent = (checked * 100.0 / windowDays).roundToInt()
        return WeeklyRate(checked, windowDays, percent)
    }

    /**
     * 弹性连续天数：允许用断签保护卡跨过短期空白。
     *
     * 一张卡抵一天缺席；空白超过 [MAX_FORGIVABLE_GAP] 天不再弥补；
     * 卡用完仍遇到缺口时链条才真正断掉。
     * 往回扫到最早一条记录之前仍无打卡，算「习惯尚未开始」而不是断档。
     */
    fun streakWithProtection(
        dates: Set<LocalDate>,
        today: LocalDate,
        protectionCards: Int,
    ): StreakResult {
        if (dates.isEmpty()) return StreakResult(0, 0, 0, false)
        var cursor = if (today in dates) today else today.minusDays(1)
        if (cursor !in dates) return StreakResult(streak = 0, cardsUsed = 0, gaps = 0, keptStreak = false)

        val oldest = dates.min()
        var streak = 0
        var used = 0
        var uncrossed = 0
        while (true) {
            if (cursor in dates) {
                streak++
                cursor = cursor.minusDays(1)
                continue
            }
            // cursor 这天缺席，在可宽慰窗口内往回找出下一次打卡
            var probe = cursor.minusDays(1)
            var missing = 1
            while (probe !in dates && missing <= MAX_FORGIVABLE_GAP && probe >= oldest) {
                probe = probe.minusDays(1)
                missing++
            }
            if (probe !in dates) {
                // 更早还有记录 => 真断档（或空白过长）；否则是习惯开头，不扣链条
                if (oldest.isBefore(cursor)) uncrossed++
                break
            }
            if (used + missing > protectionCards) {
                uncrossed++
                break
            }
            used += missing
            cursor = probe
        }
        return StreakResult(streak = streak, cardsUsed = used, gaps = uncrossed, keptStreak = uncrossed == 0)
    }

    /** 渲染执行意图；时间或地点缺失时降级成普通愿望句，并标记为不完整 */
    fun intention(timeSlot: String, place: String, action: String): IntentionPlan {
        val t = timeSlot.trim()
        val p = place.trim()
        val a = action.trim()
        val complete = t.isNotBlank() && p.isNotBlank() && a.isNotBlank()
        val text = if (complete) {
            "当我${t}坐在${p}，我就${a}。"
        } else {
            "我要${a.ifBlank { "开始行动" }}。"
        }
        return IntentionPlan(text = text, isComplete = complete)
    }

    /** 习惯叠加：把新行为接到既有习惯的尾巴上 */
    fun stackWith(action: String, anchor: StackAnchor?): StackPlan {
        val a = action.trim()
        if (anchor == null || a.isBlank()) {
            return StackPlan(text = if (a.isBlank()) "先定下一个具体行为。" else "$a。", isComplete = false)
        }
        return StackPlan(text = "在${anchor.name}之后，我立刻$a。", isComplete = true)
    }

    /** 阶段文案：把「还在 66 天路上」讲清楚，避免 21 天到期即弃 */
    fun phaseHint(days: Int): String = when (phase(days)) {
        Phase.EARLY -> "第 $days 天：还在建立线索阶段，靠固定时间地点重复，不靠意志力。"
        Phase.MIDDLE -> "第 $days 天：过半了，行为开始不需要提醒，注意别在 60 天前后松劲。"
        Phase.MASTERY -> "第 $days 天：已超过研究中位数 66 天，这个行为基本自动化了。"
    }
}
