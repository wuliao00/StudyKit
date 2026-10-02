package com.studykit.ui.habit

import java.time.LocalDate
import java.time.YearMonth

/**
 * 断签保护的**展示层派生**（app.docx 模块4；计划 B Task 17 / spec D6）。
 *
 * 纯函数、零 Compose、零 Android —— 只在 [HabitGuard]（Plan A 的额度判定：`guardedIndices` /
 * `breaksAt` / `MISSING_GUARD_TEXT`）之上，把「逐日布尔串」翻译成日历与打卡界面要画的三件事：
 * 某格是不是受保护的缺卡（灰圈）、连续数要不要跨过它、这次缺卡值不值得温和提示一句。
 *
 * ## 串只画到今天及以前（B17 复审 🔴1）
 * 进行中的月份如果把今天之后的日子也展开进串，每一个未来日都会被判成"缺卡"：灰圈爬到
 * 还没来的格子上、点它还会弹"未打卡（已用断签保护）"骗人一个还没发生的日子，而
 * [monthStreakThrough] 从串尾往回走，第一个就是没额度可用的破链缺卡 ⇒ 「本月连续」中旬就显示 0。
 * 所以 [monthMissPattern] 多一个**注入**的 [java.time.LocalDate]（同文件
 * [gapDayHintEligible]、也同仓那几处纯函数的纪律），这里**不**读系统时钟。
 *
 * ## 为什么不改 [habitStreak]
 * `HabitLogicTest` 钉着 [habitStreak] 的原始口径（"断了就回到 0"、逐日回溯到具体日期集合），
 * 那是列表副标题与全局最高连的**既有事实来源**，动它等于改一条已被单测冻结的语义。
 * 断签保护是「宽恕展示」，不是「重写事实」，所以这里另起一份**按自然月**的派生：
 *  [monthStreakThrough] 只在单月串上算"跨过本月保护日还能连几天"，不跨月、不落库（spec D6）。
 * 两者口径不同是刻意的，日历页用这份、列表用那份，各自自洽。
 *
 * 缺卡按出现顺序取本月前 [HabitGuard.MONTHLY_ALLOWANCE] 次为受保护，与 [HabitGuard] 完全同一条线。
 */

/**
 * 把某个自然月逐日展开成"打没打卡"的布尔串（下标 0 = 1 号）。
 * 展示层所有派生（灰圈、连续跨保护、额度是否用尽）都吃这一份，口径全仓唯一。
 *
 * 长度按 [today] 裁：串到 [month] 那一天为止只画到**今日**（进行中的月不给未来日造缺卡），
 * 其余（已经过去的月）照旧铺满整月。[today] 由调用方注入，本函数不读时钟。
 */
internal fun monthMissPattern(
    checkedDates: Set<LocalDate>,
    month: YearMonth,
    today: LocalDate,
): List<Boolean> {
    val lastDay = if (month == YearMonth.from(today)) today.dayOfMonth else month.lengthOfMonth()
    return (1..lastDay).map { month.atDay(it) in checkedDates }
}

/**
 * 连续数**跨受保护缺卡日延续**：从串尾往回走，遇打勾的、或落在本月前 2 次缺卡额度里的都算进串，
 * 走到"第 3 次及以后的缺卡"（即 [HabitGuard.breaksAt] 为真的那一格）就断，返回尾巴这段长度。
 * 全月都不断时返回整串长度；串尾恰好是破链缺卡时返回 0。空串返回 0。
 *
 * 名字里的 `month` 是把口径说死：它与 [habitStreak]（列表副标题、
 * 全局最长连用的逐日回溯）不是同一个数——这一份只在**单月串**上算跨保护日的连续数。
 */
internal fun monthStreakThrough(missPattern: List<Boolean>): Int {
    if (missPattern.isEmpty()) return 0
    val guarded = HabitGuard.guardedIndices(missPattern).toSet()
    var streak = 0
    var i = missPattern.lastIndex
    while (i >= 0) {
        val covered = missPattern[i] || i in guarded
        if (!covered) break
        streak += 1
        i -= 1
    }
    return streak
}

/**
 * 打卡界面是否值得挂那条 MISS_ONE_DAY（[TipEvent.GapDay]）宽恕贴士：
 *  - 今天**还**没打卡（[dates] 不含 [today]）—— 正在打卡这件事的当口才谈得上宽恕；
 *  - 昨天**缺**了（不含昨天）—— 缺的是昨天，不是今天还没发生的事；
 *  - 昨天这一次缺卡仍落在**它所在自然月**的前 [HabitGuard.MONTHLY_ALLOWANCE] 次额度里（额度未用尽）——
 *    若昨天已是本月第 3 次缺，那是真的断了链，此刻再劝"别有负担"就假了，故不提示。
 * 三条同时成立才 true。
 */
internal fun gapDayHintEligible(dates: Set<LocalDate>, today: LocalDate): Boolean {
    if (today in dates) return false
    val yesterday = today.minusDays(1)
    if (yesterday in dates) return false
    val pattern = monthMissPattern(checkedDates = dates, month = YearMonth.from(yesterday), today = today)
        .subList(0, yesterday.dayOfMonth) // 只看 1 号到昨天，今天的空缺不算进额度
    return HabitGuard.guardedIndices(pattern).contains(pattern.lastIndex)
}

/**
 * 那条 MISS_ONE_DAY（[TipEvent.GapDay]）宽恕贴士的**每次安装一次**闸门（B17 复审 ⚠3）。
 *
 * [gapDayHintEligible] 只说"这一刻值不值得提"，它每遇一次昨日缺卡都会为真，而计划里这句只说一次。
 * 两个触发点共用这一个判定，再一起交给**列表层**那一张 [com.studykit.ui.components.TipCard]
 * 渲染（`HabitListScreen` 的 [OneShotTipCard]），免得天数型一键打卡（根本没有弹层）
 * 与数量型打卡待遇不同：
 *  - 弹层开起来那一刻（数量型 / 已打卡改备注）；弹层收掉后这一句就在列表上亮；
 *  - 天数型一键打卡直接进账那一下（判定要在 `checkIn` **之前**取，打完今天就不再 eligible 了）。
 * 谁先亮谁当场经 `HabitViewModel.markGapTipSeen` 落库，另一路之后自然不亮。
 */
internal fun gapTipShouldShow(eligible: Boolean, gapTipSeen: Boolean): Boolean = eligible && !gapTipSeen
